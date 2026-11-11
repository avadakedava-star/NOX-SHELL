package cn.bugstack.ai.domain.agent.service.armory.matter.patch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.models.springai.MessageConverter;
import com.google.adk.models.springai.error.SpringAIErrorMapper;
import com.google.adk.models.springai.observability.SpringAIObservabilityHandler;
import com.google.adk.models.springai.properties.SpringAIProperties;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.openai.api.OpenAiApi;
import reactor.core.publisher.Flux;

import java.util.Objects;

/**
 * Spring AI 补丁
 * @author xiaofuge bugstack.cn @小傅哥
 * 2026/1/9 08:20
 */
@Slf4j
public class MySpringAI extends BaseLlm {

    private static MessageConverter converter(ObjectMapper mapper, ChatModel model) {
        java.util.List<org.springframework.ai.tool.ToolCallback> defaults = java.util.List.of();
        if (model instanceof org.springframework.ai.openai.OpenAiChatModel openAi
                && openAi.getDefaultOptions() instanceof org.springframework.ai.model.tool.ToolCallingChatOptions options) {
            defaults = options.getToolCallbacks();
        }
        return new MyMessageConverter(mapper, defaults == null ? java.util.List.of() : defaults);
    }

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final ObjectMapper objectMapper;
    private final MessageConverter messageConverter;
    private final SpringAIObservabilityHandler observabilityHandler;

    public MySpringAI(ChatModel chatModel) {
        super(extractModelName(chatModel));
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
        this.streamingChatModel =
                (chatModel instanceof StreamingChatModel) ? (StreamingChatModel) chatModel : null;
        this.objectMapper = new ObjectMapper();
        this.messageConverter = converter(objectMapper, this.chatModel);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(createDefaultObservabilityConfig());
    }

    public MySpringAI(ChatModel chatModel, String modelName) {
        super(Objects.requireNonNull(modelName, "model name cannot be null"));
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
        this.streamingChatModel =
                (chatModel instanceof StreamingChatModel) ? (StreamingChatModel) chatModel : null;
        this.objectMapper = new ObjectMapper();
        this.messageConverter = converter(objectMapper, this.chatModel);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(createDefaultObservabilityConfig());
    }

    public MySpringAI(StreamingChatModel streamingChatModel) {
        super(extractModelName(streamingChatModel));
        this.chatModel =
                (streamingChatModel instanceof ChatModel) ? (ChatModel) streamingChatModel : null;
        this.streamingChatModel =
                Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
        this.objectMapper = new ObjectMapper();
        this.messageConverter = converter(objectMapper, this.chatModel);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(createDefaultObservabilityConfig());
    }

    public MySpringAI(StreamingChatModel streamingChatModel, String modelName) {
        super(Objects.requireNonNull(modelName, "model name cannot be null"));
        this.chatModel =
                (streamingChatModel instanceof ChatModel) ? (ChatModel) streamingChatModel : null;
        this.streamingChatModel =
                Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
        this.objectMapper = new ObjectMapper();
        this.messageConverter = converter(objectMapper, this.chatModel);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(createDefaultObservabilityConfig());
    }

    public MySpringAI(ChatModel chatModel, StreamingChatModel streamingChatModel, String modelName) {
        super(Objects.requireNonNull(modelName, "model name cannot be null"));
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
        this.streamingChatModel =
                Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
        this.objectMapper = new ObjectMapper();
        this.messageConverter = converter(objectMapper, this.chatModel);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(createDefaultObservabilityConfig());
    }

    public MySpringAI(
            ChatModel chatModel,
            StreamingChatModel streamingChatModel,
            String modelName,
            SpringAIProperties.Observability observabilityConfig) {
        super(Objects.requireNonNull(modelName, "model name cannot be null"));
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
        this.streamingChatModel =
                Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
        this.objectMapper = new ObjectMapper();
        this.messageConverter = converter(objectMapper, this.chatModel);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(
                        Objects.requireNonNull(observabilityConfig, "observabilityConfig cannot be null"));
    }

    public MySpringAI(
            ChatModel chatModel, String modelName, SpringAIProperties.Observability observabilityConfig) {
        super(Objects.requireNonNull(modelName, "model name cannot be null"));
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
        this.streamingChatModel =
                (chatModel instanceof StreamingChatModel) ? (StreamingChatModel) chatModel : null;
        this.objectMapper = new ObjectMapper();
        this.messageConverter = converter(objectMapper, this.chatModel);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(
                        Objects.requireNonNull(observabilityConfig, "observabilityConfig cannot be null"));
    }

    public MySpringAI(
            StreamingChatModel streamingChatModel,
            String modelName,
            SpringAIProperties.Observability observabilityConfig) {
        super(Objects.requireNonNull(modelName, "model name cannot be null"));
        this.chatModel =
                (streamingChatModel instanceof ChatModel) ? (ChatModel) streamingChatModel : null;
        this.streamingChatModel =
                Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
        this.objectMapper = new ObjectMapper();
        this.messageConverter = converter(objectMapper, this.chatModel);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(
                        Objects.requireNonNull(observabilityConfig, "observabilityConfig cannot be null"));
    }

    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
        if (llmRequest.tools() instanceof cn.bugstack.ai.domain.agent.service.audit.AuditedToolMap tools) {
            var guard = tools.context().guard();
            return Flowable.defer(() -> {
                guard.checkActive();
                return generateUnchecked(llmRequest, stream);
            }).subscribeOn(io.reactivex.rxjava3.schedulers.Schedulers.io())
                    .takeUntil(Flowable.interval(1,java.util.concurrent.TimeUnit.SECONDS)
                            .map(tick->{guard.checkActive();return tick;}).filter(tick->false));
        }
        return generateUnchecked(llmRequest, stream);
    }

    private Flowable<LlmResponse> generateUnchecked(LlmRequest llmRequest, boolean stream) {
        if (stream) {
            if (this.streamingChatModel == null) {
                return Flowable.error(new IllegalStateException("StreamingChatModel is not configured"));
            }

            return generateStreamingContent(llmRequest);
        } else {
            if (this.chatModel == null) {
                return Flowable.error(new IllegalStateException("ChatModel is not configured"));
            }

            return generateContent(llmRequest);
        }
    }

    private Flowable<LlmResponse> generateContent(LlmRequest llmRequest) {
        SpringAIObservabilityHandler.RequestContext context =
                observabilityHandler.startRequest(model(), "chat");

        try {
            Prompt prompt = messageConverter.toLlmPrompt(llmRequest);
            observabilityHandler.logRequest(prompt.toString(), model());

            ChatResponse chatResponse = chatModel.call(prompt);
            LlmResponse llmResponse = enrichWithUsageMetadata(
                    messageConverter.toLlmResponse(chatResponse), chatResponse);

            observabilityHandler.logResponse(extractTextFromResponse(llmResponse), model());

            // Extract token counts if available
            int totalTokens = extractTokenCount(chatResponse);
            int inputTokens = extractInputTokenCount(chatResponse);
            int outputTokens = extractOutputTokenCount(chatResponse);

            observabilityHandler.recordSuccess(context, totalTokens, inputTokens, outputTokens);
            return Flowable.just(llmResponse);
        } catch (Exception e) {
            var stopped = cn.bugstack.ai.domain.agent.service.execution.ExecutionHaltedException.find(e);
            if (stopped != null) return Flowable.error(stopped);
            observabilityHandler.recordError(context, e);
            SpringAIErrorMapper.MappedError mappedError = SpringAIErrorMapper.mapError(e);

            return Flowable.error(new RuntimeException(mappedError.getNormalizedMessage(), e));
        }
    }

    private Flowable<LlmResponse> generateStreamingContent(LlmRequest llmRequest) {
        SpringAIObservabilityHandler.RequestContext context =
                observabilityHandler.startRequest(model(), "streaming");

        return Flowable.defer(() -> {
            Prompt prompt=messageConverter.toLlmPrompt(llmRequest);
            var parts=new java.util.ArrayList<com.google.genai.types.Part>();
            var text=new StringBuilder();
            var last=new java.util.concurrent.atomic.AtomicReference<LlmResponse>();
            return Flowable.fromPublisher(streamingChatModel.stream(prompt))
                    .map(response->{
                        LlmResponse chunk=enrichWithUsageMetadata(messageConverter.toLlmResponse(response,true),response);
                        last.set(chunk);
                        chunk.content().flatMap(com.google.genai.types.Content::parts).orElse(java.util.List.of()).forEach(part->{
                            if(part.text().isPresent() && !part.thought().orElse(false)) text.append(part.text().get());
                            else if(part.text().isEmpty()) parts.add(part);
                        });
                        // 明确区分流式片段与本次模型调用的汇总，不靠文字前缀猜测。
                        return LlmResponse.builder().content(chunk.content().orElse(null)).partial(true).turnComplete(false).build();
                    })
                    .concatWith(Flowable.defer(()->{
                        if(last.get()==null) return Flowable.error(new IllegalStateException("模型连接结束但没有返回响应"));
                        if(!text.isEmpty()) parts.add(0,com.google.genai.types.Part.fromText(text.toString()));
                        var finalChunk=last.get();
                        var complete=LlmResponse.builder().partial(false).turnComplete(true)
                                .content(com.google.genai.types.Content.builder().role("model").parts(parts).build());
                        finalChunk.usageMetadata().ifPresent(complete::usageMetadata);
                        finalChunk.finishReason().ifPresent(complete::finishReason);
                        finalChunk.errorCode().ifPresent(complete::errorCode);
                        finalChunk.errorMessage().ifPresent(complete::errorMessage);
                        return Flowable.just(complete.build());
                    }))
                    .doOnError(error->observabilityHandler.recordError(context,error));
        });
    }

    @Override
    public BaseLlmConnection connect(LlmRequest llmRequest) {
        throw new UnsupportedOperationException(
                "Live connection is not supported for Spring AI models.");
    }

    private static String extractModelName(Object model) {
        // Spring AI models may not always have a straightforward way to get model name
        // This is a fallback that can be overridden by providing explicit model name
        String className = model.getClass().getSimpleName();
        return className.toLowerCase().replace("chatmodel", "").replace("model", "");
    }

    private SpringAIProperties.Observability createDefaultObservabilityConfig() {
        SpringAIProperties.Observability config = new SpringAIProperties.Observability();
        config.setEnabled(true);
        config.setMetricsEnabled(true);
        config.setIncludeContent(false);
        return config;
    }

    /**
     * 补全 LlmResponse 的 usageMetadata（ADK MessageConverter 不设置该字段）。
     * 从 Spring AI ChatResponse 的 metadata 中提取 token 用量，
     * 并尝试从 OpenAI 原生 Usage 中提取 cachedTokens（缓存命中 token 数）。
     */
   private LlmResponse enrichWithUsageMetadata(LlmResponse llmResponse, ChatResponse chatResponse) {
       try {
           log.info("enrichWithUsageMetadata 开始 | chatResponse:{} | metadata:{}",
                   chatResponse != null, chatResponse != null ? chatResponse.getMetadata() : "null");
           if (chatResponse == null || chatResponse.getMetadata() == null) {
               return llmResponse;
           }
           Usage usage = chatResponse.getMetadata().getUsage();
           log.info("enrichWithUsageMetadata usage:{}", usage);
           if (usage == null) {
               return llmResponse;
           }
           Integer promptTokens = usage.getPromptTokens();
           Integer completionTokens = usage.getCompletionTokens();
           Integer totalTokens = usage.getTotalTokens();
           Integer cachedTokens = extractCachedTokens(usage);
          Object nativeUsage = usage.getNativeUsage();
          log.info("SpringAI-Usage 原始数据 | nativeUsage:{} | promptTokens:{} | completionTokens:{} | cachedTokens:{}",
                  nativeUsage, promptTokens, completionTokens, cachedTokens);

           GenerateContentResponseUsageMetadata.Builder metadataBuilder = GenerateContentResponseUsageMetadata.builder()
                   .promptTokenCount(promptTokens)
                   .candidatesTokenCount(completionTokens)
                   .totalTokenCount(totalTokens);
           if (cachedTokens != null) {
               metadataBuilder.cachedContentTokenCount(cachedTokens);
           }
           GenerateContentResponseUsageMetadata metadata = metadataBuilder.build();
          log.info("enrichWithUsageMetadata metadata 构建完成:{}", metadata);
          LlmResponse.Builder builder = LlmResponse.builder()
                  .content(llmResponse.content().orElse(null))
                  .partial(llmResponse.partial().orElse(null))
                  .turnComplete(llmResponse.turnComplete().orElse(null))
                  .usageMetadata(metadata);
          llmResponse.finishReason().ifPresent(builder::finishReason);
          llmResponse.errorCode().ifPresent(builder::errorCode);
          llmResponse.errorMessage().ifPresent(builder::errorMessage);
          llmResponse.interrupted().ifPresent(builder::interrupted);
          llmResponse.groundingMetadata().ifPresent(builder::groundingMetadata);
          llmResponse.modelVersion().ifPresent(builder::modelVersion);
          llmResponse.avgLogprobs().ifPresent(builder::avgLogprobs);
          return builder.build();
       } catch (Exception e) {
           log.warn("补全 usageMetadata 失败", e);
           return llmResponse;
       }
    }

    /**
     * 从 Spring AI Usage 的原生对象中提取缓存命中的 token 数。
     * OpenAI 返回的 prompt_tokens_details.cached_tokens 即为缓存命中数。
     */
    private Integer extractCachedTokens(Usage usage) {
        try {
            Object nativeUsage = usage.getNativeUsage();
            if (nativeUsage instanceof OpenAiApi.Usage openAiUsage) {
                if (openAiUsage.promptTokensDetails() != null
                        && openAiUsage.promptTokensDetails().cachedTokens() != null) {
                    return openAiUsage.promptTokensDetails().cachedTokens();
                }
            }
        } catch (Exception e) {
            log.warn("提取 cachedTokens 失败: {}", e.getMessage());
        }
        return null;
    }

    private int extractTokenCount(ChatResponse chatResponse) {
        // Spring AI may include usage metadata in the response
        // This is a simplified implementation - actual token counts depend on provider
        try {
            if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
                return chatResponse.getMetadata().getUsage().getTotalTokens();
            }
        } catch (Exception e) {
            // Ignore errors in token extraction
        }
        return 0;
    }

    private int extractInputTokenCount(ChatResponse chatResponse) {
        try {
            if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
                return chatResponse.getMetadata().getUsage().getPromptTokens();
            }
        } catch (Exception e) {
            // Ignore errors in token extraction
        }
        return 0;
    }

    private int extractOutputTokenCount(ChatResponse chatResponse) {
        try {
            if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
                return chatResponse.getMetadata().getUsage().getCompletionTokens();
            }
        } catch (Exception e) {
            // Ignore errors in token extraction
        }
        return 0;
    }

    private String extractTextFromResponse(LlmResponse response) {
        if (response.content().isPresent() && response.content().get().parts().isPresent()) {
            return response.content().get().parts().get().stream()
                    .map(part -> part.text().orElse(""))
                    .filter(text -> text != null && !text.isEmpty())
                    .findFirst()
                    .orElse("");
        }
        return "";
    }

}
