package cn.bugstack.ai.domain.agent.service.prompt;

import cn.bugstack.ai.domain.agent.model.valobj.intent.IntentResultVO;
import cn.bugstack.ai.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import cn.bugstack.ai.domain.agent.model.valobj.prompt.PromptContextVO;
import cn.bugstack.ai.domain.agent.service.IChatContextService;
import cn.bugstack.ai.domain.agent.service.IIntentService;
import cn.bugstack.ai.domain.agent.service.prompt.dynamic.DynamicPromptBuilder;
import cn.bugstack.ai.domain.agent.service.prompt.dynamic.MilestoneTracker;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class PromptServiceScenarioTest {

    @Mock
    private IChatContextService chatContextService;

    @Mock
    private IIntentService intentService;

    @Mock
    private MilestoneTracker milestoneTracker;

    private PromptService promptService;

    private DynamicPromptBuilder dynamicPromptBuilder;
    private AutoCloseable mocks;

    @Before
    public void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        dynamicPromptBuilder = new DynamicPromptBuilder();
        promptService = new PromptService();
        setField(promptService, "chatContextService", chatContextService);
        setField(promptService, "intentService", intentService);
        setField(promptService, "milestoneTracker", milestoneTracker);
        setField(promptService, "dynamicPromptBuilder", dynamicPromptBuilder);
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to set field: " + fieldName, e);
        }
    }

    @After
    public void tearDown() throws Exception {
        mocks.close();
    }

    // ---- 全上下文场景：稳定前缀 + 用户原文 + 动态尾部 ----

    @Test
    public void buildEnrichedMessage_fullContextShouldHaveThreeSections() {
        when(chatContextService.buildPromptContext(nullable(String.class), nullable(String.class), nullable(String.class), anyList()))
                .thenReturn(PromptContextVO.builder()
                        .taskDescription("排查 nginx 502")
                        .osInfo("Linux x86_64")
                        .toolResultSummary("tail: connection refused")
                        .stableContext(Map.of("taskDescription", "排查 nginx 502"))
                        .ephemeralContext(Map.of("osInfo", "Linux x86_64", "toolResultSummary", "tail: connection refused"))
                        .build());
        when(intentService.getLastIntentResult(any())).thenReturn(null);

        String result = promptService.buildEnrichedMessage(
                "帮我看看 nginx 为什么 502", "session-1", "user-1", "term-1",
                List.of("tail /var/log/nginx/error.log"), List.of(), null);

        int stableStart = result.indexOf("<walissh-stable-context>");
        int userStart = result.indexOf("<walissh-user-message>");
        int dynamicStart = result.indexOf("<walissh-dynamic-context>");
        assertTrue("should contain stable context", stableStart >= 0);
        assertTrue("should contain user message", userStart > stableStart);
        assertTrue("should contain dynamic context", dynamicStart > userStart);
        assertTrue(result.contains("排查 nginx 502"));
        assertTrue(result.contains("帮我看看 nginx 为什么 502"));
        assertTrue(result.contains("tail: connection refused"));
        assertTrue(result.contains("tail /var/log/nginx/error.log"));
    }

    @Test
    public void buildEnrichedMessage_noContextShouldReturnRawMessage() {
        when(chatContextService.buildPromptContext(nullable(String.class), nullable(String.class), nullable(String.class), anyList()))
                .thenReturn(PromptContextVO.builder().build());
        when(intentService.getLastIntentResult(any())).thenReturn(null);

        String result = promptService.buildEnrichedMessage(
                "hello world", "session-1", "user-1", null, List.of(), List.of(), null);

        assertEquals("hello world", result);
    }

    // ---- 意图场景 ----

    @Test
    public void buildEnrichedMessage_intentFromTrackerShouldBeStructured() {
        IntentResultVO intentResult = IntentResultVO.builder()
                .intent(IntentTypeEnumVO.DIAGNOSE)
                .confidence(0.9)
                .entities(Map.of("service", "nginx"))
                .candidateIntents(List.of(IntentTypeEnumVO.MONITOR))
                .build();
        when(chatContextService.buildPromptContext(nullable(String.class), nullable(String.class), nullable(String.class), anyList()))
                .thenReturn(PromptContextVO.builder().build());
        when(intentService.getLastIntentResult("session-1")).thenReturn(intentResult);

        String result = promptService.buildEnrichedMessage(
                "nginx 502", "session-1", "user-1", null, List.of(), List.of(), "STALE_LABEL");

        assertTrue(result.contains("[意图提示]"));
        assertTrue(result.contains("DIAGNOSE"));
        assertTrue(result.contains("90%"));
        assertTrue(result.contains("MONITOR"));
        assertTrue(result.contains("service=nginx"));
        assertFalse(result.contains("STALE_LABEL"));
    }

    @Test
    public void buildEnrichedMessage_noTrackerResultShouldFallbackToLabel() {
        when(chatContextService.buildPromptContext(nullable(String.class), nullable(String.class), nullable(String.class), anyList()))
                .thenReturn(PromptContextVO.builder().build());
        when(intentService.getLastIntentResult(any())).thenReturn(null);

        String result = promptService.buildEnrichedMessage(
                "hello", "session-1", "user-1", null, List.of(), List.of(), "DIAGNOSE");

        assertTrue(result.contains("[意图提示]"));
        assertTrue(result.contains("DIAGNOSE"));
        assertFalse(result.contains("%"));
    }

    @Test
    public void buildEnrichedMessage_invalidLabelShouldBeIgnored() {
        when(chatContextService.buildPromptContext(nullable(String.class), nullable(String.class), nullable(String.class), anyList()))
                .thenReturn(PromptContextVO.builder().build());
        when(intentService.getLastIntentResult(any())).thenReturn(null);

        String result = promptService.buildEnrichedMessage(
                "hello", "session-1", "user-1", null, List.of(), List.of(), "NOT_A_VALID_INTENT");

        assertEquals("hello", result);
    }

    // ---- 用户消息含特殊内容场景 ----

    @Test
    public void buildEnrichedMessage_userMessageWithSeparatorsShouldBePreserved() {
        when(chatContextService.buildPromptContext(nullable(String.class), nullable(String.class), nullable(String.class), anyList()))
                .thenReturn(PromptContextVO.builder()
                        .stableContext(Map.of("taskDescription", "任务"))
                        .ephemeralContext(Map.of("toolResultSummary", "摘要"))
                        .build());
        when(intentService.getLastIntentResult(any())).thenReturn(null);

        String userMessage = "请解释:\n---\n以下是命令输出\n---\n```\ntotal 42\n```";
        String result = promptService.buildEnrichedMessage(
                userMessage, "session-1", "user-1", null, List.of(), List.of(), null);

        assertTrue(result.contains(userMessage));
        assertEquals(userMessage, PromptEnvelope.extractUserMessage(result));
    }

    @Test
    public void buildEnrichedMessage_userMessageWithCodeBlockShouldBePreserved() {
        when(chatContextService.buildPromptContext(nullable(String.class), nullable(String.class), nullable(String.class), anyList()))
                .thenReturn(PromptContextVO.builder()
                        .ephemeralContext(Map.of("osInfo", "Linux"))
                        .build());
        when(intentService.getLastIntentResult(any())).thenReturn(null);

        String userMessage = "执行以下命令:\n```bash\ndocker ps -a | grep nginx\n```";
        String result = promptService.buildEnrichedMessage(
                userMessage, "session-1", "user-1", null, List.of(), List.of(), null);

        assertTrue(result.contains("docker ps -a | grep nginx"));
        assertEquals(userMessage, PromptEnvelope.extractUserMessage(result));
    }

    // ---- 里程碑委托 ----

    @Test
    public void detectAndRecordMilestone_shouldDelegateToTracker() {
        promptService.detectAndRecordMilestone("session-1", "user", "换个思路");
        verify(milestoneTracker).detectAndRecord("session-1", "user", "换个思路");
    }

    @Test
    public void clearMilestones_shouldDelegateToTracker() {
        promptService.clearMilestones("session-1");
        verify(milestoneTracker).clear("session-1");
    }

    // ---- 向后兼容重载 ----

    @Test
    public void buildEnrichedMessage_legacyOverloadShouldDelegateWithNullIntent() {
        when(chatContextService.buildPromptContext(nullable(String.class), nullable(String.class), nullable(String.class), anyList()))
                .thenReturn(PromptContextVO.builder().build());
        when(intentService.getLastIntentResult(any())).thenReturn(null);

        String result = promptService.buildEnrichedMessage(
                "hello", "session-1", "user-1", null, List.of(), List.of());

        assertEquals("hello", result);
    }
}
