package cn.bugstack.ai.domain.agent.service.execution;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.*;
import org.springframework.ai.tool.definition.ToolDefinition;
import java.util.List;

/** 每一轮内部工具循环都经过此处；终止信号在管理器外层抛出，不能被当成普通工具错误吞掉。 */
public final class GuardedToolCallingManager implements ToolCallingManager {
    private final ToolCallingManager delegate = ToolCallingManager.builder().build();
    @Override public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) { return delegate.resolveToolDefinitions(options); }
    public static RequestExecutionGuard guard(Prompt prompt) {
        if (prompt.getOptions() instanceof ToolCallingChatOptions options && options.getToolContext() != null
                && options.getToolContext().get(RequestExecutionGuard.CONTEXT_KEY) instanceof RequestExecutionGuard guard) return guard;
        return null;
    }
    @Override public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse response) {
        RequestExecutionGuard guard = guard(prompt);
        if (guard != null) guard.checkActive();
        try {
            ToolExecutionResult result = delegate.executeToolCalls(prompt, response);
            if (guard != null) guard.checkActive();
            return result;
        } catch (RuntimeException error) {
            if (guard != null) guard.checkActive();
            throw error;
        }
    }
}
