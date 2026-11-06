package cn.bugstack.ai.cases.react.node;

import cn.bugstack.ai.api.dto.ChatRequestDTO;
import cn.bugstack.ai.api.dto.ReActResultDTO;
import cn.bugstack.ai.cases.react.AbstractAIAgentReActSupport;
import cn.bugstack.ai.cases.react.factory.DefaultReActFactory;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import cn.bugstack.ai.domain.agent.model.valobj.intent.IntentResultVO;
import cn.bugstack.ai.domain.agent.model.valobj.intent.TaskStateVO;
import cn.bugstack.ai.domain.agent.service.IChatContextService;
import cn.bugstack.ai.domain.agent.service.IIntentService;
import cn.bugstack.ai.domain.agent.service.ILongTermMemoryService;
import cn.bugstack.ai.domain.agent.service.IPromptService;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.ai.domain.agent.service.armory.matter.tools.SshExecuteAdkTool;
import cn.bugstack.ai.domain.agent.service.intent.IntentService;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.runner.Runner;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import jakarta.annotation.Resource;
import java.util.*;

/**
 * AI 调用节点（ReAct 循环核心）
 *
 * <p>职责：
 * 1. 调用 ADK runner.runAsync() 获取事件流
 * 2. 处理文本内容，发送 SSE 事件
 * 3. 订阅真实工具回调产生的执行记录
 * 4. 如果有工具调用：存储到上下文，发送 SSE 事件，路由到 ToolCallNode
 * 5. 如果无工具调用：路由到 LoopDecisionNode
 *
 * <p>核心修复：
 * Spring AI 内部自动执行工具时，Runner 不一定转发中间函数事件。
 * 工具身份、参数和次数均从执行入口获取，stateDelta 不参与工具审计。
 *
 * <p>ReAct 循环流程：
 * <pre>
 * RootNode
 *   └→ AiCallNode（调用 ADK runner，解析事件）
 *         ├→ [有真实工具记录] ToolCallNode → AiCallNode（循环）
 *         └→ [无工具调用] LoopDecisionNode → UserFeedbackNode
 * </pre>
 *
 * <p>意图识别接入（Phase 3）：本节点是意图子系统与 ReAct 主链路的唯一接入点，
 * 负责三件事——识别、注入、反馈：
 * <pre>
 *   doApply()
 *     ┌─ 识别：intentService.configure(agentApi) + classify(msg)
 *     │        → 结果存入 dynamicContext.currentIntent / currentIntentResult
 *     │        → COMPOUND/UNKNOWN/低置信度 不硬路由，全交主模型
 *     ├─ 注入：buildEnrichedMessageWithDynamicContext()
 *     │        → currentIntent 经 PromptContextVO.intentLabel 进入消息前缀 [用户意图]
 *     └─ 反馈：工具执行后 handleIntentFeedback(toolResult)
 *              → 失败且走偏时 reportFeedback 重分类，更新 currentIntent
 * </pre>
 * 意图标签只做"提示"不做"硬路由"：即便识别为 DIAGNOSE，主模型仍可自主决定调用哪些工具。
 *
 * @author xiaofuge bugstack.cn @小傅哥
 */
@Slf4j
@Component("reactAiCallNode")
public class AiCallNode extends AbstractAIAgentReActSupport {

    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    @Resource
    private IPromptService promptService;

    @Resource
    private IChatContextService chatContextService;

    @Resource
    private IIntentService intentService;

    @Resource
    private ILongTermMemoryService longTermMemoryService;

    @Resource
    private cn.bugstack.ai.domain.agent.service.audit.ToolExecutionAudit toolExecutionAudit;

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        log.info("ReAct AiCallNode - 开始 AI 调用，第 {} 步", dynamicContext.getStep() + 1);

        String agentId = dynamicContext.getAgentId();

        // 1. 获取 Agent 注册信息和 ADK Runner
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);
        if (aiAgentRegisterVO == null) {
            throw new RuntimeException("Agent not found: " + agentId);
        }

        Runner runner = aiAgentRegisterVO.getRunner();

        // 2. 获取最新用户消息
        String lastUserMessage = getLastUserMessage(requestParameter, dynamicContext);

        // [Phase 3] 意图识别 —— 注入当前 Agent 的 API 配置后，再识别用户意图
        // 复用智能体自己的模型配置，不单独配置意图识别模型
        // 步骤：①configure 注入 API → ②classify 识别 → ③存入上下文 → ④不硬路由
        if (aiAgentRegisterVO.getOpenAiApi() != null) {
            intentService.configure(aiAgentRegisterVO.getOpenAiApi(), aiAgentRegisterVO.getChatModelName());
        }
        IntentResultVO intentResult = intentService.classify(
                dynamicContext.getSessionId(), dynamicContext.getUserId(), lastUserMessage);
        log.info("识别到用户意图: {}, 置信度: {}, 候选: {}, 重分类: {}",
                intentResult.getIntent().getLabel(),
                intentResult.getConfidence(),
                intentResult.getCandidateIntents(),
                intentResult.isReclassified());
        // 将意图保存到上下文供后续使用
        dynamicContext.setCurrentIntent(intentResult.getIntent().name());
        dynamicContext.setCurrentIntentResult(intentResult);
        syncTaskStateAfterClassification(dynamicContext, lastUserMessage, intentResult);
        // COMPOUND / UNKNOWN / 低置信度：交给主模型自行判断，不再硬路由
        if (intentResult.getIntent() == cn.bugstack.ai.domain.agent.model.valobj.intent.IntentTypeEnumVO.COMPOUND) {
            log.info("复合意图，候选 {} —— 交由主模型拆解", intentResult.getCandidateIntents());
        } else if (intentResult.getIntent() == cn.bugstack.ai.domain.agent.model.valobj.intent.IntentTypeEnumVO.UNKNOWN
                || intentResult.getConfidence() < 0.5) {
            log.info("意图不确定 ({}，conf={}) —— 全交主模型决策", intentResult.getIntent(), intentResult.getConfidence());
        }

        // 3. 重置当前轮次缓冲
        dynamicContext.resetRoundBuffers();
        dynamicContext.resetRoundToolCalls();

        // 4. 裁剪消息历史（优先级 + 滑动窗口混合策略，8000 token 预算） - 这部分也可以作为配置，根据模型不同来调整。
        List<Map<String, Object>> trimmedHistory = chatContextService.trimHistory(dynamicContext.getMessageHistory(), 8000);
        dynamicContext.setMessageHistory(new ArrayList<>(trimmedHistory));

        // 5. 绑定终端会话 ID (使用线程和会话双重绑定)
        String terminalSessionId = dynamicContext.getTerminalSessionId();
        if (terminalSessionId != null && !terminalSessionId.isEmpty()) {
            SshExecuteAdkTool.setCurrentTerminalSession(terminalSessionId);
            bindTerminalSession(dynamicContext.getSessionId(), terminalSessionId);
        } else {
            terminalSessionId = getTerminalSession(dynamicContext.getSessionId());
            if (terminalSessionId != null) {
                SshExecuteAdkTool.setCurrentTerminalSession(terminalSessionId);
            }
        }

        // 6. 构建动态上下文并注入用户消息
        String enrichedMessage = buildEnrichedMessage(lastUserMessage, dynamicContext);
        log.debug("注入动态上下文后消息长度: {} -> {}", lastUserMessage.length(), enrichedMessage.length());

        // 用户消息落库 + 长期记忆提取（用户侧）：委托领域服务完成"消息落库 + 偏好记忆提取"闭环，
        // case 层不再直接调用仓储层。仅首轮（step==0）落库 user 消息，避免多轮循环重复写入。
        longTermMemoryService.saveUserMessage(
                dynamicContext.getUserId(),
                dynamicContext.getSessionId(),
                lastUserMessage,
                dynamicContext.getCurrentIntent(),
                dynamicContext.getStep() == 0
        );

        // 7. 构建用户消息
        Content userContent = Content.builder()
                .role("user")
                .parts(Part.builder().text(enrichedMessage).build())
                .build();

        // 8. 重置 ReAct 循环标志
        dynamicContext.setStopReason(null);
        dynamicContext.setErrorMessage(null);

        // 9. 调用 ADK Runner 并处理事件流
        ResponseBodyEmitter emitter = dynamicContext.getEmitter();
        StringBuilder textAccumulator = new StringBuilder();
        boolean hasError = false;
        StringBuilder errorBuilder = new StringBuilder();

        log.info("调用 ADK Runner，用户消息: {}", lastUserMessage.length() > 200
                ? lastUserMessage.substring(0, 200) + "..." : lastUserMessage);

        if (dynamicContext.getAuditSubscription() == null) {
            dynamicContext.setAuditSubscription(toolExecutionAudit.subscribe(dynamicContext.getUserId(),
                    dynamicContext.getSessionId(), notification -> recordToolExecution(dynamicContext, notification.record())));
        }
        try {
            var guard = dynamicContext.getAuditSubscription().guard();
            if (guard != null) guard.checkActive();
            // ADK Runner 会自动执行工具（SpringAI ChatModel.call() 内部执行）
            // 工具记录通过回调订阅到达，不依赖 Runner 是否转发 functionCall/functionResponse。
            Iterator<Event> events = runner.runAsync(
                    dynamicContext.getUserId(),
                    dynamicContext.getSessionId(),
                    userContent,
                    cn.bugstack.ai.domain.agent.service.execution.ExecutionRunConfig.builder().build()
            ).blockingIterable().iterator();

            int eventCount = 0;
            while (events.hasNext()) {
                Event event = events.next();
                eventCount++;

                event.stringifyContent();
                log.debug("处理第 {} 个事件: final={}, content_len={}",
                        eventCount,
                        event.finalResponse(),
                        event.stringifyContent().length());

                // 9.1 处理文本内容（模型的响应文本，包括工具调用后的总结）
                String eventText = event.stringifyContent();
                if (!eventText.isBlank()) {
                    textAccumulator.append(eventText);
                    dynamicContext.setAssistantContent(textAccumulator);
                    sendTextEvent(emitter, eventText, textAccumulator.toString());
                }

                // 工具记录由真实回调订阅更新，stateDelta 仅是 Agent 状态，不代表工具执行。

                // 9.3 记录 assistant 内容到消息历史
                if (event.content().isPresent()) {
                    Content content = event.content().get();
                    String role = content.role().orElse("assistant");
                    if ("assistant".equals(role)) {
                        String text = event.stringifyContent();
                        if (!text.isBlank()) {
                            dynamicContext.appendAssistantMessage(text);
                        }
                    }
                }
            }

            log.info("ADK Runner 事件流处理完成，共 {} 个事件", eventCount);
            guard = dynamicContext.getAuditSubscription().guard();
            if (guard != null) guard.checkActive();

        } catch (Exception e) {
            log.error("ADK Runner 调用失败", e);
            hasError = true;
            errorBuilder.append("ADK Runner error: ").append(e.getMessage());
            dynamicContext.setErrorMessage(errorBuilder.toString());
            var stopped = cn.bugstack.ai.domain.agent.service.execution.ExecutionHaltedException.find(e);
            var guard = dynamicContext.getAuditSubscription().guard();
            dynamicContext.setStopReason(guard != null && guard.stopReason() != null ? guard.stopReason() : stopped == null ? "error" : stopped.reason());
            if (guard != null && guard.stopReason() != null) dynamicContext.setErrorMessage(guard.summary());
            if ("WAITING_APPROVAL".equals(dynamicContext.getStopReason())) {
                hasError=false; dynamicContext.setErrorMessage(null);
                textAccumulator.setLength(0); textAccumulator.append("变更方案已保存，等待你确认后执行。");
            }
        } finally {
            // 清除当前线程绑定的终端会话，但保留 session 的映射关系
            SshExecuteAdkTool.clearCurrentTerminalSession();
        }

        // 10. 更新步数统计
        dynamicContext.incrementStep();
        dynamicContext.getResult().setTotalSteps(dynamicContext.getStep());

        log.info("ReAct AiCallNode - 第 {} 步完成，本轮工具调用 {} 次，文本长度 {}",
                dynamicContext.getStep(), dynamicContext.getRoundToolCallCount().get(), textAccumulator.length());

        if (!textAccumulator.isEmpty()) {
            // 助手回复落库 + 结论记忆提取：委托领域服务完成闭环。
            longTermMemoryService.saveAssistantMessage(
                    dynamicContext.getUserId(),
                    dynamicContext.getSessionId(),
                    textAccumulator.toString()
            );
        }

        // 11. 发送本轮结束事件
        sendRoundEndEvent(
                dynamicContext.getEmitter(),
                dynamicContext.getStep(),
                dynamicContext.getMaxSteps(),
                !hasError,
                dynamicContext.getTotalToolCallCount().get()
        );

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
            ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {

        List<Map<String, Object>> toolCalls = dynamicContext.getCurrentToolCalls();

        // 明确当前架构：ADK 自动执行主导。如果有工具调用，进入 ToolCallNode 主要是做日志和事件补偿。
        if (toolCalls != null && !toolCalls.isEmpty()) {
            log.info("本轮发现工具调用，路由到 ToolCallNode 处理结果事件");
            return getBean("reactToolCallNode");
        }

        log.info("本轮无工具调用，路由到 LoopDecisionNode");
        return getBean("reactLoopDecisionNode");
    }

    // ═══════════════════════════════════════════════════════════════
    //  辅助方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 获取最新的一条用户消息
     */
    private String getLastUserMessage(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) {
        // 第一轮使用请求参数中的消息
        if (dynamicContext.getStep() == 0) {
            return requestParameter.getMessage();
        }

        // 后续轮次从历史记录中获取最后一条 user 消息
        List<Map<String, Object>> history = dynamicContext.getMessageHistory();
        for (int i = history.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = history.get(i);
            if ("user".equals(msg.get("role"))) {
                return (String) msg.get("content");
            }
        }

        return requestParameter.getMessage();
    }

    private void recordToolExecution(DefaultReActFactory.DynamicContext context,
            cn.bugstack.ai.domain.agent.service.audit.ToolExecutionRecord record) {
        synchronized (context) {
            if ("RUNNING".equals(record.status())) {
                Map<String, Object> call = new HashMap<>();
                call.put("id", record.executionId());
                call.put("name", record.toolName());
                call.put("args", record.arguments());
                context.getCurrentToolCalls().add(call);
                context.getExecutedToolCalls().add(call);
                context.incrementTotalToolCalls();
                context.incrementRoundToolCalls();
                sendToolCallEvent(context.getEmitter(), record.executionId(), record.toolName(), "executing");
            } else {
                String content = record.error() != null ? record.error() : record.result();
                String status = switch (record.status()) {
                    case "RETURNED" -> "success";
                    case "BLOCKED" -> "blocked";
                    case "UNKNOWN" -> "unknown";
                    case "WAITING_APPROVAL" -> "pending";
                    default -> "error";
                };
                Map<String, Object> result = new HashMap<>();
                result.put("id", record.executionId());
                result.put("name", record.toolName());
                result.put("content", content);
                result.put("status", status);
                context.getCurrentToolResults().add(result);
                Map<String, Object> event = new HashMap<>(result);
                event.put("event", "tool_result");
                event.put("toolCallId", record.executionId());
                event.put("toolName", record.toolName());
                event.put("toolArgs", record.arguments());
                event.put("executionStatus", record.status());
                boolean recoverable = false;
                if (record.result() != null) {
                    try {
                        var structured = objectMapper.readTree(record.result());
                        recoverable = structured.path("rewriteAllowed").asBoolean(false);
                        for (String field : new String[]{"failureType", "ruleId", "policyDecision", "commandDispatched", "rewriteAllowed", "rewritesRemaining", "approvalId"})
                            if (structured.has(field)) event.put(field, objectMapper.convertValue(structured.get(field), Object.class));
                    } catch (Exception ignored) { }
                }
                try {
                    if (context.getEmitter() != null) context.getEmitter().send(objectMapper.writeValueAsString(event) + "\n");
                } catch (Exception error) { log.warn("发送工具结果事件失败: {}", error.getMessage()); }
                if (!recoverable) promptService.detectAndRecordMilestone(context.getSessionId(), "tool", content);
                chatContextService.pushToolResult(context.getSessionId(), record.toolName(), content);
                if (!recoverable) handleIntentFeedback(context, content);
                if ("executeCommand".equals(record.toolName()) && !Set.of("BLOCKED","WAITING_APPROVAL").contains(record.status())) recordExecutedCommand(context, record.arguments());
            }
        }
    }

    /**
     * 格式化状态值为字符串
     */
    private String formatStateValue(Object stateValue) {
        if (stateValue == null) {
            return "";
        }
        if (stateValue instanceof String) {
            return (String) stateValue;
        }
        try {
            return objectMapper.writeValueAsString(stateValue);
        } catch (Exception e) {
            return stateValue.toString();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Phase 1: 动态上下文注入
    // ═══════════════════════════════════════════════════════════════

    /**
     * 记录执行的命令到最近命令列表 (修改为记录真实的 command 参数)
     */
    private void recordExecutedCommand(DefaultReActFactory.DynamicContext dynamicContext, String command) {
        if (command != null && !command.isBlank()) {
             dynamicContext.addRecentCommand(truncate(command, 200));
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }

    /**
     * 反馈回路：根据工具执行结果判定当前意图是否需要重分类。
     * <p>
     * 仅在本轮已有意图识别结果时触发；reportFeedback 返回非 null 表示已重分类，
     * 此时更新 DynamicContext 的当前意图，使后续步骤（Prompt 注入、路由）使用新意图。
     * <p>
     * 流程：
     * <pre>
     *   工具执行完(result)
     *     ├─ 无本轮意图结果 → 直接返回
     *     ├─ 判定 success（非空 && 不像意图走偏）
     *     └─ intentService.reportFeedback(...)
     *          ├─ 返回 null  → 维持原意图
     *          └─ 返回新结果 → 更新 currentIntent / currentIntentResult
     * </pre>
     * 案例：意图 CONFIGURE，工具结果 "No such file" → success=false →
     *       reportFeedback 用候选 MONITOR 递补 → 后续 Prompt 注入 [用户意图] 监控查看。
     */
    private void handleIntentFeedback(DefaultReActFactory.DynamicContext dynamicContext, String toolResult) {
        IntentResultVO lastIntent = dynamicContext.getCurrentIntentResult();
        if (lastIntent == null) {
            return;
        }
        // 复用 IntentService 的失败特征判定，保持两处逻辑一致
        boolean success = toolResult != null && !toolResult.isBlank()
                && !IntentService.looksLikeIntentMismatch(toolResult);

        IntentResultVO reclassified = intentService.reportFeedback(
                dynamicContext.getSessionId(), lastIntent, success, toolResult);
        if (reclassified != null) {
            log.info("反馈回路触发重分类: {} -> {} (conf={})",
                    lastIntent.getIntent(), reclassified.getIntent(), reclassified.getConfidence());
            dynamicContext.setCurrentIntent(reclassified.getIntent().name());
            dynamicContext.setCurrentIntentResult(reclassified);
        }

        updateTaskStateAfterFeedback(dynamicContext, success, reclassified);
    }

    /**
     * 分类后同步任务态：处理 CONTINUE 续接、非业务意图跳过、新任务创建/覆盖。
     * <p>
     * 策略：
     * <ul>
     *   <li>CONTINUE：如有进行中任务态则校准步骤索引、清除失败标记，并回写 currentIntent 为根意图</li>
     *   <li>UNKNOWN/CHAT：不维护任务态，直接返回</li>
     *   <li>其他业务意图：无任务态/已完成/意图变更 → 创建新 TaskStateVO；否则复用并刷新</li>
     * </ul>
     *
     * @param @param lastUserMessage 最新用户消息（作为任务描述）
     * @param intentResult   本轮意图识别结果
     */
    private void syncTaskStateAfterClassification(DefaultReActFactory.DynamicContext dynamicContext,
                                                  String lastUserMessage,
                                                  IntentResultVO intentResult) {
        if (intentResult == null) {
            return;
        }

        String sessionId = dynamicContext.getSessionId();
        IntentTypeEnumVO intent = intentResult.getIntent();
        TaskStateVO taskState = intentService.getTaskState(sessionId);

        if (intent == IntentTypeEnumVO.CONTINUE) {
            if (taskState != null && !taskState.isCompleted()) {
                if (taskState.getCurrentStepIndex() < 0) {
                    taskState.setCurrentStepIndex(0);
                }
                taskState.setLastFeedbackFailed(false);
                intentService.updateTaskState(sessionId, taskState);
                if (taskState.getRootIntent() != null) {
                    dynamicContext.setCurrentIntent(taskState.getRootIntent().name());
                }
            }
            return;
        }

        if (intent == IntentTypeEnumVO.UNKNOWN || intent == IntentTypeEnumVO.CHAT) {
            return;
        }

        boolean shouldReplace = taskState == null
                || taskState.isCompleted()
                || taskState.getRootIntent() != intent;

        if (shouldReplace) {
            List<String> steps = new ArrayList<>();
            steps.add(lastUserMessage);
            taskState = TaskStateVO.builder()
                    .taskDescription(lastUserMessage)
                    .rootIntent(intent)
                    .steps(steps)
                    .currentStepIndex(0)
                    .completed(false)
                    .lastFeedbackFailed(false)
                    .build();
        } else {
            if (taskState.getTaskDescription() == null || taskState.getTaskDescription().isBlank()) {
                taskState.setTaskDescription(lastUserMessage);
            }
            if (taskState.getSteps() == null || taskState.getSteps().isEmpty()) {
                taskState.setSteps(new ArrayList<>(List.of(lastUserMessage)));
            }
            if (taskState.getCurrentStepIndex() < 0) {
                taskState.setCurrentStepIndex(0);
            }
            taskState.setCompleted(false);
            taskState.setLastFeedbackFailed(false);
        }

        intentService.updateTaskState(sessionId, taskState);
    }

    /**
     * 反馈后更新任务态：成功推进步骤索引，失败标记 lastFeedbackFailed，
     * 重分类时替换 rootIntent。
     * <p>
     * 流程：
     * <pre>
     *   工具反馈回调
     *     ├─ 无任务态 → 直接返回
     *     ├─ reclassified 非 null 且非兜底意图 → 替换 rootIntent
     *     ├─ success=true → currentStepIndex++ 或标记 completed
     *     └─ success=false → lastFeedbackFailed=true
     * </pre>
     *
     * @param @param success      本轮工具执行是否成功
     * @param reclassified  反馈回路重分类结果，可为 null
     */
    private void updateTaskStateAfterFeedback(DefaultReActFactory.DynamicContext dynamicContext,
                                              boolean success,
                                              IntentResultVO reclassified) {
        TaskStateVO taskState = intentService.getTaskState(dynamicContext.getSessionId());
        if (taskState == null) {
            return;
        }

        if (reclassified != null
                && reclassified.getIntent() != null
                && reclassified.getIntent() != IntentTypeEnumVO.UNKNOWN
                && reclassified.getIntent() != IntentTypeEnumVO.CONTINUE
                && reclassified.getIntent() != IntentTypeEnumVO.CHAT) {
            taskState.setRootIntent(reclassified.getIntent());
        }

        taskState.setLastFeedbackFailed(!success);

        if (success) {
            if (taskState.getSteps() == null || taskState.getSteps().isEmpty()) {
                taskState.setSteps(new ArrayList<>(List.of(taskState.getTaskDescription())));
            }
            if (taskState.getCurrentStepIndex() < 0) {
                taskState.setCurrentStepIndex(0);
            }
            if (taskState.getCurrentStepIndex() >= taskState.getSteps().size() - 1) {
                taskState.setCompleted(true);
            } else {
                taskState.setCurrentStepIndex(taskState.getCurrentStepIndex() + 1);
            }
        }

        intentService.updateTaskState(dynamicContext.getSessionId(), taskState);
    }

    /**
     * 构建注入了动态上下文的用户消息
     * 委托 IPromptService 完成环境采集、里程碑获取、前缀构建
     * <p>
     * 意图注入路径：dynamicContext.currentIntent → buildEnrichedMessage(intentLabel)
     * → PromptContextVO.intentLabel → DynamicPromptBuilder 输出 "[用户意图] xxx" 前缀，
     * 让主模型感知当前意图但不强制路由。
     */
    private String buildEnrichedMessage(String userMessage, DefaultReActFactory.DynamicContext dynamicContext) {
        // 记录用户消息的里程碑
        promptService.detectAndRecordMilestone(dynamicContext.getSessionId(), "user", userMessage);

        // 委托领域服务构建富化消息 (传入真实 userId)
        // 注意：意图标签通过 PromptContextVO.intentLabel 传递，由 DynamicPromptBuilder 输出到消息前缀
        return promptService.buildEnrichedMessage(
                userMessage,
                dynamicContext.getSessionId(),
                dynamicContext.getUserId(),
                dynamicContext.getTerminalSessionId(),
                dynamicContext.getRecentCommands(),
                dynamicContext.getMessageHistory(),
                dynamicContext.getCurrentIntent()
        );
    }

}
