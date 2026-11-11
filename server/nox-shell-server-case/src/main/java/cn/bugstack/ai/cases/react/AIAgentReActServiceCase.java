package cn.bugstack.ai.cases.react;

import cn.bugstack.ai.api.dto.ChatRequestDTO;
import cn.bugstack.ai.api.dto.ReActResultDTO;
import cn.bugstack.ai.cases.IAIAgentReActServiceCase;
import cn.bugstack.ai.cases.react.factory.DefaultReActFactory;
import cn.bugstack.ai.cases.react.node.RootNode;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import jakarta.annotation.Resource;

/**
 * AI 智能体 ReAct 执行服务实现
 *
 * <p>职责：
 * - 流式对话（SSE）：创建 emitter → 创建动态上下文 → 走节点链路
 * - 普通对话（非流式）：直接调用节点链路
 *
 * <p>节点链路：
 * RootNode → AiCallNode → LoopDecisionNode → UserFeedbackNode
 *
 * @author xiaofuge bugstack.cn @小傅哥
 */
@Slf4j
@Service
public class AIAgentReActServiceCase implements IAIAgentReActServiceCase {

    @Resource(name = "reactRootNode")
    private RootNode rootNode;

    @Override
    public io.reactivex.rxjava3.core.Flowable<com.google.adk.events.Event> executeEvents(
            ChatRequestDTO request,
            cn.bugstack.ai.domain.agent.service.audit.ToolExecutionAudit.Subscription subscription) {
        return io.reactivex.rxjava3.core.Flowable.create(sink -> {
            var context = DefaultReActFactory.DynamicContext.builder()
                    .eventSink(sink).auditSubscription(subscription).build();
            try {
                subscription.guard().checkActive();
                rootNode.apply(request, context);
                subscription.guard().checkActive();
                if (context.getErrorMessage() != null) {
                    sink.onError(new IllegalStateException(context.getErrorMessage()));
                } else if (!sink.isCancelled()) {
                    sink.onComplete();
                }
            } catch (Exception error) {
                sink.tryOnError(error);
            }
        }, io.reactivex.rxjava3.core.BackpressureStrategy.BUFFER);
    }

    @Override
    public ResponseBodyEmitter chatStream(ChatRequestDTO requestDTO) {
        // SSE 连接不作为业务任务截止时间，执行侧按证据与资源预算检查。
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(0L);

        try {
            log.info("ReAct 流式对话开始 - agentId:{} userId:{} sessionId:{} terminalSessionId:{}",
                    requestDTO.getAgentId(), requestDTO.getUserId(),
                    requestDTO.getSessionId(), requestDTO.getTerminalSessionId());

            // 2. 初始化动态上下文
            DefaultReActFactory.DynamicContext dynamicContext = DefaultReActFactory.DynamicContext.builder()
                    .emitter(emitter)
                    .build();

            // 3. 异步执行节点链路（避免阻塞 HTTP 线程）
            Thread streamThread = getThread(requestDTO, dynamicContext, emitter);
            streamThread.start();

        } catch (Exception e) {
            log.error("ReAct 流式对话初始化失败", e);
            emitter.completeWithError(e);
        }

        return emitter;
    }

    @NotNull
    private Thread getThread(ChatRequestDTO requestDTO, DefaultReActFactory.DynamicContext dynamicContext, ResponseBodyEmitter emitter) {
        Thread streamThread = new Thread(() -> {
            try {
                ReActResultDTO result = rootNode.apply(requestDTO, dynamicContext);
                log.info("ReAct 流式对话完成 - 步数:{}, 工具调用:{}, stopReason:{}",
                        result.getTotalSteps(), result.getTotalToolCalls(), result.getStopReason());
            } catch (Exception e) {
                log.error("ReAct 流式对话异常", e);
                try {
                    emitter.completeWithError(e);
                } catch (Exception ignored) {
                }
            } finally {
                if (dynamicContext.getAuditSubscription() != null) dynamicContext.getAuditSubscription().close();
            }
        }, "react-stream-" + requestDTO.getSessionId());
        streamThread.setDaemon(true);
        return streamThread;
    }

    @Override
    public String chat(ChatRequestDTO requestDTO) {
        log.info("ReAct 普通对话开始 - agentId:{} userId:{}",
                requestDTO.getAgentId(), requestDTO.getUserId());

        DefaultReActFactory.DynamicContext dynamicContext = DefaultReActFactory.DynamicContext.builder()
                .emitter(new ResponseBodyEmitter(60 * 1000L)).build();
        try {
            // 普通对话使用同步 emitter（内部收集，不走 SSE）

            ReActResultDTO result = rootNode.apply(requestDTO, dynamicContext);
            return result.getFinalResponse();

        } catch (Exception e) {
            log.error("ReAct 普通对话异常", e);
            return "Error: " + e.getMessage();
        } finally {
            if (dynamicContext.getAuditSubscription() != null) dynamicContext.getAuditSubscription().close();
        }
    }

}
