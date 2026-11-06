package cn.bugstack.ai.domain.ssh.service.policy;

public record CommandPolicyDecision(String decision, String ruleId, String reason,
                                    boolean readOnly, String executionCommand) {
    public boolean allowed() { return "ALLOW".equals(decision); }
    public boolean recoverable() { return "REWRITE_REQUIRED".equals(decision); }
    public static CommandPolicyDecision rewrite(String rule, String reason) {
        return new CommandPolicyDecision("REWRITE_REQUIRED", rule,
                reason + "；命令未发送。请改为明确的只读单命令，禁止改写绕过删除、提权或审批限制", false, null);
    }
    public static CommandPolicyDecision deny(String rule, String reason) {
        return new CommandPolicyDecision("DENY", rule, reason, false, null);
    }
}
