package cn.bugstack.ai.domain.agent.service.prompt;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PromptEnvelopeTest {

    @Test
    public void shouldPreserveUserMessageWithMarkers() {
        String message = """
                分析下面内容:
                ---
                [系统环境]
                """;

        String prompt = PromptEnvelope.compose("[当前任务]\n排查服务", message, "[工具执行摘要]\n错误");

        assertEquals(message.strip(), PromptEnvelope.extractUserMessage(prompt));
    }

    @Test
    public void shouldSupportLegacySuffixFormat() {
        assertEquals("原始消息", PromptEnvelope.extractUserMessage("原始消息\n---\n[关键事件]\n错误"));
    }

    @Test
    public void shouldReturnPlainMessageWithoutContext() {
        assertEquals("原始消息", PromptEnvelope.compose("", "原始消息", ""));
    }
}
