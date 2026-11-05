package cn.bugstack.ai.domain.agent.service.armory.matter.tools;

import cn.bugstack.ai.domain.agent.service.event.AgentEventPublisher;
import cn.bugstack.ai.domain.ssh.service.ISshTerminalService;
import com.google.adk.events.Event;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * SSH 命令执行 ADK 工具
 * 为智能体提供在 SSH 终端执行命令的能力
 * 
 * 使用 ADK 的 @Schema 注解定义参数，支持 FunctionTool.create()
 *
 * @author xiaofuge bugstack.cn @小傅哥
 */
@Slf4j
@Service
public class SshExecuteAdkTool {

    @Resource
    private ISshTerminalService sshTerminalService;

    /**
     * 可选事件发布器；为空或工具在测试中直接构造时，SSH 命令仍可正常执行，只是不回流可视化事件。
     */
    @Resource
    private AgentEventPublisher agentEventPublisher;

    /**
     * 当前线程正在执行的 SSH 终端会话。
     * ADK 工具可能在异步线程中执行，因此每次派发前设置、完成后清理。
     */
    private static final ThreadLocal<String> currentTerminalSession = new ThreadLocal<>();

    /**
     * 当前线程对应的 Agent 名称，用于给 SSH 工具事件标注来源 Agent。
     */
    private static final ThreadLocal<String> currentSourceAgent = new ThreadLocal<>();

    @Resource
    private cn.bugstack.ai.domain.ssh.service.policy.CommandPolicyService commandPolicyService;
    @Resource
    private cn.bugstack.ai.domain.agent.service.execution.ExecutionPolicyProperties executionPolicy;
    @Resource
    private cn.bugstack.ai.domain.agent.service.audit.ToolExecutionAudit toolExecutionAudit;

    /**
     * 设置当前线程的终端会话 ID（兼容旧接口）
     */
    public static void setCurrentTerminalSession(String terminalSessionId) {
        currentTerminalSession.set(terminalSessionId);
        log.info("[ThreadLocal] 设置终端会话: thread={}, terminalSession={}",
                Thread.currentThread().getName(), terminalSessionId);
    }

    /**
     * 清除当前线程的终端会话 ID
     */
    public static void clearCurrentTerminalSession() {
        currentTerminalSession.remove();
    }

    /**
     * 设置当前线程的来源 Agent，SSH 工具发布事件时用它标识事件属于哪个子 Agent。
     */
    public static void setCurrentSourceAgent(String sourceAgent) {
        currentSourceAgent.set(sourceAgent);
    }

    /**
     * 清除当前线程的来源 Agent，防止线程池复用时把上一个子 Agent 的身份带入下一次调用。
     */
    public static void clearCurrentSourceAgent() {
        currentSourceAgent.remove();
    }

    /**
     * 在 SSH 终端执行命令。
     * <p>
     * 终端会话按“ThreadLocal → ToolContext 初始状态”的顺序解析；
     * toolContext 由 ADK 注入，用于子 Runner 或线程切换后恢复 SSH 上下文。
     * <p>
     * 案例 1：成功执行
     * <pre>
     *   currentTerminalSession = "ssh-session-001"
     *   command = "df -h"
     *
     *   返回：
     *   {
     *     "command": "df -h",
     *     "output": "Filesystem      Size  Used Avail Use% Mounted on\n/dev/sda1        50G   20G   30G  40% /",
     *     "success": true
     *   }
     * </pre>
     * <p>
     * 案例 2：命令不存在
     * <pre>
     *   command = "nonexistent-command"
     *
     *   返回：
     *   {
     *     "command": "nonexistent-command",
     *     "output": "command not found: nonexistent-command",
     *     "success": false,
     *     "suggestion": "命令不存在。可能原因：命令拼写错误..."
     *   }
     * </pre>
     * <p>
     * 案例 3：危险命令被拦截
     * <pre>
     *   command = "rm -rf /"
     *
     *   返回：
     *   {
     *     "command": "rm -rf /",
     *     "output": "⚠️ 危险命令被拦截: rm -rf /\n该命令可能导致系统损坏或数据丢失。",
     *     "success": false
     *   }
     * </pre>
     */
    public Map<String, Object> executeCommand(
            @Schema(name = "command", description = "除删除、清理数据、磁盘破坏等黑名单操作外，所有命令直接执行，无需审批。支持 sudo、安装、重启、脚本、管道、重定向、多行命令。需要管理员权限时使用 sudo -n。退出码由工具返回。单条命令默认总时限30秒，包含sleep和排队；不要sleep 30后再检查。等待启动应分成短时间检查，首次超时先核查，不重建已创建资源。")
            String command,
            @Schema(name = "toolContext", optional = true)
            ToolContext toolContext) {
        
        var policy = executionPolicy == null
                ? cn.bugstack.ai.domain.agent.service.execution.ExecutionPolicyProperties.defaults() : executionPolicy;
        var commandPolicy = commandPolicyService == null
                ? new cn.bugstack.ai.domain.ssh.service.policy.CommandPolicyService() : commandPolicyService;
        var guard = toolExecutionAudit == null ? null : toolExecutionAudit.guard(toolContext);
        if (guard != null) guard.checkActive();
        var decision = commandPolicy.check(command);
        if (!decision.allowed()) {
            Map<String, Object> blocked = new java.util.LinkedHashMap<>();
            blocked.put("success", false); blocked.put("executionStatus", "BLOCKED");
            blocked.put("command", command); blocked.put("commandDispatched", false);
            blocked.put("retryable", false); blocked.put("ruleId", decision.ruleId());
            blocked.put("policyDecision", decision.decision()); blocked.put("failureType", decision.recoverable() ? "POLICY_REWRITE_REQUIRED" : "POLICY_BLOCKED");
            blocked.put("rewriteAllowed", decision.recoverable());
            blocked.put("output", decision.reason());
            return blocked;
        }
        String terminalSessionId = toolContext == null ? currentTerminalSession.get()
                : toolContext.state().get("terminalSessionId") instanceof String value ? value : null;
        if (terminalSessionId == null || terminalSessionId.isBlank()) {
            var result = cn.bugstack.ai.domain.ssh.model.valobj.CommandExecutionResult.notSent("NO_TERMINAL", false).toToolResult(command);
            result.put("output", "未绑定 SSH 终端会话，请先建立连接");
            return result;
        }
        String toolInvocationId = "tool-" + UUID.randomUUID();
        publishToolEvent(toolContext, terminalSessionId, toolInvocationId, "call", command);
        java.util.List<Map<String, Object>> attempts = new java.util.ArrayList<>();
        for (int attempt = 1; attempt <= policy.safeRetries() + 1; attempt++) {
            if (guard != null) guard.checkActive();
            long timeout = guard == null ? policy.commandTimeoutMs() : Math.min(policy.commandTimeoutMs(), guard.remainingMillis());
            cn.bugstack.ai.domain.ssh.model.valobj.CommandExecutionResult execution;
            try {
                execution = sshTerminalService.executeStructured(terminalSessionId, decision.executionCommand(), timeout);
                if (execution == null) execution = cn.bugstack.ai.domain.ssh.model.valobj.CommandExecutionResult.unknown("", "", "EXECUTION_RESULT_MISSING");
            } catch (cn.bugstack.ai.domain.agent.service.execution.ExecutionHaltedException stopped) { throw stopped; }
            catch (RuntimeException error) {
                execution = cn.bugstack.ai.domain.ssh.model.valobj.CommandExecutionResult.unknown("", "执行层异常，远程结果需核查", "EXECUTION_EXCEPTION");
            }
            attempts.add(Map.of("attempt", attempt, "status", execution.status(), "failureType", execution.failureType() == null ? "" : execution.failureType(),
                    "commandDispatched", execution.commandDispatched()));
            if (cn.bugstack.ai.domain.agent.service.execution.ToolRetryPolicy.mayRetry(execution, decision.readOnly(), attempt, policy.safeRetries())) {
                try { Thread.sleep(Math.min(200L << (attempt - 1), guard == null ? 1000 : guard.remainingMillis())); }
                catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    execution = cn.bugstack.ai.domain.ssh.model.valobj.CommandExecutionResult.notSent("CANCELLED", false);
                    Map<String, Object> result = execution.toToolResult(command); result.put("attempts", attempts); return result;
                }
                continue;
            }
            Map<String, Object> result = execution.toToolResult(command);
            result.put("timeoutMs", timeout); result.put("attempts", attempts); result.put("attemptCount", attempt); result.put("ruleId", decision.ruleId());
            if (Set.of("COMMAND_BUSY", "COMMAND_QUEUE_TIMEOUT").contains(execution.failureType() == null ? "" : execution.failureType())) {
                result.put("output", "同一终端已有命令执行，当前命令排队超时，尚未发送到服务器。请保留已取得的结果，不要改写同类命令反复尝试。");
                result.put("suggestion", "结束当前诊断并报告未完成检查项；这不代表服务器异常，也不消耗连续执行失败额度。");
            }
            if ("UNKNOWN".equals(execution.status())) result.put("suggestion", "命令可能已执行，请核查服务器状态；禁止直接重做");
            else if ("PERMISSION_DENIED".equals(execution.failureType())) result.put("suggestion", "权限不足，停止原样重试；请用户检查账号权限，不自动提权");
            else if ("COMMAND_NOT_FOUND".equals(execution.failureType())) result.put("suggestion", "命令不存在，请核对程序和路径，按任务需要安装缺失软件");
            publishToolEvent(toolContext, terminalSessionId, toolInvocationId, "response", result);
            return result;
        }
        throw new IllegalStateException("安全重试状态异常");
    }

    public static String currentValue() {
        return currentTerminalSession.get();
    }

    public Map<String,Object> updateConfigFile(
            @Schema(name="path",description="已存在的配置文件绝对路径") String path,
            @Schema(name="expectedContent",description="先用 cat 查询到的完整原内容，包括末尾换行；用于执行前防止覆盖他人修改") String expectedContent,
            @Schema(name="content",description="准备替换的新配置全文，最大64KiB；直接备份并原子替换，无需审批") String content,
            @Schema(name="toolContext",optional=true) ToolContext context) {
        String terminal = context == null ? currentTerminalSession.get()
                : context.state().get("terminalSessionId") instanceof String value ? value : null;
        if (terminal == null || terminal.isBlank())
            return cn.bugstack.ai.domain.ssh.model.valobj.CommandExecutionResult.notSent("NO_TERMINAL", false).toToolResult("修改配置 " + path);
        var guard = toolExecutionAudit == null ? null : toolExecutionAudit.guard(context);
        if (guard != null) guard.checkActive();
        String execution = cn.bugstack.ai.domain.agent.service.approval.ConfigChangePolicy.command(UUID.randomUUID().toString(), path, expectedContent, content);
        long timeout = executionPolicy == null ? 30000 : executionPolicy.commandTimeoutMs();
        if (guard != null) timeout = Math.min(timeout, guard.remainingMillis());
        return sshTerminalService.executeStructured(terminal, execution, timeout).toToolResult("修改配置 " + path);
    }

    /**
     * 发布 SSH 工具的合成调用/响应事件。
     * <p>
     * SSH 工具的执行结果不一定会作为父 Runner 的原始 functionResponse 返回，
     * 因此这里构造标准 ADK Event，并按终端会话投递给当前 SSE 监听器。
     */
    private void publishToolEvent(
            ToolContext toolContext,
            String terminalSessionId,
            String toolInvocationId,
            String phase,
            Object payload) {
        if (agentEventPublisher == null || cn.bugstack.ai.domain.agent.service.audit.AuditedToolMap.isAudited(toolContext)
                || terminalSessionId == null || terminalSessionId.isBlank()) {
            return;
        }
        String author = currentSourceAgent.get();
        Content content = "call".equals(phase)
                ? Content.fromParts(Part.fromFunctionCall(
                        "executeCommand", Map.of("command", payload)))
                : Content.fromParts(Part.fromFunctionResponse(
                        "executeCommand", (Map<String, Object>) payload));
        Event event = Event.builder()
                .id(Event.generateEventId())
                .invocationId(toolInvocationId)
                .author(author != null && !author.isBlank() ? author : "executeCommand")
                .content(content)
                .build();
        agentEventPublisher.publishToTerminal(terminalSessionId, event, true);
    }

}
