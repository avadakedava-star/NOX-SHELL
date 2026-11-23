package cn.bugstack.ai.domain.agent.service.execution;

import cn.bugstack.ai.domain.ssh.model.valobj.CommandExecutionResult;

/**
 * 工具重试判定：只有"确定没发出去"的只读命令才允许自动重试。
 * <p>命令一旦已下发（commandDispatched），重试可能造成重复副作用，即使结果看似可重试也不重放。
 */
public final class ToolRetryPolicy {
    private ToolRetryPolicy() { }
    /**
     * 同时满足以下条件才可重试：
     * 只读操作 / 状态为未发送 / 命令确实没有下发 / 结果标记可重试 / 未超过最大次数 / 线程未被中断。
     */
    public static boolean mayRetry(CommandExecutionResult result, boolean readOnly, int attempt, int maxRetries) {
        return readOnly && "NOT_SENT".equals(result.status()) && !result.commandDispatched()
                && result.retryable() && attempt <= maxRetries && !Thread.currentThread().isInterrupted();
    }
}
