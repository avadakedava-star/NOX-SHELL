package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.IAgentService;
import cn.bugstack.ai.api.dto.*;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.service.event.AgentEventPublisher;
import cn.bugstack.ai.domain.agent.service.IChatService;
import cn.bugstack.ai.domain.agent.service.armory.matter.tools.SshExecuteAdkTool;
import cn.bugstack.ai.domain.agent.service.armory.matter.tools.SshSessionContextRegistry;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import io.reactivex.rxjava3.core.Flowable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.Resource;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2026/1/20 08:23
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/")
@CrossOrigin(origins = "*")
public class AgentServiceController implements IAgentService {

    @Resource
    private cn.bugstack.ai.domain.agent.service.audit.ToolExecutionAudit toolExecutionAudit;

    @Resource
    private IChatService chatService;

    @Resource
    private cn.bugstack.ai.domain.agent.service.execution.RunFailureSummaryService failureSummaryService;

    @Resource
    private SshExecuteAdkTool sshExecuteAdkTool;

    /**
     * ReAct UI 实时可视化事件发布器。
     * <p>
     * ADK 原始事件只能沿当前 Runner 返回，而子 Agent、批量派发和 SSH 工具可能在独立 Runner/线程中执行。
     * 这里通过发布器把这些“嵌套事件”重新挂回当前 SSE 流，前端即可看到完整的工具调用与子 Agent 执行过程。
     */
    @Resource
    private AgentEventPublisher agentEventPublisher;

    @RequestMapping(value = "query_ai_agent_config_list", method = RequestMethod.GET)
    @Override
    public Response<List<AiAgentConfigResponseDTO>> queryAiAgentConfigList() {
        try {
            log.info("查询智能体配置列表");

            List<AiAgentConfigTableVO.Agent> agentConfigs = chatService.queryAiAgentConfigList();

            List<AiAgentConfigResponseDTO> responseDTOS = agentConfigs.stream().map(agentConfig -> {
                AiAgentConfigResponseDTO responseDTO = new AiAgentConfigResponseDTO();
                responseDTO.setAgentId(agentConfig.getAgentId());
                responseDTO.setAgentName(agentConfig.getAgentName());
                responseDTO.setAgentDesc(agentConfig.getAgentDesc());
                return responseDTO;
            }).collect(Collectors.toList());

            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOS)
                    .build();

        } catch (AppException e) {
            log.error("查询智能体配置列表异常", e);
            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("查询智能体配置列表失败", e);
            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "create_session", method = RequestMethod.POST)
    @Override
    public Response<CreateSessionResponseDTO> createSession(@RequestBody CreateSessionRequestDTO requestDTO) {
        try {
            log.info("创建会话 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId());
            String sessionId = chatService.createSession(requestDTO.getAgentId(), requestDTO.getUserId());

            CreateSessionResponseDTO responseDTO = new CreateSessionResponseDTO();
            responseDTO.setSessionId(sessionId);

            return Response.<CreateSessionResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            log.error("查询智能体配置列表异常", e);
            return Response.<CreateSessionResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("创建会话失败 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId(), e);
            return Response.<CreateSessionResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "create_session", method = RequestMethod.GET)
    public Response<CreateSessionResponseDTO> createSession(@RequestParam("agentId") String agentId, @RequestParam("userId") String userId) {
        CreateSessionRequestDTO requestDTO = new CreateSessionRequestDTO();
        requestDTO.setAgentId(agentId);
        requestDTO.setUserId(userId);
        return createSession(requestDTO);
    }

    @RequestMapping(value = "chat", method = RequestMethod.POST)
    @Override
    public Response<ChatResponseDTO> chat(@RequestBody ChatRequestDTO requestDTO) {
        try {
            log.info("智能体对话 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId());
            String sessionId = requestDTO.getSessionId();
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = chatService.createSession(requestDTO.getAgentId(), requestDTO.getUserId());
            }

            List<String> messages = chatService.handleMessage(requestDTO.getAgentId(), requestDTO.getUserId(), sessionId, requestDTO.getMessage());

            ChatResponseDTO responseDTO = new ChatResponseDTO();
            responseDTO.setContent(String.join("\n", messages));

            return Response.<ChatResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            log.error("智能体对话异常", e);
            return Response.<ChatResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("智能体对话败 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId(), e);
            return Response.<ChatResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    /**
     * AI 流式事件接口；你现在看到的 trigger 层接口里的逻辑比较多，而后续 case 的加入，在架构设计上，就是来分摊 trigger 层对于逻辑编排的压力的。让 trigger 的 http 处理更为简洁，只是入参、出参的处理，以及异常的封装。
     */
    @RequestMapping(value = "chat_stream", method = RequestMethod.POST, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Override
    public ResponseBodyEmitter chatStream(@RequestBody ChatRequestDTO request) {
        SseEmitter emitter=createSseEmitter(10*60*1000L);
        try {
            String sessionId=request.getSessionId();
            if(sessionId==null || sessionId.isBlank()) sessionId=chatService.createSession(request.getAgentId(),request.getUserId());
            final String chatSession=sessionId;
            Set<String> calls=ConcurrentHashMap.newKeySet(), results=ConcurrentHashMap.newKeySet();
            var activeControl=new AtomicReference<RunStreamControl>();
            var subscription=toolExecutionAudit.subscribe(request.getUserId(),chatSession,notification->{
                var active=activeControl.get();
                if(active!=null && !active.closed()) synchronized(active){sendAuditEvent(emitter,notification,calls,results);}
            });
            var control=new RunStreamControl(emitter,subscription.requestId(),subscription.guard());
            activeControl.set(control);

            Thread worker=new Thread(()->{
                var mapper=new ObjectMapper();
                var text=new StreamTextBuffer();
                Set<String> invocations=ConcurrentHashMap.newKeySet(),sessions=ConcurrentHashMap.newKeySet(),terminals=ConcurrentHashMap.newKeySet(),stateKeys=ConcurrentHashMap.newKeySet();
                AtomicInteger steps=new AtomicInteger();
                var heartbeat=Flowable.interval(15,java.util.concurrent.TimeUnit.SECONDS).subscribe(tick->control.heartbeat());
                try {
                    chatService.bindTerminalSession(chatSession,request.getTerminalSessionId());
                    AtomicReference<Consumer<AgentEventPublisher.PublishedEvent>> listenerRef=new AtomicReference<>();
                    Consumer<AgentEventPublisher.PublishedEvent> listener=published->{
                        if(control.closed()) return;
                        synchronized(control) {
                            registerInvocationListener(published.event(),agentEventPublisher,invocations,listenerRef.get());
                            try { processStateEvents(emitter,mapper,published.event(),true,stateKeys); }
                            catch(Exception sendError) { control.disconnect(); }
                        }
                    };
                    listenerRef.set(listener);
                    agentEventPublisher.registerSession(chatSession,listener);sessions.add(chatSession);
                    if(request.getTerminalSessionId()!=null && !request.getTerminalSessionId().isBlank()) {
                        agentEventPublisher.registerTerminal(request.getTerminalSessionId(),listener);terminals.add(request.getTerminalSessionId());
                    }
                    control.send(Map.of("event","status","stage","thinking","sessionId",chatSession,"content","正在分析请求…"));
                    Flowable<Event> events=Flowable.defer(()->chatService.handleMessageStream(request.getAgentId(),request.getUserId(),chatSession,request.getMessage(),request.getTerminalSessionId()));
                    control.events(events).blockingForEach(event->{
                        if(control.closed()) return;
                        synchronized(control) {
                            registerInvocationListener(event,agentEventPublisher,invocations,listenerRef.get());
                            processStateEvents(emitter,mapper,event,false,stateKeys);
                            String content=extractTextContent(event);
                            if(!content.isEmpty()) {
                                text.accept(event.invocationId()+"/"+event.author(),content,event.partial().orElse(false));
                                var update=createBaseEvent("text",event);update.put("stage","responding");update.put("fullText",text.fullText());
                                control.send(update);
                            }
                            if(event.turnComplete().orElse(false)) {
                                steps.incrementAndGet();text.endMessage(event.invocationId()+"/"+event.author());
                            }
                            if(event.errorCode().isPresent()) throw new IllegalStateException(event.errorMessage().orElse(event.errorCode().get().toString()));
                        }
                    });
                    if(control.closed()) return;
                    subscription.guard().checkActive();
                    if(text.fullText().isBlank()) throw new IllegalStateException("模型流结束，但未生成最终答复；请结合执行记录确认已完成部分");
                    if(subscription.guard().hasInFlight()) throw new IllegalStateException("根执行流结束，但仍有工具未返回；这些操作的远程结果需要核查");
                    control.finish("completed","completed",Map.of("totalSteps",steps.get(),"totalToolCalls",calls.size()));
                } catch(Exception error) {
                    if(control.closed()) return;
                    var guard=subscription.guard();
                    var halted=cn.bugstack.ai.domain.agent.service.execution.ExecutionHaltedException.find(error);
                    String reason=guard.stopReason()!=null ? guard.stopReason() : halted!=null ? halted.reason() : "EXECUTION_ERROR";
                    guard.halt(reason,"执行已停止，正在整理异常原因");
                    if(reason.equals("WAITING_APPROVAL")) {
                        control.finish("waiting_approval",reason,Map.of("totalToolCalls",calls.size()));return;
                    }
                    String cause=halted!=null ? halted.getMessage() : error.getMessage();
                    var report=cn.bugstack.ai.domain.agent.service.execution.RunFailureReport.from(reason,toolExecutionAudit.safeText(cause==null ? error.getClass().getSimpleName():cause),subscription.records());
                    // 先展示事实兜底，模型失联时也不让用户猜；随后只调用无工具总结模型。
                    String previous=text.fullText();
                    String separator=previous.isBlank()?"":"\n\n";
                    control.send(Map.of("event","text","stage","responding","fullText",previous+separator+report.fallback()));
                    control.send(Map.of("event","status","stage","responding","content","已停止执行，正在整理异常说明（最多10秒）…"));
                    String summary=failureSummaryService.summarize(request.getAgentId(),report,control.cancellation());
                    control.send(Map.of("event","text","stage","responding","fullText",previous+separator+summary));
                    control.finish("failed",reason,Map.of("totalToolCalls",calls.size(),"content",summary));
                    log.warn("请求异常结束 requestId={} reason={}",subscription.requestId(),reason);
                } finally {
                    heartbeat.dispose();subscription.close();
                    invocations.forEach(agentEventPublisher::unregister);
                    sessions.forEach(agentEventPublisher::unregisterSession);
                    terminals.forEach(agentEventPublisher::unregisterTerminal);
                    SshExecuteAdkTool.clearCurrentTerminalSession();
                }
            },"agent-stream-"+subscription.requestId());
            worker.setDaemon(true);worker.start();
        } catch(Exception error) {
            try { sendJsonEvent(emitter,new ObjectMapper(),Map.of("event","run_finished","status","failed","stopReason","INITIALIZATION_ERROR","content","无法开始本轮请求："+error.getMessage())); }
            catch(Exception sendError) { log.debug("初始化错误响应未送达",sendError); }
            emitter.complete();
        }
        return emitter;
    }

    /**
     * 将结构化事件序列化为 SSE/Emitter 两种输出格式，并串行化写入。
     * <p>
     * 嵌套 Agent 和心跳线程都可能写同一个 emitter；同步块避免事件字节交叉或响应已提交时产生竞态。
     */
    private void sendJsonEvent(ResponseBodyEmitter emitter, ObjectMapper objectMapper, Map<String, Object> payload) throws Exception {
        String body = objectMapper.writeValueAsString(payload);
        String eventName = String.valueOf(payload.getOrDefault("event", "message"));

        synchronized (emitter) {
            if (emitter instanceof SseEmitter sseEmitter) {
                sseEmitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(body, MediaType.APPLICATION_JSON));
                return;
            }

            emitter.send(body + "\n");
        }
    }

    /**
     * 首次观察到 invocation 时登记监听器，使该 invocation 后续产生的子事件回流到当前 SSE 请求。
     * registeredInvocationIds 用于去重，防止同一 invocation 在多个事件中重复注册。
     */
    private void registerInvocationListener(
            Event event,
            AgentEventPublisher publisher,
            Set<String> registeredInvocationIds,
            Consumer<AgentEventPublisher.PublishedEvent> listener) {
        if (event == null || listener == null) {
            return;
        }

        String invocationId = event.invocationId();
        if (invocationId == null || invocationId.isBlank() || !registeredInvocationIds.add(invocationId)) {
            return;
        }
        publisher.register(invocationId, listener);
    }

    /**
     * 仅将 ADK 状态变化转换成状态事件；工具事件由执行审计记录产生。
     * <p>
     * nested=true 时为子 Agent 事件增加来源标识和展示前缀；各集合负责处理 ADK 重复投递的事件。
     */
    private void processStateEvents(
            ResponseBodyEmitter emitter,
            ObjectMapper objectMapper,
            Event event,
            boolean nested,
            Set<String> emittedStateKeys) throws Exception {
        if (event == null) {
            return;
        }

        String scope = event.invocationId();
        if (scope == null || scope.isBlank()) {
            scope = nested ? "nested" : "root";
        }
        // 原始 functionCall 仅表示模型提出调用；真实执行由审计回调独立上报。
        // 不再用工具名队列配对结果，也不根据 stateDelta 伪造工具调用。
        EventActions actions = event.actions();
        if (actions != null && !actions.stateDelta().isEmpty() && event.functionResponses().isEmpty()) {
            for (Map.Entry<String, Object> entry : actions.stateDelta().entrySet()) {
                String stateKey = scope + "|" + entry.getKey();
                if (entry.getKey().startsWith("toolAudit.") || "REMOVED".equals(entry.getValue()) || !emittedStateKeys.add(stateKey)) {
                    continue;
                }

                String resultContent = extractStateDeltaContent(entry.getValue(), objectMapper);
                Map<String, Object> stateEvent = createBaseEvent("status", event);
                decorateNestedEvent(stateEvent, event, nested);
                stateEvent.put("stage", "thinking");
                stateEvent.put("content", resultContent);
                stateEvent.put("stateKey", entry.getKey());
                sendJsonEvent(emitter, objectMapper, stateEvent);
            }
        }
    }

    private void sendAuditEvent(ResponseBodyEmitter emitter,
            cn.bugstack.ai.domain.agent.service.audit.ToolExecutionAudit.Notification notification,
            Set<String> calls, Set<String> results) {
        var record = notification.record();
        boolean started = "RUNNING".equals(record.status());
        boolean blocked = "BLOCKED".equals(record.status());
        boolean waiting="WAITING_APPROVAL".equals(record.status());
        if (!(started ? calls : results).add(record.executionId())) return;
        Map<String, Object> payload = createBaseEvent(started ? "tool_call" : "tool_result", null);
        payload.put("toolCallId", record.executionId());
        payload.put("executionId", record.executionId());
        payload.put("requestId", record.requestId());
        payload.put("parentExecutionId", record.parentExecutionId());
        payload.put("toolName", record.toolName());
        if (blocked) payload.put("commandDispatched", false);
        if (!started && record.result() != null) {
            try {
                var result = new ObjectMapper().readTree(record.result());
                for (String field : new String[]{"failureType", "ruleId", "policyDecision", "commandDispatched", "attemptCount", "exitCode", "rewriteAllowed", "rewritesRemaining", "approvalId"}) {
                    if (result.has(field)) payload.put(field, new ObjectMapper().convertValue(result.get(field), Object.class));
                }
            } catch (Exception ignored) { }
        }
        payload.put("toolArgs", record.arguments());
        payload.put("sourceAgent", record.sourceAgent());
        payload.put("author", record.sourceAgent());
        payload.put("nested", record.parentExecutionId() != null);
        payload.put("executionStatus", record.status());
        payload.put("auditPersisted", notification.persisted());
        payload.put("totalToolCalls", calls.size());
        payload.put("status", started ? "running" : waiting ? "pending" : blocked ? "blocked" : "UNKNOWN".equals(record.status()) ? "unknown" : "RETURNED".equals(record.status()) ? "success" : "error");
        payload.put("stage", waiting ? "waiting_approval" : started ? "tool_running" : "thinking");
        payload.put("content", started ? "正在调用工具：" + record.toolName()
                : record.error() != null ? record.error() : record.result());
        payload.put("startedAt", record.startedAt());
        payload.put("finishedAt", record.finishedAt());
        try { sendJsonEvent(emitter, new ObjectMapper(), payload); }
        catch (Exception error) { log.debug("工具 SSE 发送失败，记录已独立保存 executionId={}", record.executionId(), error); }
    }

    /**
     * 为嵌套 Agent 事件增加前端展示所需的来源元数据，根 Agent 事件保持原有结构。
     */
    private void decorateNestedEvent(Map<String, Object> payload, Event event, boolean nested) {
        if (!nested) {
            return;
        }
        payload.put("nested", true);
        if (event.author() != null && !event.author().isBlank()) {
            payload.put("sourceAgent", event.author());
        }
    }

    /**
     * 截短 invocationId，仅用于嵌套事件的展示前缀，避免把完整内部标识暴露给 UI。
     */
    private String shortInvocationId(String invocationId) {
        return invocationId.length() <= 8 ? invocationId : invocationId.substring(0, 8);
    }

    protected SseEmitter createSseEmitter(long timeout) {
        return new SseEmitter(timeout) {
            @Override
            protected void extendResponse(ServerHttpResponse outputMessage) {
                super.extendResponse(outputMessage);
                outputMessage.getHeaders().set("Cache-Control", "no-cache, no-transform");
                outputMessage.getHeaders().set("X-Accel-Buffering", "no");
                outputMessage.getHeaders().set("Connection", "keep-alive");
            }
        };
    }

    private Map<String, Object> createBaseEvent(String eventName, Event event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", eventName);
        payload.put("timestamp", System.currentTimeMillis());
        if (event == null) {
            return payload;
        }

        if (event.author() != null && !event.author().isBlank()) {
            payload.put("author", event.author());
        }
        event.branch().ifPresent(branch -> payload.put("branch", branch));
        event.modelVersion().ifPresent(modelVersion -> payload.put("modelVersion", modelVersion));
        event.partial().ifPresent(partial -> payload.put("partial", partial));
        return payload;
    }

    private String extractTextContent(Event event) {
        return event.content()
                .flatMap(Content::parts)
                .stream()
                .flatMap(List::stream)
                .filter(part -> !part.thought().orElse(false))
                .flatMap(part -> part.text().stream())
                .collect(Collectors.joining());
    }

    private boolean isToolResponseError(FunctionResponse functionResponse) {
        return functionResponse.response()
                .map(response -> (response.containsKey("error") && response.get("error") != null)
                        || Boolean.FALSE.equals(response.get("success")))
                .orElse(false);
    }

    private String extractToolResponseContent(FunctionResponse functionResponse, ObjectMapper objectMapper) {
        Map<String, Object> response = functionResponse.response().orElse(Map.of());
        if (response.isEmpty()) {
            return "";
        }

        Object error = response.get("error");
        if (error != null) {
            return stringifyValue(error, objectMapper);
        }

        Object output = response.containsKey("output") ? response.get("output") : response;
        return stringifyValue(output, objectMapper);
    }

    private String stringifyValue(Object value, ObjectMapper objectMapper) {
        if (value == null) {
            return "";
        }
        if (value instanceof String) {
            return (String) value;
        }

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private String extractStateDeltaContent(Object stateValue, ObjectMapper objectMapper) {
        if (stateValue instanceof Map<?, ?> stateMap) {
            Object output = stateMap.containsKey("output") ? stateMap.get("output") : stateValue;
            return stringifyValue(output, objectMapper);
        }

        String resultContent = stateValue != null ? stateValue.toString() : "";
        if (resultContent.startsWith("\"") && resultContent.endsWith("\"") && resultContent.length() > 1) {
            return resultContent.substring(1, resultContent.length() - 1);
        }
        return resultContent;
    }

    private boolean isStateDeltaError(Object stateValue, String resultContent) {
        if (stateValue instanceof Map<?, ?> stateMap) {
            if (Boolean.FALSE.equals(stateMap.get("success"))) {
                return true;
            }
            if (stateMap.get("error") != null) {
                return true;
            }
        }

        if (resultContent == null || resultContent.isBlank()) {
            return false;
        }

        String lowerContent = resultContent.toLowerCase();
        return lowerContent.contains("未绑定 ssh 终端会话")
                || lowerContent.contains("终端会话不存在")
                || lowerContent.contains("已关闭")
                || lowerContent.contains("命令执行异常")
                || lowerContent.contains("danger")
                || lowerContent.contains("error")
                || lowerContent.contains("failed");
    }

}
