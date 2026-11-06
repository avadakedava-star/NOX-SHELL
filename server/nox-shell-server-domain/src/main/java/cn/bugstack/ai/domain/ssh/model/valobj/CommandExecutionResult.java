package cn.bugstack.ai.domain.ssh.model.valobj;

import java.util.LinkedHashMap;
import java.util.Map;

/** UNKNOWN 表示无法确认远程结果；不能据此重发命令。 */
public record CommandExecutionResult(String status, Integer exitCode, String stdout, String stderr,
        String failureType, boolean commandDispatched, boolean retryable) {
    public static CommandExecutionResult notSent(String reason, boolean retryable) {
        return new CommandExecutionResult("NOT_SENT", null, "", "", reason, false, retryable);
    }
    public static CommandExecutionResult unknown(String stdout, String stderr, String reason) {
        return new CommandExecutionResult("UNKNOWN", null, stdout, stderr, reason, true, false);
    }
    public static CommandExecutionResult completed(int code, String stdout, String stderr) {
        String failure = code == 0 ? null : code == 127 ? "COMMAND_NOT_FOUND" : code == 126 ? "PERMISSION_DENIED"
                : stderr.toLowerCase(java.util.Locale.ROOT).contains("permission denied") ? "PERMISSION_DENIED" : "COMMAND_FAILED";
        return new CommandExecutionResult(code == 0 ? "SUCCEEDED" : "FAILED", code, stdout, stderr, failure, true, false);
    }
    public Map<String, Object> toToolResult(String command) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("command", command); result.put("success", "SUCCEEDED".equals(status));
        result.put("executionStatus", status); result.put("exitCode", exitCode);
        result.put("stdout", stdout); result.put("stderr", stderr);
        result.put("output", stdout + (stderr.isBlank() ? "" : "\n" + stderr));
        result.put("failureType", failureType); result.put("commandDispatched", commandDispatched);
        result.put("retryable", retryable);
        return result;
    }
}
