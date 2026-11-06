package cn.bugstack.ai.domain.agent.service.armory.matter.tools;

import cn.bugstack.ai.domain.agent.model.valobj.dynamic.DynamicTaskPlan;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class PlanParserTest {
    private final PlanParser planParser = new PlanParser();

    @Test
    public void shouldParseJsonWrappedByMarkdown() {
        String content = """
                ```json
                {"tasks":[{"taskId":"check","agentName":"sshDiagnosisAgent","request":"检查"}],"maxConcurrency":2}
                ```
                """;

        DynamicTaskPlan plan = planParser.parse(content, List.of("sshDiagnosisAgent"));

        assertEquals(1, plan.getTasks().size());
        assertEquals(2, plan.getMaxConcurrency().intValue());
    }

    @Test
    public void shouldRejectUnknownAgent() {
        String content = "{\"tasks\":[{\"taskId\":\"check\",\"agentName\":\"unknownAgent\",\"request\":\"检查\"}]}";

        assertThrows(IllegalArgumentException.class,
                () -> planParser.parse(content, List.of("sshDiagnosisAgent")));
    }

    @Test
    public void shouldParseFailFastAndTaskRetryFields() {
        String content = """
                {"tasks":[{"taskId":"check","agentName":"sshDiagnosisAgent","request":"检查","maxRetries":2,"timeoutSeconds":60}],"failFast":true,"maxConcurrency":2}
                """;

        DynamicTaskPlan plan = planParser.parse(content, List.of("sshDiagnosisAgent"));

        assertEquals(Boolean.TRUE, plan.getFailFast());
        assertEquals(Integer.valueOf(2), plan.getTasks().get(0).getMaxRetries());
        assertEquals(Integer.valueOf(60), plan.getTasks().get(0).getTimeoutSeconds());
    }

    @Test
    public void shouldDefaultFailFastToFalseWhenAbsent() {
        String content = "{\"tasks\":[{\"taskId\":\"check\",\"agentName\":\"sshDiagnosisAgent\",\"request\":\"检查\"}]}";

        DynamicTaskPlan plan = planParser.parse(content, List.of("sshDiagnosisAgent"));

        assertEquals(Boolean.FALSE, plan.getFailFast());
        assertEquals(Integer.valueOf(0), plan.getTasks().get(0).getMaxRetries());
    }
}
