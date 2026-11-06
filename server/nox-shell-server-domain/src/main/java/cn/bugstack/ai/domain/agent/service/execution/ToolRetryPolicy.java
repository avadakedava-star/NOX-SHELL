package cn.bugstack.ai.domain.agent.service.execution;

import cn.bugstack.ai.domain.ssh.model.valobj.CommandExecutionResult;

public final class ToolRetryPolicy {
    private ToolRetryPolicy() { }
    public static boolean mayRetry(CommandExecutionResult result, boolean readOnly, int attempt, int maxRetries) {
        return readOnly && "NOT_SENT".equals(result.status()) && !result.commandDispatched()
                && result.retryable() && attempt <= maxRetries && !Thread.currentThread().isInterrupted();
    }
}
