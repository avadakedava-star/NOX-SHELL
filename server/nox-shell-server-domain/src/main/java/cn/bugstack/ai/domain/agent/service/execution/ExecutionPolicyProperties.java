package cn.bugstack.ai.domain.agent.service.execution;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record ExecutionPolicyProperties(int sameCommandFailures, int consecutiveFailures,
        int requestTimeoutSeconds, int commandTimeoutMs, int safeRetries,
        int maxPolicyRewrites, int maxRequestSeconds, int repeatedEvidenceLimit) {
    public ExecutionPolicyProperties(int same, int consecutive, int observation, int command, int retries, int rewrites) {
        this(same, consecutive, observation, command, retries, rewrites, 3600, 8);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public ExecutionPolicyProperties(
            @Value("${nox-shell.execution.same-command-failures:3}") int sameCommandFailures,
            @Value("${nox-shell.execution.consecutive-failures:5}") int consecutiveFailures,
            @Value("${nox-shell.execution.request-timeout-seconds:180}") int requestTimeoutSeconds,
            @Value("${nox-shell.execution.command-timeout-ms:30000}") int commandTimeoutMs,
            @Value("${nox-shell.execution.safe-retries:2}") int safeRetries,
            @Value("${nox-shell.execution.max-policy-rewrites:2}") int maxPolicyRewrites,
            @Value("${nox-shell.execution.max-request-seconds:3600}") int maxRequestSeconds,
            @Value("${nox-shell.execution.repeated-evidence-limit:8}") int repeatedEvidenceLimit) {
        this.sameCommandFailures = sameCommandFailures; this.consecutiveFailures = consecutiveFailures;
        this.requestTimeoutSeconds = requestTimeoutSeconds; this.commandTimeoutMs = commandTimeoutMs; this.safeRetries = safeRetries;
        this.maxPolicyRewrites = maxPolicyRewrites;
        this.maxRequestSeconds = maxRequestSeconds; this.repeatedEvidenceLimit = repeatedEvidenceLimit;
        if (sameCommandFailures < 1 || consecutiveFailures < 1 || requestTimeoutSeconds < 1 || commandTimeoutMs < 1 || safeRetries < 0 || safeRetries > 2 || maxPolicyRewrites < 0)
            throw new IllegalArgumentException("执行策略配置无效");
        if (maxRequestSeconds < requestTimeoutSeconds || repeatedEvidenceLimit < 2) throw new IllegalArgumentException("进展策略配置无效");
    }
    public static ExecutionPolicyProperties defaults() { return new ExecutionPolicyProperties(3, 5, 180, 30000, 2, 2); }
}
