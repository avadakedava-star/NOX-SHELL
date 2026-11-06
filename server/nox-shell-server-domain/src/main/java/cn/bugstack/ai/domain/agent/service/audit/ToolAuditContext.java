package cn.bugstack.ai.domain.agent.service.audit;

import com.google.adk.agents.InvocationContext;
import com.google.adk.tools.ToolContext;
import java.util.HashMap;
import java.util.Map;

/** 随请求/工具闭包传递，不能存进共享模型实例或靠活跃终端猜归属。 */
public record ToolAuditContext(String requestId, String userId, String sessionId,
        String invocationId, String sourceAgent, String terminalSessionId,
        String parentExecutionId, InvocationContext invocation,
        cn.bugstack.ai.domain.agent.service.execution.RequestExecutionGuard guard) {
    public ToolAuditContext(String requestId, String userId, String sessionId, String invocationId,
            String sourceAgent, String terminalSessionId, String parentExecutionId, InvocationContext invocation) {
        this(requestId, userId, sessionId, invocationId, sourceAgent, terminalSessionId, parentExecutionId, invocation,
                new cn.bugstack.ai.domain.agent.service.execution.RequestExecutionGuard(
                        cn.bugstack.ai.domain.agent.service.execution.ExecutionPolicyProperties.defaults(), System.currentTimeMillis() + 180000));
    }
    public static final String ROOT_REQUEST = "toolAudit.requestId";
    public static final String ROOT_USER = "toolAudit.userId";
    public static final String ROOT_SESSION = "toolAudit.sessionId";
    public static final String PARENT_EXECUTION = "toolAudit.parentExecutionId";
    public static final String DEADLINE = "toolAudit.deadline";

    public static Map<String, Object> childState(ToolContext context) {
        Map<String, Object> state = new HashMap<>();
        if (context == null) return state;
        for (String key : new String[]{ROOT_REQUEST, ROOT_USER, ROOT_SESSION, DEADLINE}) {
            Object value = context.state().get(key);
            if (value != null) state.put(key, value);
        }
        context.functionCallId().ifPresent(id -> state.put(PARENT_EXECUTION, id));
        return state;
    }
}
