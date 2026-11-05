package cn.bugstack.ai.domain.agent.service.execution;

import java.util.*;

/** 原请求及所有子 Agent 共用。检查和占用额度原子化；不自动重放已经发出的操作。 */
public final class RequestExecutionGuard {
    public static final String CONTEXT_KEY = "walissh.execution.guard";
    private final ExecutionPolicyProperties policy;
    private final long deadline;
    private int requests, calls, consecutive, policyRejections;
    private String stopReason, stopMessage;
    private final Map<String, Integer> failures = new HashMap<>(), policyBlocks = new HashMap<>();
    private final Set<String> permanentBlocks = new HashSet<>(), inFlight = new HashSet<>();
    private final Map<String, List<String>> children = new HashMap<>();
    private final Map<String, String> parents = new HashMap<>();
    private final Map<String, String> outcomes = new HashMap<>();
    private final Map<String, String> executionSessions = new HashMap<>(), sessionOutcomes = new HashMap<>();

    public RequestExecutionGuard(ExecutionPolicyProperties policy, long deadline) { this.policy = policy; this.deadline = deadline; }
    public ExecutionPolicyProperties policy() { return policy; }
    public long deadline() { return deadline; }
    public synchronized long remainingMillis() { return Math.max(1, deadline - System.currentTimeMillis()); }
    public synchronized void checkActive() {
        if (stopReason == null && System.currentTimeMillis() >= deadline) halt("REQUEST_TIMEOUT", "请求超过 " + policy.requestTimeoutSeconds() + " 秒，停止后续操作；已发出的命令需核查结果");
        if (stopReason != null) throw new ExecutionHaltedException(stopReason, stopMessage);
    }
    /** 次数只用于审计统计；失败、并发重复和请求时限仍在真实执行入口检查。 */
    public synchronized String reserve(String signature) {
        checkActive();
        requests++;
        if (permanentBlocks.contains(signature) || failures.getOrDefault(signature, 0) >= policy.sameCommandFailures())
            return "该目标上的相同命令/工具参数已失败，禁止本次请求继续原样执行";
        if (inFlight.contains(signature)) return "相同操作正在执行，禁止并发重复发送";
        calls++; inFlight.add(signature); return null;
    }
    public synchronized void register(String executionId, String parentExecutionId, String sessionId) {
        executionSessions.put(executionId, sessionId);
        if (parentExecutionId != null) {
            parents.put(executionId, parentExecutionId);
            children.computeIfAbsent(parentExecutionId, ignored -> new ArrayList<>()).add(executionId);
        }
    }
    public synchronized void releaseBeforeExecution(String signature) { if (inFlight.remove(signature)) calls--; }
    /** 只允许修正未发送的语法/未知查询，不计入远程执行失败，也不污染父任务结果。 */
    public synchronized boolean allowPolicyRewrite() {
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
            if ("RETURNED".equals(status)) { if (stopReason == null) consecutive = 0; failures.remove(signature); }
            else if (!"BLOCKED".equals(status) && !"WAITING_APPROVAL".equals(status)
                    && !Set.of("COMMAND_BUSY", "COMMAND_QUEUE_TIMEOUT").contains(failureType == null ? "" : failureType)) {
                consecutive++; failures.merge(signature, 1, Integer::sum);
                if (Set.of("PERMISSION_DENIED", "COMMAND_NOT_FOUND", "INVALID_ARGUMENT", "NO_TERMINAL").contains(failureType == null ? "" : failureType)) permanentBlocks.add(signature);
                if ("UNKNOWN".equals(status)) halt("RESULT_UNKNOWN", "操作结果未知，停止自动执行，请核查服务器状态后再继续");
                else if (consecutive >= policy.consecutiveFailures()) halt("CONSECUTIVE_FAILURE_LIMIT", "连续工具失败达到 " + policy.consecutiveFailures() + " 次，停止自动操作");
            }
        }
        outcomes.put(executionId, status);
        if (!"RETURNED".equals(status)) sessionOutcomes.merge(executionSessions.getOrDefault(executionId, ""), status,
                (oldValue, newValue) -> "UNKNOWN".equals(oldValue) ? oldValue : newValue);
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
    public synchronized void halt(String reason, String message) { if (stopReason == null) { stopReason = reason; stopMessage = message; } }
    public synchronized void rejectPolicy(String signature, String reason) {
        if(policyBlocks.merge(signature,1,Integer::sum)>=policy.sameCommandFailures())
            halt("POLICY_BLOCKED", "相同禁止操作已被拒绝 "+policy.sameCommandFailures()+" 次："+reason);
    }
    public synchronized String stopReason() { return stopReason; }
    public synchronized boolean hasInFlight() { return !inFlight.isEmpty(); }
    public synchronized String summary() {
        return "工具请求 " + requests + " 次，实际调用 " + calls + " 次，连续失败 " + consecutive + " 次。" + (stopMessage == null ? "" : stopMessage);
    }
}
