package cn.bugstack.ai.domain.agent.service.armory.matter.tools;

import io.reactivex.rxjava3.core.Flowable;

import cn.bugstack.ai.domain.agent.model.valobj.dynamic.AgentExecutionContext;
import cn.bugstack.ai.domain.agent.model.valobj.dynamic.DynamicTask;
import cn.bugstack.ai.domain.agent.model.valobj.dynamic.TaskStatus;
import cn.bugstack.ai.domain.agent.service.event.AgentEventPublisher;
import cn.bugstack.ai.domain.agent.service.armory.catalog.AgentCatalog;
import cn.bugstack.ai.domain.agent.service.armory.matter.session.factory.CustomRunnerFactory;
import com.google.adk.agents.RunConfig;
import com.google.adk.agents.BaseAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 子 Agent 派发服务 - 动态编排体系中实际执行单个任务的 Spring 服务。
 * <p>
 * 与 {@link SubAgentDispatchTool}（包装固定子 Agent 给 LLM 调用）不同，
 * 本服务面向运行期规划出的任务列表：从 AgentCatalog 查找任务指定的子 Agent，
 * 透传父会话绑定的 SSH 终端会话（ThreadLocal），带超时执行并回写任务状态。
 * 编排器（DynamicAgentOrchestrator）通过本服务完成每个 DynamicTask 的执行。
 */
@Slf4j
@Service
public class SubAgentDispatchService {

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private cn.bugstack.ai.domain.agent.service.audit.ToolExecutionAudit toolExecutionAudit;

    private final AgentCatalog agentCatalog;
    private final CustomRunnerFactory runnerFactory;
    private final Executor executor;
    /**
     * 事件发布器负责把独立 Runner 的事件转发到父请求的 SSE 流。
     */
    private final AgentEventPublisher agentEventPublisher;

    /**
     * 兼容旧调用方式；使用独立发布器兜底，但不会连接任何 SSE 监听器。
     */
    public SubAgentDispatchService(AgentCatalog agentCatalog,
                                   CustomRunnerFactory runnerFactory,
                                   Executor executor) {
        this(agentCatalog, runnerFactory, executor, new AgentEventPublisher());
    }

    /**
     * Spring 注入入口；显式限定线程池，避免与框架默认异步执行器混淆。
     */
    @Autowired
    public SubAgentDispatchService(AgentCatalog agentCatalog,
                                   CustomRunnerFactory runnerFactory,
                                   @Qualifier("threadPoolExecutor") Executor executor,
                                   AgentEventPublisher agentEventPublisher) {
        this.agentCatalog = agentCatalog;
        this.runnerFactory = runnerFactory;
        this.executor = executor;
        this.agentEventPublisher = agentEventPublisher;
    }

    /**
     * 并发派发一批任务（无依赖编排的简单批量场景）。
     * 每个任务提交到线程池执行，全部完成后汇总：tasks（含状态/结果）与 allSucceeded。
     */
    public Map<String, Object> dispatch(AgentExecutionContext context, List<DynamicTask> tasks) {
        Map<String, Object> response = new LinkedHashMap<>();
        Semaphore concurrency = new Semaphore(Math.max(1, tasks.size()));

        List<CompletableFuture<Void>> futures = tasks.stream()
                .map(task -> CompletableFuture.runAsync(() -> {
                    boolean acquired = false;
                    try {
                        concurrency.acquire();
                        acquired = true;
                        execute(context, task);
                    } catch (Exception exception) {
                        task.setStatus(TaskStatus.FAILED);
                        task.setError(exception.getMessage());
                    } finally {
                        if (acquired) concurrency.release();
                    }
                }, executor))
                .toList();

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        response.put("tasks", tasks);
        response.put("allSucceeded", tasks.stream().allMatch(task -> task.getStatus() == TaskStatus.COMPLETED));
        return response;
    }

    /**
     * 执行单个任务并原地更新任务状态（含失败重试）：
     * <ol>
     *   <li>置为 RUNNING，从 AgentCatalog 查找子 Agent（先按父 Agent 精确匹配，再全局兜底），找不到直接置 FAILED（确定性错误，不重试）</li>
     *   <li>以独立会话执行一次子 Agent，共享根请求的证据观察策略，不按固定 120 秒取消</li>
     *   <li>结合真实子工具结果判定 COMPLETED / FAILED / UNKNOWN / STOPPED</li>
     *   <li>回写 attempts（实际尝试次数），finally 中清理 ThreadLocal，避免线程池复用导致的会话串扰</li>
     * </ol>
     */
    public void execute(AgentExecutionContext context, DynamicTask task) {
        var guard = toolExecutionAudit == null ? null : toolExecutionAudit.guardForState(context.getAuditState());
        if (guard != null) {
            try { guard.checkActive(); }
            catch (cn.bugstack.ai.domain.agent.service.execution.ExecutionHaltedException stopped) {
                task.setStatus(TaskStatus.STOPPED); task.setError(stopped.getMessage()); return;
            }
        }
        task.setStatus(TaskStatus.RUNNING);
        String invocationId = UUID.randomUUID().toString();
        BaseAgent agent = agentCatalog.find(context.getAgentId(), task.getAgentName())
                .or(() -> agentCatalog.findByName(task.getAgentName()))
                .orElse(null);

        if (agent == null) {
            task.setStatus(TaskStatus.FAILED);
            task.setError("agent not found: " + task.getAgentName());
            return;
        }

        // 整个 Agent 工作流只运行一次，安全重试由 SSH 层处理。
        try {
            if (context.getTerminalSessionId() != null) {
                SshExecuteAdkTool.setCurrentTerminalSession(context.getTerminalSessionId());
            }
            SshExecuteAdkTool.setCurrentSourceAgent(agent.name());
            int attempt = 1;
            task.setAttempts(attempt);
                try {
                    Runner runner = runnerFactory.create(agent, agent.name(), List.of());
                    Content content = Content.fromParts(Part.fromText(task.getRequest()));
                    RunConfig runConfig = cn.bugstack.ai.domain.agent.service.execution.ExecutionRunConfig.builder().autoCreateSession(true).build();
                    // 子 Runner 的 terminalSessionId 通过初始状态显式透传，避免线程切换后丢失 SSH 上下文。
                    Map<String, Object> childState = new HashMap<>();
                    if (context.getAuditState() != null) childState.putAll(context.getAuditState());
                    if (context.getTerminalSessionId() != null) {
                        childState.put("terminalSessionId", context.getTerminalSessionId());
                    }

                    List<Event> events = new ArrayList<>();
                    var executionGuard=guard!=null?guard:new cn.bugstack.ai.domain.agent.service.execution.RequestExecutionGuard(
                            cn.bugstack.ai.domain.agent.service.execution.ExecutionPolicyProperties.defaults(),
                            System.currentTimeMillis()+Math.max(1,Optional.ofNullable(task.getTimeoutSeconds()).orElse(120))*1000L);
                    runner.runAsync(context.getUserId(), "subagent-" + invocationId + "-" + attempt,
                                    content, runConfig, childState)
                            .takeUntil(Flowable.interval(1,TimeUnit.SECONDS).map(tick->{executionGuard.checkActive();return tick;}).filter(tick->false))
                            .blockingForEach(event -> {
                                // 边执行边发布，前端可以实时看到子 Agent 的工具调用，而不是等待任务汇总后一次性返回（否则ui渲染效果就不咋地了，一坨一坨的）。
                                events.add(event);
                                if (context.getParentSessionId() != null
                                        && !context.getParentSessionId().isBlank()
                                        && !"unknown".equals(context.getParentSessionId())) {
                                    agentEventPublisher.publish(
                                            context.getParentSessionId(), context.getParentSessionKey(), event, true);
                                } else {
                                    agentEventPublisher.publishToOnlyActiveSession(event, true);
                                }
                            });

                    task.setResult(toResult(events));
                    if (guard != null) {
                        String outcome = guard.sessionOutcome("subagent-" + invocationId + "-" + attempt);
                        if (outcome != null && java.util.Set.of("UNKNOWN", "BLOCKED").contains(outcome)) {
                            task.setStatus("UNKNOWN".equals(outcome) ? TaskStatus.UNKNOWN : TaskStatus.FAILED);
                            task.setError("子工具执行存在失败、拦截或未知结果，任务不能标记成功"); return;
                        }
                        guard.checkActive();
                    }
                    task.setStatus(TaskStatus.COMPLETED);
                    return;
                } catch (Exception e) {
                    var stopped = cn.bugstack.ai.domain.agent.service.execution.ExecutionHaltedException.find(e);
                    if (stopped != null || guard != null && guard.stopReason() != null) {
                        task.setStatus(TaskStatus.STOPPED); task.setError(guard == null ? e.getMessage() : guard.summary()); return;
                    }
                    boolean timeout = false;
                    for (Throwable cause = e; cause != null && cause != cause.getCause(); cause = cause.getCause()) {
                        if (cause instanceof java.util.concurrent.TimeoutException) { timeout = true; break; }
                    }
                    if (timeout) {
                        task.setStatus(TaskStatus.UNKNOWN); task.setError("子任务超时，结果未知，禁止自动重放");
                        if (guard != null) guard.halt("RESULT_UNKNOWN", task.getError()); return;
                    }
                    task.setStatus(TaskStatus.FAILED);
                    task.setError("attempts=1 | " + e.getMessage());
                    log.warn("子Agent失败，停止本任务且不重放 | agent={} | task={}", task.getAgentName(), task.getTaskId(), e);
                }
        } finally {
            SshExecuteAdkTool.clearCurrentTerminalSession();
            SshExecuteAdkTool.clearCurrentSourceAgent();
        }

    }

    /**
     * 拼接事件中全部文本片段作为任务执行结果（取空时返回空串）
     */
    private String toResult(List<Event> events) {
        return events.isEmpty() ? "" : events.get(events.size() - 1).content()
                .flatMap(Content::parts)
                .flatMap(parts -> parts.stream()
                        .map(part -> part.text().orElse(""))
                        .reduce((left, right) -> left + right))
                .orElse("");
    }

}
