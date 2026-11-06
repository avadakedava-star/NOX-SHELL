package cn.bugstack.ai.domain.agent.service.audit;

/** 一次真实回调执行。executionId 与模型的 toolCallId 分开，不从输出内容推断身份。 */
public record ToolExecutionRecord(
        String executionId, String requestId, String userId, String sessionId,
        String invocationId, String sourceAgent, String terminalSessionId,
        String parentExecutionId, String toolName, String status,
        String arguments, String result, String error, long startedAt, Long finishedAt) {
    public ToolExecutionRecord finished(String status, String result, String error) {
        return new ToolExecutionRecord(executionId, requestId, userId, sessionId, invocationId,
                sourceAgent, terminalSessionId, parentExecutionId, toolName, status,
                arguments, result, error, startedAt, System.currentTimeMillis());
    }
}
