package cn.bugstack.ai.domain.agent.service.execution;

public final class ExecutionHaltedException extends RuntimeException {
    private final String reason;
    public ExecutionHaltedException(String reason, String message) { super(message); this.reason = reason; }
    public String reason() { return reason; }
    public static ExecutionHaltedException find(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof ExecutionHaltedException stopped) return stopped;
            if (current == current.getCause()) break;
        }
        return null;
    }
}
