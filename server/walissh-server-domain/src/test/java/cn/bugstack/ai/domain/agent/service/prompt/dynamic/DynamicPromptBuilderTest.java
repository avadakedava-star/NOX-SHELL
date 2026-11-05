package cn.bugstack.ai.domain.agent.service.prompt.dynamic;

import cn.bugstack.ai.domain.agent.model.valobj.intent.IntentResultVO;
import cn.bugstack.ai.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import cn.bugstack.ai.domain.agent.model.valobj.prompt.MilestoneVO;
import cn.bugstack.ai.domain.agent.model.valobj.prompt.PromptContextVO;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DynamicPromptBuilderTest {

    private final DynamicPromptBuilder builder = new DynamicPromptBuilder();

    @Test
    public void shouldSeparateStableAndEphemeralContext() {
        PromptContextVO context = PromptContextVO.builder()
                .taskDescription("排查 nginx 502")
                .currentDirectory("/var/log/nginx")
                .toolResultSummary("tail: connect() failed")
                .build();

        String stableContext = builder.buildStableContext(context);
        String ephemeralContext = builder.buildEphemeralContext(context);

        assertTrue(stableContext.contains("[当前任务]"));
        assertFalse(stableContext.contains("[工具执行摘要]"));
        assertTrue(ephemeralContext.contains("[工具执行摘要]"));
        assertFalse(ephemeralContext.contains("[当前任务]"));
    }

    @Test
    public void shouldRenderIntentAsNonAuthoritativeHint() {
        PromptContextVO context = PromptContextVO.builder()
                .intentResult(IntentResultVO.builder()
                        .intent(IntentTypeEnumVO.DIAGNOSE)
                        .confidence(0.86)
                        .entities(Map.of("service", "nginx"))
                        .candidateIntents(List.of(IntentTypeEnumVO.MONITOR))
                        .build())
                .milestoneVOS(List.of(MilestoneVO.builder()
                        .type(MilestoneVO.Type.ERROR)
                        .content("permission denied")
                        .build()))
                .build();

        String prompt = builder.buildEphemeralContext(context);

        assertTrue(prompt.contains("[意图提示]"));
        assertTrue(prompt.contains("DIAGNOSE"));
        assertTrue(prompt.contains("86%"));
        assertTrue(prompt.contains("MONITOR"));
        assertTrue(prompt.contains("service=nginx"));
        assertTrue(prompt.contains("不得覆盖"));
    }
}
