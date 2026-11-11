package cn.bugstack.ai.domain.agent.service.audit;

import cn.bugstack.ai.domain.agent.adapter.repository.IToolExecutionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.agents.CallbackContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import cn.bugstack.ai.domain.agent.service.execution.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Service
@Slf4j
public class ToolExecutionAudit {
    private final IToolExecutionRepository repository;
    private final ExecutionPolicyProperties policy;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private cn.bugstack.ai.domain.ssh.service.ISshTerminalService terminalService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private cn.bugstack.ai.domain.agent.service.approval.CommandApprovalService commandApprovals;
    private final Map<String, RequestExecutionGuard> guards = new ConcurrentHashMap<>();
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();
    private record Attempt(ToolAuditContext context, String signature) { }
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<SessionKey, Subscription> subscriptions = new ConcurrentHashMap<>();
    private record SessionKey(String userId, String sessionId) { }
    public record Notification(ToolExecutionRecord record, boolean persisted) { }

    public ToolExecutionAudit(IToolExecutionRepository repository) { this(repository, ExecutionPolicyProperties.defaults()); }
    @org.springframework.beans.factory.annotation.Autowired
    public ToolExecutionAudit(IToolExecutionRepository repository, ExecutionPolicyProperties policy) { this.repository = repository; this.policy = policy; }

    /** 同一会话一次只运行一个请求，避免消息历史及运行状态相互覆盖。不同会话仍可并发。 */
    public Subscription subscribe(String userId, String sessionId, Consumer<Notification> listener) {
        guards.entrySet().removeIf(entry -> entry.getValue().deadline() + 60000 < System.currentTimeMillis());
        if (guards.size() >= 10000) throw new ExecutionHaltedException("SERVER_BUSY", "执行请求容量已满，请稍后重试");
        SessionKey key = new SessionKey(userId, sessionId);
        Subscription subscription = new Subscription(key, UUID.randomUUID().toString(), listener);
        if (subscriptions.putIfAbsent(key, subscription) != null) {
            throw new IllegalStateException("该会话已有运行中的请求，请等待完成或新建会话");
        }
        guards.put(subscription.requestId, new RequestExecutionGuard(policy, System.currentTimeMillis() + policy.requestTimeoutSeconds() * 1000L));
        return subscription;
    }

    public final class Subscription implements AutoCloseable {
        private final SessionKey key;
        private final String requestId;
        private final Consumer<Notification> listener;
        private final java.util.List<Consumer<Notification>> observers = new java.util.concurrent.CopyOnWriteArrayList<>();

        /** 在同一个请求预算内观察工具事件，不新建请求或重置失败预算。 */
        public AutoCloseable observe(Consumer<Notification> observer) {
            observers.add(observer);
            return () -> observers.remove(observer);
        }
        private final Map<String, ToolExecutionRecord> records = new ConcurrentHashMap<>();
        private Subscription(SessionKey key, String requestId, Consumer<Notification> listener) {
            this.key = key; this.requestId = requestId; this.listener = listener;
        }
        public String requestId() { return requestId; }
        public List<ToolExecutionRecord> records() { return records.values().stream().sorted(Comparator.comparingLong(ToolExecutionRecord::startedAt)).toList(); }
        public RequestExecutionGuard guard() { return guards.get(requestId); }
        @Override public void close() {
            subscriptions.remove(key, this);
            RequestExecutionGuard guard = guards.get(requestId);
            if (guard != null) guard.halt("REQUEST_FINISHED", "请求已经结束，禁止后台继续执行新操作");
        }
    }

    public ToolAuditContext context(CallbackContext callback) {
        Map<String, Object> state = callback.state();
        Subscription active = subscriptions.get(new SessionKey(callback.userId(), callback.sessionId()));
        // 根请求使用本轮入口的绑定；子 Runner 保留父调用显式传入的终端，避免串到其他请求。
        if (active != null || !(state.get("terminalSessionId") instanceof String stateTerminal) || stateTerminal.isBlank()) {
            cn.bugstack.ai.domain.agent.service.armory.matter.tools.SshSessionContextRegistry.get(callback.sessionId())
                    .ifPresent(terminal -> state.put("terminalSessionId", terminal));
        }
        boolean child = active == null && state.get(ToolAuditContext.PARENT_EXECUTION) instanceof String;
        // 根请求以当前订阅为准，不能沿用上一次请求留在 Session 中的 requestId。
        String requestId = active != null ? active.requestId : child ? string(state.get(ToolAuditContext.ROOT_REQUEST), callback.invocationId()) : callback.invocationId();
        String userId = child ? string(state.get(ToolAuditContext.ROOT_USER), callback.userId()) : callback.userId();
        String sessionId = child ? string(state.get(ToolAuditContext.ROOT_SESSION), callback.sessionId()) : callback.sessionId();
        boolean sameRequest = requestId.equals(state.get(ToolAuditContext.ROOT_REQUEST));
        long deadline = active != null ? active.guard().deadline() : sameRequest && state.get(ToolAuditContext.DEADLINE) instanceof Number number
                ? number.longValue() : System.currentTimeMillis() + policy.requestTimeoutSeconds() * 1000L;
        state.put(ToolAuditContext.DEADLINE, deadline);
        guards.entrySet().removeIf(entry -> entry.getValue().deadline() + 60000 < System.currentTimeMillis());
        if (guards.size() >= 10000 && !guards.containsKey(requestId)) throw new ExecutionHaltedException("SERVER_BUSY", "执行请求容量已满，请稍后重试");
        RequestExecutionGuard guard = guards.computeIfAbsent(requestId, ignored -> new RequestExecutionGuard(policy, deadline));
        guard.checkActive();
        state.put(ToolAuditContext.ROOT_REQUEST, requestId);
        state.put(ToolAuditContext.ROOT_USER, userId);
        state.put(ToolAuditContext.ROOT_SESSION, sessionId);
        return new ToolAuditContext(requestId, userId, sessionId, callback.invocationId(), callback.agentName(),
                string(state.get("terminalSessionId"), null), child ? string(state.get(ToolAuditContext.PARENT_EXECUTION), null) : null,
                callback.invocationContext(), guard);
    }

    public ToolExecutionRecord started(ToolAuditContext context, String toolName, Object arguments) {
        String signature = signature(context, toolName, arguments);
        String denial = context.guard().reserve(signature);
        cn.bugstack.ai.domain.ssh.service.policy.CommandPolicyDecision commandDecision = null;
        JsonNode argumentNode = json(arguments);
        if (denial == null && toolName.equals("executeCommand") && argumentNode.path("command").isTextual()) {
            commandDecision = new cn.bugstack.ai.domain.ssh.service.policy.CommandPolicyService().check(argumentNode.path("command").asText());
            if (!commandDecision.allowed() && !commandDecision.decision().equals("REQUIRE_APPROVAL")) {
                context.guard().releaseBeforeExecution(signature);
                denial = commandDecision.reason();
            }
        }
        ToolExecutionRecord record = new ToolExecutionRecord("exec-" + UUID.randomUUID(), context.requestId(),
                context.userId(), context.sessionId(), context.invocationId(), context.sourceAgent(),
                context.terminalSessionId(), context.parentExecutionId(), toolName, "RUNNING",
                sanitize(arguments), null, null, System.currentTimeMillis(), null);
        // 开始记录失败时不执行工具，避免产生完全没有记录的副作用。
        if (denial != null) {
            boolean policyBlocked = commandDecision != null && !commandDecision.allowed();
            boolean recoverable = policyBlocked && commandDecision.recoverable() && context.guard().allowPolicyRewrite();
            Map<String, Object> result = new LinkedHashMap<>(Map.of("success", false, "executionStatus", "BLOCKED", "failureType", recoverable ? "POLICY_REWRITE_REQUIRED" : policyBlocked ? "POLICY_BLOCKED" : "REPEATED_OPERATION",
                    "ruleId", policyBlocked ? commandDecision.ruleId() : "R001", "commandDispatched", false, "retryable", false, "output", denial,
                    "policyDecision", policyBlocked ? commandDecision.decision() : "DENY"));
            result.put("rewriteAllowed", recoverable);
            result.put("rewritesRemaining", context.guard().policyRewritesRemaining());
            if (recoverable) result.put("suggestion", "请根据校验原因改写为只读查询后再调用；本次未执行，不计远程执行失败。禁止原样重试或绕过危险操作限制");
            record = record.finished("BLOCKED", sanitize(result), null);
            repository.save(record);
            context.guard().blocked(record.executionId(), record.parentExecutionId(), context.invocation() == null ? context.sessionId() : context.invocation().session().id(), recoverable);
            if(policyBlocked && !recoverable) context.guard().rejectPolicy(signature,denial);
            publish(record, true); return record;
        }
        try { repository.save(record); }
        catch (RuntimeException error) { context.guard().releaseBeforeExecution(signature); throw error; }
        attempts.put(record.executionId(), new Attempt(context, signature));
        context.guard().register(record.executionId(), context.parentExecutionId(), context.invocation() == null ? context.sessionId() : context.invocation().session().id());
        publish(record, true);
        return record;
    }

    public void returned(ToolExecutionRecord started, Object result) {
        JsonNode node;
        try { node = json(result); } catch (RuntimeException ignored) { node = mapper.nullNode(); }
        boolean failed = node.isObject() && (node.path("success").isBoolean() && !node.path("success").booleanValue()
                || node.path("isError").asBoolean(false) || node.path("error").isTextual() && !node.path("error").asText().isBlank());
        String status = "UNKNOWN".equals(node.path("executionStatus").asText()) ? "UNKNOWN"
                : "WAITING_APPROVAL".equals(node.path("executionStatus").asText()) ? "WAITING_APPROVAL"
                : "BLOCKED".equals(node.path("executionStatus").asText()) ? "BLOCKED" : failed ? "REPORTED_FAILURE" : "RETURNED";
        Attempt attempt = attempts.get(started.executionId());
        if (attempt != null) {
            String childStatus = attempt.context.guard().descendantOutcome(started.executionId());
            if("WAITING_APPROVAL".equals(childStatus) && !"UNKNOWN".equals(status)) {
                status="WAITING_APPROVAL";
                result=Map.of("success",false,"executionStatus","WAITING_APPROVAL","output","子任务的变更方案已保存，等待用户确认");
            }
            if (Set.of("UNKNOWN", "BLOCKED").contains(childStatus == null ? "" : childStatus) && "RETURNED".equals(status)) {
                status = "UNKNOWN".equals(childStatus) ? "UNKNOWN" : "REPORTED_FAILURE";
                result = Map.of("success", false, "failureType", "CHILD_TOOL_FAILED", "output", "子工具存在失败或被拦截的操作，不能把父任务标成成功", "originalResult", sanitize(result));
            }
            if ("BLOCKED".equals(status)) {
                attempt.context.guard().releaseBeforeExecution(attempt.signature);
                boolean recoverable = "REWRITE_REQUIRED".equals(node.path("policyDecision").asText()) && attempt.context.guard().allowPolicyRewrite();
                if (recoverable) {
                    attempts.remove(started.executionId());
                    attempt.context.guard().blocked(started.executionId(), started.parentExecutionId(),
                            attempt.context.invocation() == null ? started.sessionId() : attempt.context.invocation().session().id(), true);
                }
            }
            if(!node.has("commandDispatched") || node.path("commandDispatched").asBoolean()) {
                JsonNode stable=node.deepCopy();stripVolatile(stable);
                attempt.context.guard().observe(started.executionId(),started.toolName(),status,started.toolName()+"|"+started.terminalSessionId()+"|"+stable);
            }
            updateGuard(started, status, node.path("failureType").asText());
        }
        finish(started.finished(status, sanitize(result), null));
    }

    public Map<String, Object> normalizeChildResult(ToolExecutionRecord record, Map<String, Object> original) {
        Attempt attempt = attempts.get(record.executionId());
        if (attempt == null) return original;
        String outcome = attempt.context.guard().descendantOutcome(record.executionId());
        if (outcome == null || "RETURNED".equals(outcome)) return original;
        if (Set.of("REPORTED_FAILURE", "FAILED").contains(outcome)) {
            var result=new LinkedHashMap<String,Object>(original);
            result.put("toolWarning","子工具曾返回失败，请根据最终结果说明是否已恢复，不能把未完成检查说成正常");
            return result;
        }
        if("WAITING_APPROVAL".equals(outcome)) return Map.of("success",false,"executionStatus","WAITING_APPROVAL","output","子任务变更等待用户确认");
        return Map.of("success", false, "executionStatus", "UNKNOWN".equals(outcome) ? "UNKNOWN" : "FAILED",
                "failureType", "CHILD_TOOL_FAILED", "output", "子工具存在失败或被拦截的操作，请检查执行记录", "originalResult", original);
    }

    public Map<String,Object> withProgress(ToolExecutionRecord record,Map<String,Object> original) {
        var guard=guards.get(record.requestId());if(guard==null)return original;
        var result=new LinkedHashMap<String,Object>(original);
        result.put("executionEvidenceId",record.executionId());
        result.put("progressState",guard.progressState());
        result.put("progressNote","工具返回不等于目标完成；每完成一个阶段、改变方法或连续失败时，用 reportProgress 引用执行证据并说明下一项验证");
        return result;
    }
    private void stripVolatile(JsonNode node) {
        if(node.isObject()) {
            var object=(ObjectNode)node;
            for(String key:List.of("timestamp","updatedAt","createdAt","duration","durationMs","timeoutMs","attempts","attemptCount","command","executionEvidenceId","progressState","progressNote"))object.remove(key);
            object.elements().forEachRemaining(this::stripVolatile);
        }else if(node.isArray())node.elements().forEachRemaining(this::stripVolatile);
    }

    public void failed(ToolExecutionRecord started, Throwable error) {
        // 已进入未知工具的实现后抛异常，不能证明远程没有副作用，因此不允许盲目重试。
        var stopped=ExecutionHaltedException.find(error);
        String status = stopped!=null && stopped.reason().equals("WAITING_APPROVAL") ? "WAITING_APPROVAL" : stopped == null ? "UNKNOWN" : "FAILED";
        updateGuard(started, status, "TOOL_EXCEPTION");
        finish(started.finished(status, null, sanitize(error.getClass().getSimpleName() + ": " + error.getMessage())));
    }

    public void cancelled(ToolExecutionRecord started) {
        var attempt=attempts.get(started.executionId());
        String status=attempt!=null && "WAITING_APPROVAL".equals(attempt.context.guard().stopReason()) ? "WAITING_APPROVAL":"UNKNOWN";
        updateGuard(started, status, "CANCELLED");
        finish(started.finished(status, null, status.equals("WAITING_APPROVAL") ? "等待用户确认" : "执行订阅已取消，远程执行结果仍需核查"));
    }

    private void finish(ToolExecutionRecord record) {
        boolean persisted = true;
        try { repository.save(record); }
        catch (RuntimeException failure) {
            persisted = false;
            // 工具已执行，不能因落库失败向上抛异常诱发再次执行；数据库保留 RUNNING 供核查。
            log.error("工具已返回但审计写入失败 executionId={}", record.executionId(), failure);
        }
        publish(record, persisted);
    }

    private void updateGuard(ToolExecutionRecord record, String status, String failureType) {
        Attempt attempt = attempts.remove(record.executionId());
        if (attempt != null) attempt.context.guard().finished(attempt.signature, record.executionId(), record.parentExecutionId(), status, failureType);
    }

    public Map<String, Object> blockedResult(ToolExecutionRecord record) {
        try { return mapper.readValue(record.result(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { }); }
        catch (Exception error) { throw new IllegalStateException("拦截结果解析失败", error); }
    }

    public RequestExecutionGuard guard(com.google.adk.tools.ToolContext context) {
        if (context == null) return null;
        Object requestId = context.state().get(ToolAuditContext.ROOT_REQUEST);
        if (requestId == null) return null;
        RequestExecutionGuard guard = guards.get(requestId);
        if (requestId != null && guard == null) throw new ExecutionHaltedException("REQUEST_EXPIRED", "原执行请求已过期，禁止继续执行");
        return guard;
    }

    public RequestExecutionGuard guardForState(Map<String, Object> state) {
        if (state == null || !state.containsKey(ToolAuditContext.ROOT_REQUEST)) return null;
        RequestExecutionGuard guard = guards.get(state.get(ToolAuditContext.ROOT_REQUEST));
        if (guard == null) throw new ExecutionHaltedException("REQUEST_EXPIRED", "原请求已过期，禁止派发新任务");
        return guard;
    }

    private String signature(ToolAuditContext context, String name, Object arguments) {
        String target = context.terminalSessionId();
        if (terminalService != null && target != null) {
            var terminal = terminalService.getTerminalSession(target);
            if (terminal != null) target = terminal.getConnectionId();
        }
        Object normalized = arguments;
        JsonNode node = json(arguments);
        if (name.equals("executeCommand") && node.path("command").isTextual()) {
            try { normalized = new cn.bugstack.ai.domain.ssh.service.policy.ShellCommandAnalyzer().analyzeChain(node.path("command").asText()); }
            catch (IllegalArgumentException ignored) { normalized = node.path("command").asText(); }
        } else normalized = canonical(node);
        try {
            byte[] data = (name + "|" + target + "|" + mapper.writeValueAsString(normalized)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception error) { throw new IllegalStateException("工具参数规范化失败", error); }
    }

    private Object canonical(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> sorted = new TreeMap<>(); node.fields().forEachRemaining(entry -> sorted.put(entry.getKey(), canonical(entry.getValue()))); return sorted;
        }
        if (node.isArray()) { List<Object> list = new ArrayList<>(); node.forEach(value -> list.add(canonical(value))); return list; }
        return node;
    }

    private void publish(ToolExecutionRecord record, boolean persisted) {
        Subscription subscription = subscriptions.get(new SessionKey(record.userId(), record.sessionId()));
        if (subscription == null || !subscription.requestId.equals(record.requestId())) return;
        subscription.records.put(record.executionId(), record);
        for (Consumer<Notification> observer : subscription.observers) {
            try { observer.accept(new Notification(record, persisted)); }
            catch (RuntimeException failure) { log.warn("工具上下文更新失败 executionId={}", record.executionId(), failure); }
        }
        try { subscription.listener.accept(new Notification(record, persisted)); }
        catch (RuntimeException failure) { log.warn("工具事件通知失败 executionId={}", record.executionId(), failure); }
    }

    public List<ToolExecutionRecord> findBySession(String userId, String sessionId) {
        return repository.findBySession(userId, sessionId);
    }

    private String sanitize(Object value) {
        String text;
        try {
            JsonNode node = redact(json(value).deepCopy());
            text = node.isTextual() ? node.asText() : mapper.writeValueAsString(node);
        } catch (Exception failure) { text = "[无法序列化]"; }
        return text.length() > 16000 ? text.substring(0, 16000) + "[已截断]" : text;
    }

    public String safeText(String text) { return redactText(text); }

    private String redactText(String text) {
        // 覆盖 Shell 参数、URL 参数及错误文本中常见的凭据形式。
        return text.replaceAll("(?i)((?:password|passwd|api[_-]?key|access[_-]?token|secret|authorization)\\s*[=:]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)", "$1[REDACTED]")
                .replaceAll("(?i)((?:--(?:password|passwd|api[_-]?key|token|secret))\\s+)(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)", "$1[REDACTED]")
                .replaceAll("(?i)(Bearer\\s+)[A-Za-z0-9._~+/-]+=*", "$1[REDACTED]");
    }

    private JsonNode json(Object value) {
        if (value instanceof String string) {
            try {
                JsonNode node = mapper.readTree(string);
                return node == null ? mapper.valueToTree(string) : node;
            } catch (Exception ignored) { return mapper.valueToTree(string); }
        }
        return mapper.valueToTree(value);
    }

    private JsonNode redact(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>(); object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                if (name.matches("(?i).*(password|passwd|secret|token|api.?key|authorization|private.?key).*")) object.put(name, "[REDACTED]");
                else object.set(name, redact(object.get(name)));
            }
        } else if (node instanceof com.fasterxml.jackson.databind.node.ArrayNode array) {
            for (int index = 0; index < array.size(); index++) array.set(index, redact(array.get(index)));
        } else if (node != null && node.isTextual()) return mapper.valueToTree(redactText(node.asText()));
        return node;
    }

    private static String string(Object value, String fallback) {
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }
}
