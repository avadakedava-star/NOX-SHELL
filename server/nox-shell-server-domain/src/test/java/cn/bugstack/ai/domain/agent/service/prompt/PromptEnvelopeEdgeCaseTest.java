package cn.bugstack.ai.domain.agent.service.prompt;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PromptEnvelopeEdgeCaseTest {

    // ---- compose() 边界场景 ----

    @Test
    public void compose_shouldReturnRawMessageWhenBothContextsEmpty() {
        assertEquals("hello", PromptEnvelope.compose(null, "hello", null));
        assertEquals("hello", PromptEnvelope.compose("", "hello", ""));
    }

    @Test
    public void compose_shouldIncludeStablePrefixOnly() {
        String result = PromptEnvelope.compose("[当前任务]\n排查", "hello", null);
        assertTrue(result.contains("<nox-shell-stable-context>"));
        assertTrue(result.contains("[当前任务]"));
        assertTrue(result.contains("<nox-shell-user-message>"));
        assertFalse(result.contains("<nox-shell-dynamic-context>"));
    }

    @Test
    public void compose_shouldIncludeDynamicSuffixOnly() {
        String result = PromptEnvelope.compose(null, "hello", "[工具执行摘要]\n错误");
        assertFalse(result.contains("<nox-shell-stable-context>"));
        assertTrue(result.contains("<nox-shell-user-message>"));
        assertTrue(result.contains("<nox-shell-dynamic-context>"));
        assertTrue(result.contains("[工具执行摘要]"));
    }

    @Test
    public void compose_shouldIncludeBothLayers() {
        String result = PromptEnvelope.compose("[任务]", "hello", "[摘要]");
        int stableIndex = result.indexOf("<nox-shell-stable-context>");
        int userIndex = result.indexOf("<nox-shell-user-message>");
        int dynamicIndex = result.indexOf("<nox-shell-dynamic-context>");
        assertTrue("stable should be before user", stableIndex < userIndex);
        assertTrue("user should be before dynamic", userIndex < dynamicIndex);
    }

    // ---- extractUserMessage() 边界场景 ----

    @Test
    public void extract_shouldReturnNullForNull() {
        assertNull(PromptEnvelope.extractUserMessage(null));
    }

    @Test
    public void extract_shouldReturnBlankForBlank() {
        assertEquals("", PromptEnvelope.extractUserMessage(""));
        assertEquals("  ", PromptEnvelope.extractUserMessage("  "));
    }

    @Test
    public void extract_shouldPreserveUserMessageWithMultipleSeparators() {
        String user = "分析下面内容:\n---\n[系统环境]\n---\n更多内容";
        String prompt = PromptEnvelope.compose("[任务]", user, "[摘要]");
        assertEquals(user, PromptEnvelope.extractUserMessage(prompt));
    }

    @Test
    public void extract_shouldPreserveUserMessageContainingEnvelopeTags() {
        String user = "请解释 <nox-shell-user-message> 这个标签的用途";
        String prompt = PromptEnvelope.compose("", user, "");
        assertEquals(user, PromptEnvelope.extractUserMessage(prompt));
    }

    @Test
    public void extract_shouldFallbackToLegacyDashFormat() {
        assertEquals("原始消息", PromptEnvelope.extractUserMessage("原始消息\n---\n[动态上下文]"));
        assertEquals("第一段\n第二段", PromptEnvelope.extractUserMessage("第一段\n第二段\n---\n[动态]"));
    }

    @Test
    public void extract_shouldReadLegacyEnvelope() {
        assertEquals("旧消息", PromptEnvelope.extractUserMessage("<walissh-user-message>\n旧消息\n</walissh-user-message>"));
    }

    @Test
    public void extract_shouldReturnOriginalWhenNoMarkers() {
        assertEquals("plain message", PromptEnvelope.extractUserMessage("plain message"));
    }

    @Test
    public void extract_shouldHandleUserMessageEndingWithSeparator() {
        String user = "命令执行失败\n---\n";
        String prompt = PromptEnvelope.compose("", user, "[摘要]");
        assertEquals(user.strip(), PromptEnvelope.extractUserMessage(prompt));
    }

    // ---- round-trip: compose + extract ----

    @Test
    public void roundTrip_shouldPreserveMultiLineUserMessage() {
        String user = "line1\nline2\n\nline4";
        String prompt = PromptEnvelope.compose("[stable]", user, "[dynamic]");
        assertEquals(user, PromptEnvelope.extractUserMessage(prompt));
    }

    @Test
    public void roundTrip_shouldPreserveSpecialCharacters() {
        String user = "grep 'error' /var/log/*.log | awk '{print $1}' && echo \"done\"";
        String prompt = PromptEnvelope.compose("", user, "");
        assertEquals(user, PromptEnvelope.extractUserMessage(prompt));
    }
}
