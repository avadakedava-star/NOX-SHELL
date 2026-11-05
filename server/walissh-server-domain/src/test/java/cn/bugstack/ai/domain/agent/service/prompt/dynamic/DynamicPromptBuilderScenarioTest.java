package cn.bugstack.ai.domain.agent.service.prompt.dynamic;

import cn.bugstack.ai.domain.agent.model.valobj.intent.IntentResultVO;
import cn.bugstack.ai.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import cn.bugstack.ai.domain.agent.model.valobj.prompt.MilestoneVO;
import cn.bugstack.ai.domain.agent.model.valobj.prompt.PromptContextVO;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DynamicPromptBuilderScenarioTest {

    private final DynamicPromptBuilder builder = new DynamicPromptBuilder();

    // ---- stable / ephemeral 分层边界 ----

    @Test
    public void stable_shouldContainTaskAndMemory() {
        PromptContextVO ctx = PromptContextVO.builder()
                .taskDescription("排查 nginx 502")
                .longTermMemorySummary("用户偏好使用 systemctl")
                .build();
        String stable = builder.buildStableContext(ctx);
        assertTrue(stable.contains("[当前任务]"));
        assertTrue(stable.contains("[长期记忆]"));
        assertFalse(stable.contains("[工具执行摘要]"));
    }

    @Test
    public void stable_shouldBeEmptyWhenNoStableFields() {
        PromptContextVO ctx = PromptContextVO.builder()
                .currentDirectory("/tmp")
                .toolResultSummary("ls: ok")
                .build();
        assertEquals("", builder.buildStableContext(ctx));
    }

    @Test
    public void ephemeral_shouldContainEnvironmentAndToolSummary() {
        PromptContextVO ctx = PromptContextVO.builder()
                .osInfo("Linux x86_64")
                .currentDirectory("/var/log")
                .toolResultSummary("tail: error")
                .build();
        String ephemeral = builder.buildEphemeralContext(ctx);
        assertTrue(ephemeral.contains("[系统环境]"));
        assertTrue(ephemeral.contains("[工具执行摘要]"));
        assertFalse(ephemeral.contains("[当前任务]"));
    }

    @Test
    public void ephemeral_shouldContainRecentCommandsAndMilestones() {
        PromptContextVO ctx = PromptContextVO.builder()
                .recentCommands(List.of("df -h", "tail /var/log/syslog"))
                .milestoneVOS(List.of(MilestoneVO.builder()
                        .type(MilestoneVO.Type.USER_CORRECTION)
                        .content("不要改配置文件")
                        .build()))
                .build();
        String ephemeral = builder.buildEphemeralContext(ctx);
        assertTrue(ephemeral.contains("[最近执行的命令]"));
        assertTrue(ephemeral.contains("df -h"));
        assertTrue(ephemeral.contains("[关键事件]"));
        assertTrue(ephemeral.contains("USER_CORRECTION"));
    }

    @Test
    public void ephemeral_shouldBeEmptyWhenNoEphemeralFields() {
        PromptContextVO ctx = PromptContextVO.builder()
                .taskDescription("任务")
                .longTermMemorySummary("记忆")
                .build();
        assertEquals("", builder.buildEphemeralContext(ctx));
    }

    // ---- 意图提示场景覆盖 ----

    @Test
    public void intent_highConfidenceShouldIncludeAllDetails() {
        PromptContextVO ctx = PromptContextVO.builder()
                .intentResult(IntentResultVO.builder()
                        .intent(IntentTypeEnumVO.DIAGNOSE)
                        .confidence(0.92)
                        .entities(Map.of("service", "nginx", "error", "502"))
                        .candidateIntents(List.of(IntentTypeEnumVO.MONITOR))
                        .build())
                .build();
        String prompt = builder.buildEphemeralContext(ctx);
        assertTrue(prompt.contains("DIAGNOSE"));
        assertTrue(prompt.contains("92%"));
        assertTrue(prompt.contains("MONITOR"));
        assertTrue(prompt.contains("service=nginx"));
        assertTrue(prompt.contains("不得覆盖"));
    }

    @Test
    public void intent_unknownLowConfidenceShouldBeOmitted() {
        PromptContextVO ctx = PromptContextVO.builder()
                .intentResult(IntentResultVO.builder()
                        .intent(IntentTypeEnumVO.UNKNOWN)
                        .confidence(0.1)
                        .build())
                .build();
        assertEquals("", builder.buildEphemeralContext(ctx));
    }

    @Test
    public void intent_chatShouldBeIncluded() {
        PromptContextVO ctx = PromptContextVO.builder()
                .intentResult(IntentResultVO.builder()
                        .intent(IntentTypeEnumVO.CHAT)
                        .confidence(0.95)
                        .build())
                .build();
        String prompt = builder.buildEphemeralContext(ctx);
        assertTrue(prompt.contains("CHAT"));
        assertTrue(prompt.contains("95%"));
    }

    @Test
    public void intent_compoundShouldIncludeCandidates() {
        PromptContextVO ctx = PromptContextVO.builder()
                .intentResult(IntentResultVO.builder()
                        .intent(IntentTypeEnumVO.COMPOUND)
                        .confidence(0.85)
                        .candidateIntents(List.of(IntentTypeEnumVO.DIAGNOSE, IntentTypeEnumVO.CONFIGURE))
                        .build())
                .build();
        String prompt = builder.buildEphemeralContext(ctx);
        assertTrue(prompt.contains("COMPOUND"));
        assertTrue(prompt.contains("DIAGNOSE"));
        assertTrue(prompt.contains("CONFIGURE"));
    }

    @Test
    public void intent_reclassifiedShouldStillRender() {
        PromptContextVO ctx = PromptContextVO.builder()
                .intentResult(IntentResultVO.builder()
                        .intent(IntentTypeEnumVO.MONITOR)
                        .confidence(0.48)
                        .reclassified(true)
                        .build())
                .build();
        String prompt = builder.buildEphemeralContext(ctx);
        assertTrue(prompt.contains("MONITOR"));
        assertTrue(prompt.contains("48%"));
    }

    @Test
    public void intent_legacyLabelOnlyShouldFallbackToMinimalHint() {
        PromptContextVO ctx = PromptContextVO.builder()
                .intentLabel("DIAGNOSE")
                .build();
        String prompt = builder.buildEphemeralContext(ctx);
        assertTrue(prompt.contains("[意图提示]"));
        assertTrue(prompt.contains("DIAGNOSE"));
        assertFalse(prompt.contains("%"));
    }

    @Test
    public void intent_nullResultAndNullLabelShouldBeOmitted() {
        PromptContextVO ctx = PromptContextVO.builder().build();
        assertEquals("", builder.buildEphemeralContext(ctx));
    }

    @Test
    public void intent_resultShouldOverrideLabel() {
        PromptContextVO ctx = PromptContextVO.builder()
                .intentLabel("STALE")
                .intentResult(IntentResultVO.builder()
                        .intent(IntentTypeEnumVO.DIAGNOSE)
                        .confidence(0.9)
                        .build())
                .build();
        String prompt = builder.buildEphemeralContext(ctx);
        assertTrue(prompt.contains("DIAGNOSE"));
        assertFalse(prompt.contains("STALE"));
    }

    // ---- provider placement 消费验证 ----

    @Test
    public void stableContext_shouldReadFromPlacementMap() {
        PromptContextVO ctx = PromptContextVO.builder()
                .stableContext(Map.of("taskDescription", "来自 Provider 的任务"))
                .taskDescription("旧字段值")
                .build();
        String stable = builder.buildStableContext(ctx);
        assertTrue(stable.contains("来自 Provider 的任务"));
        assertFalse(stable.contains("旧字段值"));
    }

    @Test
    public void ephemeralContext_shouldReadFromPlacementMap() {
        PromptContextVO ctx = PromptContextVO.builder()
                .ephemeralContext(Map.of("currentDirectory", "/provider/path"))
                .currentDirectory("/旧路径")
                .build();
        String ephemeral = builder.buildEphemeralContext(ctx);
        assertTrue(ephemeral.contains("/provider/path"));
        assertFalse(ephemeral.contains("/旧路径"));
    }

    // ---- 完整链路：稳定 + 动态 + 意图 ----

    @Test
    public void fullPrompt_shouldHaveAllSectionsInOrder() {
        PromptContextVO ctx = PromptContextVO.builder()
                .stableContext(Map.of("taskDescription", "排查磁盘"))
                .ephemeralContext(Map.of("osInfo", "Linux", "toolResultSummary", "df: 90%"))
                .intentResult(IntentResultVO.builder()
                        .intent(IntentTypeEnumVO.DIAGNOSE)
                        .confidence(0.88)
                        .build())
                .build();

        String stable = builder.buildStableContext(ctx);
        String ephemeral = builder.buildEphemeralContext(ctx);

        assertTrue(stable.contains("[当前任务]"));
        assertTrue(ephemeral.contains("[系统环境]"));
        assertTrue(ephemeral.contains("[工具执行摘要]"));
        assertTrue(ephemeral.contains("[意图提示]"));
        assertTrue(ephemeral.contains("DIAGNOSE"));
    }
}
