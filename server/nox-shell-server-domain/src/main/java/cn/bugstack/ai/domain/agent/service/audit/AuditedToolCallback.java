package cn.bugstack.ai.domain.agent.service.audit;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/** MCP/Skills 的真实执行入口。两个 call 重载都保留原始语义。 */
public final class AuditedToolCallback implements ToolCallback {
    private final ToolCallback delegate;
    private final ToolAuditContext context;
    private final ToolExecutionAudit audit;
    public AuditedToolCallback(ToolCallback delegate, ToolAuditContext context, ToolExecutionAudit audit) {
        this.delegate = delegate; this.context = context; this.audit = audit;
    }
    @Override public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }
    @Override public ToolMetadata getToolMetadata() { return delegate.getToolMetadata(); }
    @Override public String call(String arguments) { return execute(arguments, () -> delegate.call(arguments)); }
    @Override public String call(String arguments, ToolContext toolContext) {
        return execute(arguments, () -> delegate.call(arguments, toolContext));
    }
    private String execute(String arguments, java.util.function.Supplier<String> action) {
        ToolExecutionRecord record = audit.started(context, getToolDefinition().name(), arguments);
        if ("BLOCKED".equals(record.status()) || "WAITING_APPROVAL".equals(record.status())) return record.result();
        try {
            String result = action.get();
            audit.returned(record, result);
            return result;
        } catch (RuntimeException | Error error) {
            audit.failed(record, error);
            throw error;
        }
    }
}
