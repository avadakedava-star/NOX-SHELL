package cn.bugstack.ai.domain.agent.service.chat;

import cn.bugstack.ai.domain.agent.adapter.repository.IChatHistoryRepository;
import cn.bugstack.ai.domain.agent.model.entity.ChatCommandEntity;
import cn.bugstack.ai.domain.agent.model.entity.ChatMessageEntity;
import cn.bugstack.ai.domain.agent.model.entity.ChatSessionEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import cn.bugstack.ai.domain.agent.model.valobj.dynamic.AgentExecutionContext;
import cn.bugstack.ai.domain.agent.service.IChatService;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.ai.domain.agent.service.armory.matter.tools.SshSessionContextRegistry;
import cn.bugstack.ai.domain.agent.service.armory.matter.tools.SshExecuteAdkTool;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ChatService implements IChatService {

    private static final RunConfig STREAM_RUN_CONFIG = cn.bugstack.ai.domain.agent.service.execution.ExecutionRunConfig.builder()
            .streamingMode(RunConfig.StreamingMode.SSE)
            .autoCreateSession(true)
            .build();

    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    @Resource
    private IChatHistoryRepository chatHistoryRepository;

    private final Map<String, String> userSessions = new ConcurrentHashMap<>();
    /**
     * 业务会话到 SSH 终端会话的本地缓存。
     * <p>同时写入 {@link SshSessionContextRegistry}，用于子 Agent 在线程池中恢复终端上下文。</p>
     */
    private final Map<String, String> sessionTerminalContext = new ConcurrentHashMap<>();

    @Override
    public List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList() {
        Map<String, AiAgentConfigTableVO> tables = aiAgentAutoConfigProperties.getTables();

        List<AiAgentConfigTableVO.Agent> agentList = new ArrayList<>();
        if (null != tables) {
            for (AiAgentConfigTableVO vo : tables.values()) {
                if (null != vo.getAgent()) {
                    agentList.add(vo.getAgent());
                }
            }
        }

        return agentList;
    }

    @Override
    public String createSession(String agentId, String userId) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String appName = aiAgentRegisterVO.getAppName();
        Runner runner = aiAgentRegisterVO.getRunner();
        String sessionKey = agentId + ":" + userId;

        return userSessions.computeIfAbsent(sessionKey, key -> {
            Session session = runner.sessionService().createSession(appName, userId)
                    .blockingGet();

            // 会话元数据落库（2-8 新增）：try-catch 旁路写入，DB 写入失败不影响 ADK Session 创建。
            // 旁路原则：如果 DB 异常（如表不存在），只是 DB 里没有这条记录，不影响 Agent 正常运行。
            try {
                chatHistoryRepository.saveSession(ChatSessionEntity.builder()
                        .id(session.id())
                        .agentId(agentId)
                        .userId(userId)
                        .title("新会话")
                        .messageCount(0)
                        .build());
            } catch (Exception e) {
                log.error("保存会话元数据失败 sessionId={}", session.id(), e);
            }

            return session.id();
        });
    }

    /** 建立/更新业务会话与 SSH 终端会话的绑定关系（供后续消息流式处理时回填） */
    public void bindTerminalSession(String sessionId, String terminalSessionId) {
        if (sessionId != null && !sessionId.isBlank() && terminalSessionId != null && !terminalSessionId.isBlank()) {
            sessionTerminalContext.put(sessionId, terminalSessionId);
            SshSessionContextRegistry.bind(sessionId, terminalSessionId);
        }
    }

    /** 查询业务会话绑定的 SSH 终端会话 ID，未绑定时返回 Optional.empty() */
    public Optional<String> getTerminalSession(String sessionId) {
        return Optional.ofNullable(sessionTerminalContext.get(sessionId));
    }

    @Override
    public List<String> handleMessage(String agentId, String userId, String message) {

        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String sessionId = createSession(agentId, userId);

        return handleMessage(agentId, userId, sessionId, message);
    }

    @Override
    public List<String> handleMessage(String agentId, String userId, String sessionId, String message) {

        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        Runner runner = aiAgentRegisterVO.getRunner();

        Content userMsg = Content.fromParts(Part.fromText(message));
        Flowable<Event> events = runner.runAsync(userId, sessionId, userMsg, cn.bugstack.ai.domain.agent.service.execution.ExecutionRunConfig.builder().build());

        List<String> outputs = new ArrayList<>();
        events.blockingForEach(event -> outputs.add(event.stringifyContent()));

        return outputs;
    }

    /**
     * 带执行上下文的消息处理：先把上下文中的 SSH 终端会话写入 ThreadLocal，
     * 使本轮对话（含子 Agent 派发）内的 SSH 工具复用该终端，再走标准消息处理。
     */
    public List<String> handleMessage(String agentId, String userId, String sessionId, String message,
                                      AgentExecutionContext context) {
        if (context != null && context.getTerminalSessionId() != null) {
            SshExecuteAdkTool.setCurrentTerminalSession(context.getTerminalSessionId());
        }
        return handleMessage(agentId, userId, sessionId, message);
    }

    @Override
    public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        Runner runner = aiAgentRegisterVO.getRunner();

        Content userMsg = Content.fromParts(Part.fromText(message));
        return runner.runAsync(userId, sessionId, userMsg, STREAM_RUN_CONFIG);
    }

    @Override
    public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message, String terminalSessionId) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        Runner runner = aiAgentRegisterVO.getRunner();

        // 将终端绑定同时写入本地缓存、注册表和 ThreadLocal，覆盖当前请求与子 Runner 两类执行路径。
        if (terminalSessionId != null && !terminalSessionId.isEmpty()) {
            log.info("设置终端会话ID: {}", terminalSessionId);
            bindTerminalSession(sessionId, terminalSessionId);
            SshExecuteAdkTool.setCurrentTerminalSession(terminalSessionId);
            // ADK Session 状态会随子 Runner 初始上下文传播，避免 ThreadLocal 在异步线程切换后丢失。
            com.google.adk.sessions.Session session = runner.sessionService()
                    .getSession(aiAgentRegisterVO.getAppName(), userId, sessionId, Optional.empty())
                    .blockingGet();
            if (session != null) {
                session.state().put("terminalSessionId", terminalSessionId);
            }
        } else {
            // 本次请求未显式携带终端会话时，回退到该业务会话的绑定关系
            getTerminalSession(sessionId).ifPresent(value -> {
                bindTerminalSession(sessionId, value);
                SshExecuteAdkTool.setCurrentTerminalSession(value);
                com.google.adk.sessions.Session session = runner.sessionService()
                        .getSession(aiAgentRegisterVO.getAppName(), userId, sessionId, Optional.empty())
                        .blockingGet();
                if (session != null) {
                    session.state().put("terminalSessionId", value);
                }
            });
        }

        Content userMsg = Content.fromParts(Part.fromText(message));

        return runner.runAsync(userId, sessionId, userMsg, STREAM_RUN_CONFIG);
    }

    /**
     * 查询用户会话列表（2-8 新增）。
     * <p>
     * 为前端历史记录功能提供后端支撑，默认返回最多 20 条。
     *
     * @param agentId 智能体 ID
     * @param userId  用户 ID
     * @param limit   最大返回条数，≤0 时默认 20
     * @return 会话列表
     */
    @Override
    public List<ChatSessionEntity> querySessionList(String agentId, String userId, int limit) {
        return chatHistoryRepository.querySessionList(agentId, userId, limit > 0 ? limit : 20);
    }

    /**
     * 查询会话消息列表（2-8 新增）。
     * <p>
     * 为前端历史消息展示提供后端支撑，默认返回最多 100 条。
     *
     * @param sessionId 会话 ID
     * @param limit     最大返回条数，≤0 时默认 100
     * @return 消息列表（时间正序）
     */
    @Override
    public List<ChatMessageEntity> queryMessageList(String sessionId, int limit) {
        return chatHistoryRepository.queryMessageList(sessionId, limit > 0 ? limit : 100);
    }

    @Override
    public List<String> handleMessage(ChatCommandEntity chatCommandEntity) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(chatCommandEntity.getAgentId());

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        List<Part> parts = new ArrayList<>();

        List<ChatCommandEntity.Content.Text> texts = chatCommandEntity.getTexts();
        if (null != texts && !texts.isEmpty()) {
            for (ChatCommandEntity.Content.Text text : texts) {
                parts.add(Part.fromText(text.getMessage()));
            }
        }

        List<ChatCommandEntity.Content.File> files = chatCommandEntity.getFiles();
        if (null != files && !files.isEmpty()) {
            for (ChatCommandEntity.Content.File file : files) {
                parts.add(Part.fromUri(file.getFileUri(), file.getMimeType()));
            }
        }

        List<ChatCommandEntity.Content.InlineData> inlineDatas = chatCommandEntity.getInlineDatas();
        if (null != inlineDatas && !inlineDatas.isEmpty()) {
            for (ChatCommandEntity.Content.InlineData inlineData : inlineDatas) {
                parts.add(Part.fromBytes(inlineData.getBytes(), inlineData.getMimeType()));
            }
        }

        Content content = Content.builder().role("user").parts(parts).build();

        // 获取运行体
        Runner runner = aiAgentRegisterVO.getRunner();

        Flowable<Event> events = runner.runAsync(chatCommandEntity.getUserId(), chatCommandEntity.getSessionId(), content,
                cn.bugstack.ai.domain.agent.service.execution.ExecutionRunConfig.builder().build());

        List<String> outputs = new ArrayList<>();
        events.blockingForEach(event -> outputs.add(event.stringifyContent()));

        return outputs;
    }

}
