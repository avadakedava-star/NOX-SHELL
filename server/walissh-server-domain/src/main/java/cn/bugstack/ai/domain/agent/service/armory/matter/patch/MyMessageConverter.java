package cn.bugstack.ai.domain.agent.service.armory.matter.patch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.springai.MessageConverter;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeType;
import cn.bugstack.ai.domain.agent.service.audit.AuditedToolMap;
import cn.bugstack.ai.domain.agent.service.audit.AuditedToolCallback;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI 补丁
 * @author xiaofuge bugstack.cn @小傅哥
 * 2026/1/9 08:17
 */
public class MyMessageConverter extends MessageConverter {
    private final List<ToolCallback> defaultCallbacks;

    public MyMessageConverter(ObjectMapper objectMapper) {
        this(objectMapper, List.of());
    }

    public MyMessageConverter(ObjectMapper objectMapper, List<ToolCallback> defaultCallbacks) {
        super(objectMapper);
        this.defaultCallbacks = List.copyOf(defaultCallbacks);
    }

    @Override
    public Prompt toLlmPrompt(LlmRequest llmRequest) {

        List<Media> mediaList = new ArrayList<>();
        for (Content content : llmRequest.contents()) {
            for (Part part : content.parts().orElse(List.of())) {
                if (part.inlineData().isPresent()) {
                    // Handle inline media data (images, audio, video, etc.)
                    com.google.genai.types.Blob blob = part.inlineData().get();
                    if (blob.mimeType().isPresent() && blob.data().isPresent()) {
                        try {
                            MimeType mimeType = MimeType.valueOf(blob.mimeType().get());
                            // Create Media object from inline data using ByteArrayResource
                            org.springframework.core.io.ByteArrayResource resource =
                                    new org.springframework.core.io.ByteArrayResource(blob.data().get());
                            mediaList.add(new Media(mimeType, resource));
                        } catch (Exception e) {
                            // Log warning but continue processing other parts
                            // In production, consider proper logging framework
                            System.err.println(
                                    "Warning: Failed to parse media mime type: " + blob.mimeType().get());
                        }
                    }
                } else if (part.fileData().isPresent()) {
                    // Handle file-based media (URI references)
                    com.google.genai.types.FileData fileData = part.fileData().get();
                    if (fileData.mimeType().isPresent() && fileData.fileUri().isPresent()) {
                        try {
                            MimeType mimeType = MimeType.valueOf(fileData.mimeType().get());
                            // Create Media object from file URI
                            URI uri = URI.create(fileData.fileUri().get());
                            mediaList.add(new Media(mimeType, uri));
                        } catch (Exception e) {
                            System.err.println(
                                    "Warning: Failed to parse media mime type: " + fileData.mimeType().get());
                        }
                    }
                }
            }
        }

        Prompt llmPrompt = super.toLlmPrompt(llmRequest);
        llmPrompt.getUserMessage().getMedia().addAll(mediaList);

        if (llmRequest.tools() instanceof AuditedToolMap tools) {
            ToolCallingChatOptions options;
            if (llmPrompt.getOptions() instanceof ToolCallingChatOptions toolOptions) {
                options = toolOptions;
            } else {
                options = llmPrompt.getOptions() == null ? new org.springframework.ai.openai.OpenAiChatOptions()
                        : org.springframework.ai.model.ModelOptionsUtils.copyToTarget(llmPrompt.getOptions(),
                        org.springframework.ai.chat.prompt.ChatOptions.class, org.springframework.ai.openai.OpenAiChatOptions.class);
                llmPrompt = new Prompt(llmPrompt.getInstructions(), options);
            }
            // 默认 MCP/Skills 与请求级 ADK 工具必须一起覆盖；请求中的同名工具优先。
            java.util.Map<String, ToolCallback> callbacks = new java.util.LinkedHashMap<>();
            defaultCallbacks.forEach(callback -> callbacks.put(callback.getToolDefinition().name(), callback));
            if (options.getToolCallbacks() != null) options.getToolCallbacks().forEach(callback -> callbacks.put(callback.getToolDefinition().name(), callback));
            options.setToolCallbacks(callbacks.values().stream().map(callback -> {
                // ADK 工具已在 BoundTool 中记录；MCP/Skills 在 Spring AI 回调入口记录。
                return tools.containsKey(callback.getToolDefinition().name()) ? callback
                        : new AuditedToolCallback(callback, tools.context(), tools.audit());
            }).toList());
            java.util.Map<String, Object> toolContext = new java.util.HashMap<>();
            if (options.getToolContext() != null) toolContext.putAll(options.getToolContext());
            toolContext.put(cn.bugstack.ai.domain.agent.service.execution.RequestExecutionGuard.CONTEXT_KEY, tools.context().guard());
            options.setToolContext(toolContext);
        }

        return llmPrompt;
    }

}
