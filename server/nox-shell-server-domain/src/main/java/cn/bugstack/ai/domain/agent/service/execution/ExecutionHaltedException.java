package cn.bugstack.ai.domain.agent.service.execution;

/**
 * 执行被守卫主动终止时抛出的异常，reason 用于区分终止类型（预算超限 / 停滞 / 重复失败等）。
 * <p>ADK 会把异常包在多层 CompletionException 里，因此需要 {@link #find(Throwable)} 沿 cause 链回溯。
 */
public final class ExecutionHaltedException extends RuntimeException {
    private final String reason;
    public ExecutionHaltedException(String reason, String message) { super(message); this.reason = reason; }
    public String reason() { return reason; }
    /** 深度查找 cause 链上的终止异常；找不到返回 null（说明是其他类型失败）。 */
    public static ExecutionHaltedException find(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof ExecutionHaltedException stopped) return stopped;
            // 防御自引用 cause，避免异常链成环导致死循环。
            if (current == current.getCause()) break;
        }
        return null;
    }
}
