package cn.bugstack.ai.domain.agent.service.audit;

import com.google.adk.agents.CallbackContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import io.reactivex.rxjava3.core.Single;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 请求级工具表，同时把服务端上下文交给 MyMessageConverter；不会发给模型。 */
public final class AuditedToolMap extends LinkedHashMap<String, BaseTool> {
    private final ToolAuditContext context;
    private final ToolExecutionAudit audit;

    private AuditedToolMap(Map<String, BaseTool> tools, ToolAuditContext context, ToolExecutionAudit audit) {
        this.context = context;
        this.audit = audit;
        tools.forEach((name, tool) -> put(name, new BoundTool(tool instanceof BoundTool bound ? bound.delegate : tool, context, audit)));
    }

    public ToolAuditContext context() { return context; }
    public ToolExecutionAudit audit() { return audit; }

    public static void bind(CallbackContext callback, LlmRequest.Builder request, ToolExecutionAudit audit) {
        request.tools(new AuditedToolMap(request.build().tools(), audit.context(callback), audit));
    }

    public static boolean isAudited(ToolContext context) {
        return context != null && context.functionCallId().orElse("").startsWith("exec-");
    }

    private static final class BoundTool extends BaseTool {
        private final BaseTool delegate;
        private final ToolAuditContext context;
        private final ToolExecutionAudit audit;
        private BoundTool(BaseTool delegate, ToolAuditContext context, ToolExecutionAudit audit) {
            super(delegate.name(), delegate.description(), delegate.longRunning());
            this.delegate = delegate; this.context = context; this.audit = audit;
            delegate.customMetadata().forEach(this::setCustomMetadata);
        }
        @Override public Optional<FunctionDeclaration> declaration() { return delegate.declaration(); }
        @Override public Single<Map<String, Object>> runAsync(Map<String, Object> arguments, ToolContext ignored) {
            return Single.defer(() -> {
                ToolExecutionRecord record = audit.started(context, name(), arguments);
                if ("BLOCKED".equals(record.status()) || "WAITING_APPROVAL".equals(record.status())) return Single.just(audit.blockedResult(record));
                ToolContext toolContext = ToolContext.builder(context.invocation())
                        .functionCallId(record.executionId()).build();
                var finished = new java.util.concurrent.atomic.AtomicBoolean();
                return Single.defer(() -> delegate.runAsync(arguments, toolContext))
                        .map(result -> audit.normalizeChildResult(record, result))
                        .doOnSuccess(result -> { if (finished.compareAndSet(false, true)) audit.returned(record, result); })
                        .doOnError(error -> { if (finished.compareAndSet(false, true)) audit.failed(record, error); })
                        .doOnDispose(() -> { if (finished.compareAndSet(false, true)) audit.cancelled(record); });
            });
        }
    }
}
