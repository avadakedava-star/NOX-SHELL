package cn.bugstack.ai.domain.agent.service.armory.matter.tools;

import cn.bugstack.ai.domain.agent.service.armory.matter.session.factory.CustomRunnerFactory;
import cn.bugstack.ai.domain.agent.service.event.AgentEventPublisher;
import com.google.adk.agents.RunConfig;
import com.google.adk.agents.BaseAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.reactivex.rxjava3.core.Single;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 子 Agent 派发工具（单个） - 把一个子 Agent 包装成 ADK FunctionTool 供父 Agent 调用。
 * <p>
 * 配置了 subAgents 的父 Agent 装配时，每个子 Agent 都会被包装为本工具
 * （工具名/描述即子 Agent 的 name/description，LLM 通过函数调用触发派发）。
 * 派发过程：为子 Agent 创建独立 Runner 与会话，同步收集全部事件，
 * 取最后一个事件的文本作为执行结果返回给父 Agent。
 * <p>
 * 注：本工具在装配阶段创建（非 Spring 管理），需传入 runnerFactory 手工构造 Runner。
 */
@Slf4j
public class SubAgentDispatchTool extends BaseTool {

    /** 被包装、派发的子 Agent */
    private final BaseAgent subAgent;
    /** Runner 工厂，为子 Agent 构建独立执行器 */
    private final CustomRunnerFactory runnerFactory;
    /**
     * 事件发布器为可选依赖，兼容旧的直接构造方式和不需要 UI 透传的测试场景。
     */
    private final AgentEventPublisher agentEventPublisher;

    /**
     * 兼容旧调用方式；不注入事件发布器时派发仍可正常执行，只是不产生嵌套事件。
     */
    public SubAgentDispatchTool(BaseAgent subAgent, CustomRunnerFactory runnerFactory) {
        this(subAgent, runnerFactory, null);
    }

    /**
     * 创建单个子 Agent 派发工具。
     *
     * @param subAgent            被包装的子 Agent
     * @param runnerFactory       子 Agent Runner 工厂
     * @param agentEventPublisher 可选事件发布器；为空时不回流子 Runner 事件
     */
    public SubAgentDispatchTool(BaseAgent subAgent,
                                CustomRunnerFactory runnerFactory,
                                AgentEventPublisher agentEventPublisher) {
        super(subAgent.name(), subAgent.description());
        this.subAgent = subAgent;
        this.runnerFactory = runnerFactory;
        this.agentEventPublisher = agentEventPublisher;
    }

    /**
     * 声明工具的函数签名：入参仅一个必填字符串 request（下发给子 Agent 的任务指令）。
     */
    @Override
    public Optional<FunctionDeclaration> declaration() {
        return Optional.of(FunctionDeclaration.builder()
                .name(name())
                .description(description())
                .parameters(Schema.builder()
                        .type("OBJECT")
                        .properties(ImmutableMap.of(
                                "request", Schema.builder().type("STRING").build()))
                        .required(ImmutableList.of("request"))
                        .build())
                .build());
    }

    /**
     * 执行派发：以独立会话运行子 Agent，阻塞收集全部事件后汇总结果。
     * 执行异常时返回 {success:false, error:...}，由父 Agent 感知并决定下一步。
     */
    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
        String request = String.valueOf(args.getOrDefault("request", ""));
        String invocationId = UUID.randomUUID().toString();

        log.info("子Agent上下文诊断 | toolContext={} | invocationId={} | sessionId={} | stateTerminalSessionId={}",
                toolContext != null ? "present" : "null",
                invocationId,
                toolContext != null ? toolContext.sessionId() : "null",
                toolContext != null ? toolContext.state().get("terminalSessionId") : "null");

        log.info("子Agent派发开始 | subAgent:{} | request:{} | invocationId:{}",
                subAgent.name(), request, invocationId);

        Runner runner = runnerFactory.create(subAgent, subAgent.name(), List.of());
        Content content = Content.fromParts(Part.fromText(request));

        RunConfig runConfig = cn.bugstack.ai.domain.agent.service.execution.ExecutionRunConfig.builder()
                .autoCreateSession(true)
                .build();

        String parentInvocationId = toolContext != null ? toolContext.invocationId() : null;
        String parentSessionId = toolContext != null ? toolContext.sessionId() : null;
        String functionCallId = toolContext != null
                ? toolContext.functionCallId().orElse("call_" + invocationId)
                : "call_" + invocationId;
        // 父 Runner 的 function call 可能未进入同一事件流，先补发调用事件，保证 UI 展示完整。
        publishSyntheticFunctionCall(parentInvocationId, parentSessionId, functionCallId, request);

        // 子 Agent 使用独立 Runner，必须显式复制终端会话状态，否则异步线程无法找到 SSH 终端。
        Map<String, Object> childState = new HashMap<>(cn.bugstack.ai.domain.agent.service.audit.ToolAuditContext.childState(toolContext));
        String terminalSessionId = null;
        if (toolContext != null) {
            Object stateTerminalSessionId = toolContext.state().get("terminalSessionId");
            if (stateTerminalSessionId instanceof String value && !value.isBlank()) {
                terminalSessionId = value;
            } else {
                terminalSessionId = SshSessionContextRegistry.get(toolContext.sessionId()).orElse(null);
            }
        }
        if (terminalSessionId == null) {
            terminalSessionId = SshSessionContextRegistry.only().orElse(null);
        }
        if (terminalSessionId == null) {
            terminalSessionId = SshSessionContextRegistry.active().orElse(null);
        }
        if (terminalSessionId != null) {
            childState.put("terminalSessionId", terminalSessionId);
            SshExecuteAdkTool.setCurrentTerminalSession(terminalSessionId);
        }
        SshExecuteAdkTool.setCurrentSourceAgent(subAgent.name());

        return runner.runAsync(toolContext != null ? toolContext.userId() : "subagent-user", "subagent-" + invocationId, content, runConfig, childState)
                .doOnNext(event -> {
                    if (agentEventPublisher != null) {
                        publishEvent(parentInvocationId, parentSessionId, event);
                    }
                })
                .doFinally(SshExecuteAdkTool::clearCurrentSourceAgent)
                .toList()
                .map(events -> {
                    Map<String, Object> result = toResult(events, invocationId);
                    publishSyntheticFunctionResponse(parentInvocationId, parentSessionId, functionCallId, result);
                    return result;
                })
                .onErrorReturn(error -> {
                    log.error("子Agent派发异常 | subAgent:{} | invocationId:{}",
                            subAgent.name(), invocationId, error);
                    Map<String, Object> result = ImmutableMap.of(
                            "success", false,
                            "error", String.valueOf(error.getMessage()));
                    publishSyntheticFunctionResponse(parentInvocationId, parentSessionId, functionCallId, result);
                    return result;
                });
    }

    /**
     * 构造并发布子 Agent 的合成 function call 事件，补齐父 Runner 可能缺失的工具调用事件。
     */
    private void publishSyntheticFunctionCall(
            String parentInvocationId, String parentSessionId, String functionCallId, String request) {
        if (agentEventPublisher == null || functionCallId.startsWith("exec-")) {
            return;
        }
        Event event = Event.builder()
                .id(Event.generateEventId())
                .invocationId(parentInvocationId)
                .author(subAgent.name())
                .content(Content.fromParts(Part.fromFunctionCall(
                        subAgent.name(), Map.of("request", request))))
                .build();
        publishEvent(parentInvocationId, parentSessionId, event);
    }

    /**
     * 构造并发布子 Agent 的合成 function response 事件；成功和失败都会通知前端。
     */
    private void publishSyntheticFunctionResponse(
            String parentInvocationId,
            String parentSessionId,
            String functionCallId,
            Map<String, Object> result) {
        if (agentEventPublisher == null || functionCallId.startsWith("exec-")) {
            return;
        }
        Event event = Event.builder()
                .id(Event.generateEventId())
                .invocationId(parentInvocationId)
                .author(subAgent.name())
                .content(Content.fromParts(Part.fromFunctionResponse(
                        subAgent.name(), result)))
                .build();
        publishEvent(parentInvocationId, parentSessionId, event);
    }

    /**
     * 按父 invocation 优先、业务会话次之的方式路由事件；两者均不可用时保守兜底。
     */
    private void publishEvent(String parentInvocationId, String parentSessionId, Event event) {
        // 合成事件使用父 invocation/session 路由；无法获取父上下文时只投递到唯一活跃 SSE 会话。
        if (parentInvocationId != null && !parentInvocationId.isBlank()) {
            agentEventPublisher.publish(parentInvocationId, parentSessionId, event, true);
        } else {
            agentEventPublisher.publishToOnlyActiveSession(event, true);
        }
    }

    /** 取最后一个事件的文本内容作为子 Agent 的最终回复 */
    private Map<String, Object> toResult(List<Event> events, String invocationId) {
        String result = events.isEmpty() ? "" : events.get(events.size() - 1)
                .content()
                .map(Content::text)
                .orElse("");

        log.info("子Agent派发完成 | subAgent:{} | resultLength:{} | invocationId:{}",
                subAgent.name(), result.length(), invocationId);

        return ImmutableMap.of(
                "success", true,
                "result", result);
    }

}
