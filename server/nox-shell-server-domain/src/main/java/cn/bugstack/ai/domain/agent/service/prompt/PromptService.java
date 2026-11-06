package cn.bugstack.ai.domain.agent.service.prompt;

import cn.bugstack.ai.domain.agent.model.valobj.prompt.PromptContextVO;
import cn.bugstack.ai.domain.agent.model.valobj.intent.IntentResultVO;
import cn.bugstack.ai.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import cn.bugstack.ai.domain.agent.service.IChatContextService;
import cn.bugstack.ai.domain.agent.service.IIntentService;
import cn.bugstack.ai.domain.agent.service.IPromptService;
import cn.bugstack.ai.domain.agent.service.prompt.PromptEnvelope;
import cn.bugstack.ai.domain.agent.service.prompt.dynamic.DynamicPromptBuilder;
import cn.bugstack.ai.domain.agent.service.prompt.dynamic.MilestoneTracker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * 提示词服务
 * <p>
 * 组合 DynamicPromptBuilder、MilestoneTracker、IChatContextService，
 * 向 case 层提供统一的提示词领域能力。
 * <p>
 * 上下文采集已下沉到 IChatContextService 的 Provider 体系；本类是 Prompt 组装收口：
 * 稳定上下文放用户消息前，实时上下文放用户消息后，并用 PromptEnvelope 保留原始消息。
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2026/7/30 23:16
 */
@Slf4j
@Service
public class PromptService implements IPromptService {

    /** 动态提示词构建器——负责组装结构化消息前缀（环境/命令/里程碑/工具摘要/任务） */
    @Resource
    private DynamicPromptBuilder dynamicPromptBuilder;

    /** 里程碑追踪器——检测并缓存用户纠偏、任务切换等关键事件，供动态 Prompt 引用 */
    @Resource
    private MilestoneTracker milestoneTracker;

    /** 上下文管理服务——聚合各 ContextProvider 输出，组装 PromptContextVO */
    @Resource
    private IChatContextService chatContextService;

    /** 意图服务——读取最近一次完整识别结果，供结构化提示渲染 */
    @Resource
    private IIntentService intentService;

    @Override
    public void detectAndRecordMilestone(String sessionId, String role, String content) {
        milestoneTracker.detectAndRecord(sessionId, role, content);
    }

    @Override
    public String buildEnrichedMessage(String userMessage, String sessionId, String userId, String terminalSessionId, List<String> recentCommands, List<Map<String, Object>> messageHistory) {
        // 向后兼容：无意图标签的重载，委托给带 intentLabel 的版本（传 null）
        return buildEnrichedMessage(userMessage, sessionId, userId, terminalSessionId, recentCommands, messageHistory, null);
    }

    @Override
    public String buildEnrichedMessage(String userMessage, String sessionId, String userId, String terminalSessionId, List<String> recentCommands, List<Map<String, Object>> messageHistory, String intentLabel) {
        // 统一组装：Provider 分层结果 -> 稳定前缀 / 动态尾部 -> PromptEnvelope 定界用户原文。
        PromptContextVO promptContextVO = chatContextService.buildPromptContext(sessionId, userId, terminalSessionId, messageHistory);
        promptContextVO.setRecentCommands(recentCommands);
        promptContextVO.setIntentResult(resolveIntentResult(sessionId, intentLabel));
        promptContextVO.setIntentLabel(resolveIntentLabel(intentLabel, promptContextVO.getIntentResult()));

        String stableContext = dynamicPromptBuilder.buildStableContext(promptContextVO);
        String dynamicContext = dynamicPromptBuilder.buildEphemeralContext(promptContextVO);
        return PromptEnvelope.compose(stableContext, userMessage, dynamicContext);
    }

    @Override
    public void clearMilestones(String sessionId) {
        milestoneTracker.clear(sessionId);
    }

    /**
     * 优先读取 ContextTracker 保存的完整识别结果；没有完整结果时兼容旧 intentLabel。
     */
    private IntentResultVO resolveIntentResult(String sessionId, String intentLabel) {
        return intentService.getLastIntentResult(sessionId);
    }

    private String resolveIntentLabel(String intentLabel, IntentResultVO result) {
        if (result != null) {
            return result.getIntent() != null ? result.getIntent().name() : null;
        }
        if (intentLabel == null || intentLabel.isBlank()) {
            return null;
        }
        try {
            IntentTypeEnumVO.valueOf(intentLabel);
            return intentLabel;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

}
