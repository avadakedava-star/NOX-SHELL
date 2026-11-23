package cn.bugstack.ai.domain.agent.service.execution;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 执行策略配置（对应 application.yml 的 nox-shell.execution.*）。
 *
 * <p>熔断与预算参数的唯一来源：真实执行入口、{@link RequestExecutionGuard}、重试策略读取同一份配置，
 * 保证同一次失败不会在不同层级被重复计数或放宽。
 *
 * <p>字段含义：
 * <ul>
 *   <li>sameCommandFailures —— 同一签名（命令/工具参数）累计失败多少次后，永久禁止原样再执行</li>
 *   <li>consecutiveFailures —— 连续失败多少次后置 reviewRequired，要求模型先复核进展</li>
 *   <li>requestTimeoutSeconds —— 单个请求的观察窗口，用于判定"多久没有新证据算停滞"</li>
 *   <li>commandTimeoutMs —— 单条远端命令的执行超时</li>
 *   <li>safeRetries —— 只读操作允许的最大自动重试次数（上限 2，避免放大副作用）</li>
 *   <li>maxPolicyRewrites —— 命令校验未通过时，允许模型改写后重试的次数</li>
 *   <li>maxRequestSeconds —— 整个请求（含所有子 Agent）的最终资源上限</li>
 *   <li>repeatedEvidenceLimit —— 连续多少次结果没有任何新信息后暂停后续操作</li>
 * </ul>
 */
@Component
public record ExecutionPolicyProperties(int sameCommandFailures, int consecutiveFailures,
        int requestTimeoutSeconds, int commandTimeoutMs, int safeRetries,
        int maxPolicyRewrites, int maxRequestSeconds, int repeatedEvidenceLimit) {
    /** 兼容旧调用方的 6 参数构造：观察窗口复用 requestTimeoutSeconds，资源上限与重复证据阈值取默认值。 */
    public ExecutionPolicyProperties(int same, int consecutive, int observation, int command, int retries, int rewrites) {
        this(same, consecutive, observation, command, retries, rewrites, 3600, 8);
    }
    /** Spring 注入入口：各参数均可通过 nox-shell.execution.* 覆盖，缺省值即生产默认策略。 */
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
        // 基础参数校验：重试次数上限为 2，防止配置错误把只读重试放大成写操作风险。
        if (sameCommandFailures < 1 || consecutiveFailures < 1 || requestTimeoutSeconds < 1 || commandTimeoutMs < 1 || safeRetries < 0 || safeRetries > 2 || maxPolicyRewrites < 0)
            throw new IllegalArgumentException("执行策略配置无效");
        // 资源上限不能小于单次观察窗口；重复证据阈值至少为 2，否则一次重复就会触发停滞。
        if (maxRequestSeconds < requestTimeoutSeconds || repeatedEvidenceLimit < 2) throw new IllegalArgumentException("进展策略配置无效");
    }
    /** 代码内构造默认策略（测试与无 Spring 环境使用），与 yml 缺省值保持一致。 */
    public static ExecutionPolicyProperties defaults() { return new ExecutionPolicyProperties(3, 5, 180, 30000, 2, 2); }
}
