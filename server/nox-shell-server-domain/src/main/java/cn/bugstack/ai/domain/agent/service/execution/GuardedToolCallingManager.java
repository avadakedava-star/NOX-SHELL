package cn.bugstack.ai.domain.agent.service.execution;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.*;
import org.springframework.ai.tool.definition.ToolDefinition;
import java.util.List;

/** 每一轮内部工具循环都经过此处；终止信号在管理器外层抛出，不能被当成普通工具错误吞掉。 */
public final class GuardedToolCallingManager implements ToolCallingManager {
    /** 真正的工具执行仍交给 Spring AI 默认实现，这里只做前置/后置守卫检查。 */
    private final ToolCallingManager delegate = ToolCallingManager.builder().build();
    @Override public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) { return delegate.resolveToolDefinitions(options); }
    /** 从 prompt 的 toolContext 中取回本次请求共享的守卫；非本链路调用返回 null，按普通工具执行。 */
    public static RequestExecutionGuard guard(Prompt prompt) {
        if (prompt.getOptions() instanceof ToolCallingChatOptions options && options.getToolContext() != null
                && options.getToolContext().get(RequestExecutionGuard.CONTEXT_KEY) instanceof RequestExecutionGuard guard) return guard;
        return null;
    }
    @Override public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse response) {
        RequestExecutionGuard guard = guard(prompt);
        // 执行前：若预算已耗尽或已判定停滞，直接抛出终止异常，连工具都不再调用。
        if (guard != null) guard.checkActive();
        try {
            ToolExecutionResult result = delegate.executeToolCalls(prompt, response);
            // 执行后：让守卫重新评估超时/停滞（工具执行期间可能已越过截止时间）。
            if (guard != null) guard.checkActive();
            return result;
        } catch (RuntimeException error) {
            // 工具自身抛错时也要检查守卫：优先把真正的终止原因（ExecutionHaltedException）抛给上层，而不是普通工具异常。
            if (guard != null) guard.checkActive();
            throw error;
        }
    }
}
