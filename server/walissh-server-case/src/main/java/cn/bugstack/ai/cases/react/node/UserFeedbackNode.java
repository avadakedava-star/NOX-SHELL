package cn.bugstack.ai.cases.react.node;

import cn.bugstack.ai.api.dto.ChatRequestDTO;
import cn.bugstack.ai.api.dto.ReActResultDTO;
import cn.bugstack.ai.cases.react.AbstractAIAgentReActSupport;
import cn.bugstack.ai.cases.react.factory.DefaultReActFactory;
import cn.bugstack.ai.domain.agent.service.IChatContextService;
import cn.bugstack.ai.domain.agent.service.IPromptService;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import jakarta.annotation.Resource;

/**
 * ReAct 用户反馈/结束节点
 *
 * <p>职责：
 * 1. 组装最终结果
 * 2. 清理会话绑定的终端资源
 * 3. 将 DynamicContext 中的统计信息同步到 ResultDTO
 * 4. 清理会话级别的上下文缓存（如工具摘要、里程碑等）
 *
 * @author xiaofuge bugstack.cn @小傅哥
 */
@Slf4j
@Component("reactUserFeedbackNode")
public class UserFeedbackNode extends AbstractAIAgentReActSupport {

    @Resource
    private IChatContextService chatContextService;

    @Resource
    private IPromptService promptService;

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        log.info("ReAct UserFeedbackNode - 生成最终结果");

        String sessionId = dynamicContext.getSessionId();

        // 1. 构建最终结果
        ReActResultDTO result = dynamicContext.getResult();
        result.setStopReason(dynamicContext.getStopReason());
        result.setFinalResponse(dynamicContext.getAssistantContent() != null
                ? dynamicContext.getAssistantContent().toString() : "");

        // 2. 同步真实的统计数据
        result.setTotalSteps(dynamicContext.getStep());
        result.setTotalToolCalls(dynamicContext.getTotalToolCallCount().get());

        // 3. 将会话中实际执行的工具调用记录设置到结果中
        result.setToolCalls(dynamicContext.getExecutedToolCalls());

        // 4. 清理资源绑定和缓存
        unbindTerminalSession(sessionId);
        clearCurrentTerminalSession();
        chatContextService.clearSessionContext(sessionId);
        promptService.clearMilestones(sessionId);

        log.info("会话 {} 结束，总步数: {}, 总工具调用: {}, 状态: {}",
                sessionId, result.getTotalSteps(), result.getTotalToolCalls(), dynamicContext.getStopReason());

        return result;
    }

    @Override
    public StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
            ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        // 这是链条最后一环
        return defaultStrategyHandler;
    }

}
