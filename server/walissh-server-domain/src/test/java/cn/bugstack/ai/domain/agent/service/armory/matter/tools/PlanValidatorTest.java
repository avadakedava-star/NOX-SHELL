package cn.bugstack.ai.domain.agent.service.armory.matter.tools;

import cn.bugstack.ai.domain.agent.model.valobj.dynamic.DynamicTask;
import cn.bugstack.ai.domain.agent.model.valobj.dynamic.DynamicTaskPlan;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class PlanValidatorTest {
    private final PlanValidator planValidator = new PlanValidator();

    @Test
    public void shouldValidateTaskDependencies() {
        DynamicTaskPlan plan = DynamicTaskPlan.builder()
                .tasks(List.of(
                        DynamicTask.builder().taskId("check").agentName("sshDiagnosisAgent").request("检查").build(),
                        DynamicTask.builder().taskId("fix").agentName("sshChangeAgent").request("修复")
                                .dependsOn(List.of("check")).build()))
                .build();

        planValidator.validate(plan, 2);
    }

    @Test
    public void shouldRejectTooManyTasks() {
        DynamicTaskPlan plan = DynamicTaskPlan.builder()
                .tasks(List.of(
                        DynamicTask.builder().taskId("a").agentName("sshDiagnosisAgent").request("a").build(),
                        DynamicTask.builder().taskId("b").agentName("sshDiagnosisAgent").request("b").build()))
                .build();

        assertThrows(IllegalArgumentException.class, () -> planValidator.validate(plan, 1));
    }

    @Test
    public void shouldRejectDependencyCycle() {
        DynamicTaskPlan plan = DynamicTaskPlan.builder()
                .tasks(List.of(
                        DynamicTask.builder().taskId("a").agentName("sshDiagnosisAgent").request("a")
                                .dependsOn(List.of("b")).build(),
                        DynamicTask.builder().taskId("b").agentName("sshDiagnosisAgent").request("b")
                                .dependsOn(List.of("a")).build()))
                .build();

        assertThrows(IllegalArgumentException.class, () -> planValidator.validate(plan, 2));
    }

    @Test
    public void shouldClampMaxRetriesToUpperBound() {
        // LLM 规划出失控的重试次数时应被钳制到 3，不抛异常
        DynamicTask task = DynamicTask.builder()
                .taskId("a").agentName("sshDiagnosisAgent").request("a")
                .maxRetries(100).build();
        DynamicTaskPlan plan = DynamicTaskPlan.builder().tasks(List.of(task)).build();

        planValidator.validate(plan, 1);

        assertEquals(Integer.valueOf(3), task.getMaxRetries());
    }

    @Test
    public void shouldKeepReasonableMaxRetries() {
        DynamicTask task = DynamicTask.builder()
                .taskId("a").agentName("sshDiagnosisAgent").request("a")
                .maxRetries(2).build();
        DynamicTaskPlan plan = DynamicTaskPlan.builder().tasks(List.of(task)).build();

        planValidator.validate(plan, 1);

        assertEquals(Integer.valueOf(2), task.getMaxRetries());
    }
}
