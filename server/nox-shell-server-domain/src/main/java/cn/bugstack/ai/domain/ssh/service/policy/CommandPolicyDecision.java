package cn.bugstack.ai.domain.ssh.service.policy;

import java.util.Map;

public record CommandPolicyDecision(String decision, String ruleId, String reason,
                                    boolean readOnly, String executionCommand) {
    public boolean allowed() { return "ALLOW".equals(decision); }
    public boolean recoverable() { return "REWRITE_REQUIRED".equals(decision); }
    public Map<String, Object> blockedToolResult(String command) {
        return Map.of("success", false, "executionStatus", "BLOCKED", "command", command == null ? "" : command,
                "commandDispatched", false, "retryable", false, "ruleId", ruleId,
                "policyDecision", decision, "failureType", recoverable() ? "POLICY_REWRITE_REQUIRED" : "POLICY_BLOCKED",
                "rewriteAllowed", recoverable(), "output", reason);
    }
    public static CommandPolicyDecision rewrite(String rule, String reason) {
        return new CommandPolicyDecision("REWRITE_REQUIRED", rule,
                reason + "；命令未发送。请将查询和变更拆开，禁止改写绕过审批或拦截规则", false, null);
    }
    public static CommandPolicyDecision deny(String rule, String reason) {
        return new CommandPolicyDecision("DENY", rule, reason, false, null);
    }
}
