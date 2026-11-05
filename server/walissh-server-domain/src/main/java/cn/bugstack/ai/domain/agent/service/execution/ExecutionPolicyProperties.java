package cn.bugstack.ai.domain.agent.service.execution;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record ExecutionPolicyProperties(int sameCommandFailures, int consecutiveFailures,
        int requestTimeoutSeconds, int commandTimeoutMs, int safeRetries,
        int maxPolicyRewrites) {
    @org.springframework.beans.factory.annotation.Autowired
    public ExecutionPolicyProperties(
            @Value("${walissh.execution.same-command-failures:3}") int sameCommandFailures,
            @Value("${walissh.execution.consecutive-failures:5}") int consecutiveFailures,
            @Value("${walissh.execution.request-timeout-seconds:180}") int requestTimeoutSeconds,
            @Value("${walissh.execution.command-timeout-ms:30000}") int commandTimeoutMs,
            @Value("${walissh.execution.safe-retries:2}") int safeRetries,
            @Value("${walissh.execution.max-policy-rewrites:2}") int maxPolicyRewrites) {
        this.sameCommandFailures = sameCommandFailures; this.consecutiveFailures = consecutiveFailures;
        this.requestTimeoutSeconds = requestTimeoutSeconds; this.commandTimeoutMs = commandTimeoutMs; this.safeRetries = safeRetries;
        this.maxPolicyRewrites = maxPolicyRewrites;
        if (sameCommandFailures < 1 || consecutiveFailures < 1 || requestTimeoutSeconds < 1 || commandTimeoutMs < 1 || safeRetries < 0 || safeRetries > 2 || maxPolicyRewrites < 0)
            throw new IllegalArgumentException("执行策略配置无效");
    }
    public static ExecutionPolicyProperties defaults() { return new ExecutionPolicyProperties(3, 5, 180, 30000, 2, 2); }
}
