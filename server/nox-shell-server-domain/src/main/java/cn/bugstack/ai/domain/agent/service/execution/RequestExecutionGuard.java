package cn.bugstack.ai.domain.agent.service.execution;

import java.util.*;

/**
 * 请求级执行守卫，原请求及其所有子 Agent 共用同一个实例（通过 toolContext 传递）。
 *
 * <p>职责：把"检查额度"和"占用额度"做成原子操作，并维护失败计数、永久禁止签名、
 * 执行树父子关系与会话结果；所有方法 synchronized，保证并发子 Agent 不会重复越界。
 * <p>原则：对已经发出的操作绝不自动重放，只允许在确认未发送时修正重试。
 */
public final class RequestExecutionGuard {
    /** 放在 ToolCallingChatOptions.toolContext 中的键，供 GuardedToolCallingManager 取回守卫实例。 */
    public static final String CONTEXT_KEY = "nox-shell.execution.guard";
    private final ExecutionPolicyProperties policy;
    private final AgentPermissionMode permissionMode;
    /** 整个请求的最终截止时间（毫秒），由 maxRequestSeconds 计算。 */
    private final long deadline;
    /** 停滞观察窗口：超过该时长没有新证据即视为没有进展。 */
    private final long observationMillis;
    private final java.util.function.LongSupplier clock;
    private final ExecutionProgress progress;
    /** 连续失败达到阈值后置 true，提示模型必须先做进展复核（reportProgress）。 */
    private boolean reviewRequired;
    private int requests, calls, consecutive, policyRejections;
    private String stopReason, stopMessage;
    /** 签名 → 失败次数 / 被策略拒绝次数。 */
    private final Map<String, Integer> failures = new HashMap<>(), policyBlocks = new HashMap<>();
    /** 永久禁止签名（权限、命令不存在等确定性失败）与正在执行中的签名（防并发重复）。 */
    private final Set<String> permanentBlocks = new HashSet<>(), inFlight = new HashSet<>();
    /** 执行树：父执行编号 → 子编号列表，以及子 → 父反查。 */
    private final Map<String, List<String>> children = new HashMap<>();
    private final Map<String, String> parents = new HashMap<>();
    /** 各执行编号最终状态，以及按终端会话汇总的状态。 */
    private final Map<String, String> outcomes = new HashMap<>();
    private final Map<String, String> executionSessions = new HashMap<>(), sessionOutcomes = new HashMap<>();

    public RequestExecutionGuard(ExecutionPolicyProperties policy, long observationAt) { this(policy,observationAt,System::currentTimeMillis); }
    public RequestExecutionGuard(ExecutionPolicyProperties policy,long observationAt,java.util.function.LongSupplier clock) {
        this(policy, observationAt, clock, AgentPermissionMode.APPROVAL);
    }

    public RequestExecutionGuard(ExecutionPolicyProperties policy, long observationAt, java.util.function.LongSupplier clock,
                                 AgentPermissionMode permissionMode) {
        this.permissionMode = Objects.requireNonNull(permissionMode);
        // observationAt 是外部（请求入口）开始时刻，据此换算观察窗口，避免守卫创建晚导致窗口被拉长。
        this.policy=policy;this.clock=clock;this.observationMillis=Math.max(1,observationAt-clock.getAsLong());
        this.deadline=clock.getAsLong()+policy.maxRequestSeconds()*1000L;this.progress=new ExecutionProgress(clock);
    }
    public boolean requiresApproval() { return permissionMode == AgentPermissionMode.APPROVAL; }
    public ExecutionProgress progress(){return progress;}
    /** 对外状态：需要复核或已超出一个观察窗口没有新证据时为 CHECKING，否则 RUNNING。 */
    public synchronized String progressState(){return reviewRequired || clock.getAsLong()-progress.lastEvidenceAt()>=observationMillis ? "CHECKING" : "RUNNING";}
    public synchronized void observe(String id,String tool,String status,String fingerprint) {
        // 派发类父执行不重复记账，避免同一事实在父子两层各算一次。
        if(children.containsKey(id))return;
        // 一旦出现真正的新证据，说明有进展，解除复核标记。
        if(progress.observe(id,tool,status,fingerprint))reviewRequired=false;
        // 连续重复结果达到上限：判定停滞并暂停后续操作（不代表远端任务失败）。
        if(progress.unchanged()>=policy.repeatedEvidenceLimit())halt("PROGRESS_STALLED","重复操作未取得新信息，已暂停后续操作；请核查当前状态或调整方法");
    }
    public ExecutionPolicyProperties policy() { return policy; }
    public long deadline() { return deadline; }
    public synchronized long remainingMillis() { return Math.max(1, deadline - clock.getAsLong()); }
    /** 每个工具执行前后都会调用：先记录终止原因（若有），再统一抛异常，避免同一次请求出现多种停止原因。 */
    public synchronized void checkActive() {
        // 1. 总资源预算耗尽。
        if (stopReason == null && clock.getAsLong() >= deadline) halt("RESOURCE_BUDGET", "达到最终执行资源上限，已保存执行记录；远端后台任务不会因此被取消");
        // 2. 连续两个观察窗口没有新证据（且没有在途操作）→ 要求人工/模型复核。
        if (stopReason == null && !hasInFlight() && clock.getAsLong()-progress.lastEvidenceAt()>=observationMillis*2)
            halt("PROGRESS_REVIEW_REQUIRED","连续两个观察窗口没有取得新证据，已暂停后续操作；这不代表远端任务失败");
        if (stopReason != null) throw new ExecutionHaltedException(stopReason, stopMessage);
    }
    /** 次数只用于审计统计；失败、并发重复和请求时限仍在真实执行入口检查。 */
    public synchronized String reserve(String signature) {
        checkActive();
        requests++;
        // 永久禁止（权限/命令不存在）或同签名失败次数超限：拒绝原样再执行。
        if (permanentBlocks.contains(signature) || failures.getOrDefault(signature, 0) >= policy.sameCommandFailures())
            return "该目标上的相同命令/工具参数已失败，禁止本次请求继续原样执行";
        // 同签名在途：拒绝并发重复发送，防止两个子 Agent 同时操作同一目标。
        if (inFlight.contains(signature)) return "相同操作正在执行，禁止并发重复发送";
        calls++; inFlight.add(signature); return null;
    }
    /** 登记执行节点，构建父子关系；父节点据此识别"派发工具"，从而跳过非叶子记账。 */
    public synchronized void register(String executionId, String parentExecutionId, String sessionId) {
        executionSessions.put(executionId, sessionId);
        if (parentExecutionId != null) {
            parents.put(executionId, parentExecutionId);
            children.computeIfAbsent(parentExecutionId, ignored -> new ArrayList<>()).add(executionId);
        }
    }
    /** 确认命令未真正下发时回滚计数，交由上层决定是否改写后重试。 */
    public synchronized void releaseBeforeExecution(String signature) { if (inFlight.remove(signature)) calls--; }
    /** 只允许修正未发送的语法/未知查询，不计入远程执行失败，也不污染父任务结果。 */
    public synchronized boolean allowPolicyRewrite() {
        // 改写次数是请求级共享预算，超过即终止，防止模型无限改写试探安全规则。
        if (++policyRejections <= policy.maxPolicyRewrites()) return true;
        halt("POLICY_REWRITE_LIMIT", "命令校验仍未通过，已用完 " + policy.maxPolicyRewrites() + " 次改写机会，请调整请求");
        return false;
    }
    public synchronized int policyRewritesRemaining() { return Math.max(0, policy.maxPolicyRewrites() - policyRejections); }
    public synchronized void finished(String signature, String executionId, String parentId, String status, String failureType) {
        inFlight.remove(signature);
        // 派发工具包含子工具，失败预算只按叶子操作计算，避免同一失败被父子重复累计。
        boolean leaf = !children.containsKey(executionId);
        if (leaf) {
            // 成功：清空连续失败与同签名失败计数。
            if ("RETURNED".equals(status)) { if (stopReason == null) consecutive = 0; failures.remove(signature); }
            // 失败：BLOCKED/待审批/命令忙等不算真实失败；其余累加连续失败与签名失败。
            else if (!"BLOCKED".equals(status) && !"WAITING_APPROVAL".equals(status)
                    && !Set.of("COMMAND_BUSY", "COMMAND_QUEUE_TIMEOUT").contains(failureType == null ? "" : failureType)) {
                consecutive++; failures.merge(signature, 1, Integer::sum);
                // 确定性错误（权限不足、命令不存在、参数非法、无终端）直接永久禁止该签名，重试无意义。
                if (Set.of("PERMISSION_DENIED", "COMMAND_NOT_FOUND", "INVALID_ARGUMENT", "NO_TERMINAL").contains(failureType == null ? "" : failureType)) permanentBlocks.add(signature);
                // 结果未知时无法确认远端状态，立即停止自动执行，避免叠加副作用。
                if ("UNKNOWN".equals(status)) halt("RESULT_UNKNOWN", "操作结果未知，停止自动执行，请核查服务器状态后再继续");
                else if (consecutive >= policy.consecutiveFailures()) reviewRequired=true;
            }
        }
        outcomes.put(executionId, status);
        // 会话维度只在失败时汇总；UNKNOWN 具有最高优先级，不会被后续普通失败覆盖。
        if (!"RETURNED".equals(status)) sessionOutcomes.merge(executionSessions.getOrDefault(executionId, ""), status,
                (oldValue, newValue) -> "UNKNOWN".equals(oldValue) ? oldValue : newValue);
        // 子节点失败向上冒泡，使父任务最终状态能反映子任务结果。
        if (parentId != null && !"RETURNED".equals(status)) propagate(parentId, status);
    }
    private void propagate(String parent, String status) {
        outcomes.merge(parent, status, (oldValue, newValue) -> "UNKNOWN".equals(oldValue) ? oldValue : newValue);
        if (parents.containsKey(parent)) propagate(parents.get(parent), status);
    }
    public synchronized String descendantOutcome(String executionId) { return outcomes.get(executionId); }
    public synchronized String sessionOutcome(String sessionId) { return sessionOutcomes.get(sessionId); }
    public synchronized void blocked(String executionId, String parentId, String sessionId) {
        blocked(executionId, parentId, sessionId, false);
    }
    /** 记录被拦截的执行；recoverable=true 表示模型可改写后重试，不向上冒泡污染父任务。 */
    public synchronized void blocked(String executionId, String parentId, String sessionId, boolean recoverable) {
        if (recoverable) {
            register(executionId, parentId, sessionId);
            outcomes.put(executionId, "BLOCKED");
            return;
        }
        outcomes.put(executionId, "BLOCKED"); sessionOutcomes.merge(sessionId, "BLOCKED", (oldValue, newValue) -> "UNKNOWN".equals(oldValue) ? oldValue : newValue);
        if (parentId != null) {
            parents.put(executionId, parentId);
            children.computeIfAbsent(parentId, ignored -> new ArrayList<>()).add(executionId);
            propagate(parentId, "BLOCKED");
        }
    }
    /** 记录终止原因；只在第一次调用时生效，保证 stopReason 反映最先生效的终止条件。 */
    public synchronized void halt(String reason, String message) { if (stopReason == null) { stopReason = reason; stopMessage = message; } }
    /** 统计同一签名被安全策略拒绝的次数，达到阈值后整体终止，避免反复试探。 */
    public synchronized void rejectPolicy(String signature, String reason) {
        if(policyBlocks.merge(signature,1,Integer::sum)>=policy.sameCommandFailures())
            halt("POLICY_BLOCKED", "相同禁止操作已被拒绝 "+policy.sameCommandFailures()+" 次："+reason);
    }
    public synchronized String stopReason() { return stopReason; }
    public synchronized boolean hasInFlight() { return !inFlight.isEmpty(); }
    /** 面向用户/审计的简短汇总。 */
    public synchronized String summary() {
        return "工具请求 " + requests + " 次，实际调用 " + calls + " 次，连续失败 " + consecutive + " 次。" + (stopMessage == null ? "" : stopMessage);
    }
}
