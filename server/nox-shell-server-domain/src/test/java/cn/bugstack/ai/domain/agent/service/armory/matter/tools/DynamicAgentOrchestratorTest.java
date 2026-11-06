package cn.bugstack.ai.domain.agent.service.armory.matter.tools;

import cn.bugstack.ai.domain.agent.model.valobj.dynamic.AgentExecutionContext;
import cn.bugstack.ai.domain.agent.model.valobj.dynamic.DynamicTask;
import cn.bugstack.ai.domain.agent.model.valobj.dynamic.DynamicTaskPlan;
import cn.bugstack.ai.domain.agent.model.valobj.dynamic.TaskStatus;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 动态 Agent 编排器测试 - 覆盖 DAG 调度与失败处理的多场景：
 * <ul>
 *   <li>无依赖并发任务全部成功</li>
 *   <li>依赖链按序执行（下游能看到上游完成后的状态）</li>
 *   <li>中间任务失败 → 直接/间接下游级联 SKIPPED，无依赖分支不受影响</li>
 *   <li>failFast=true → 首个失败后剩余任务全部 SKIPPED</li>
 *   <li>failFast=false（默认）→ 失败不中断整体</li>
 *   <li>并发上限生效（maxConcurrency=1 时串行执行）</li>
 *   <li>汇总结果包含 failedCount / skippedCount</li>
 * </ul>
 * 说明：测试通过 FakeDispatchService 替换真实的 SubAgentDispatchService，
 * 不触碰 AgentCatalog / Runner / LLM，纯验证编排与失败传播逻辑。
 */
public class DynamicAgentOrchestratorTest {

    /** 可编程行为的派发服务替身：taskId -> 执行动作 */
    private static class FakeDispatchService extends SubAgentDispatchService {
        private final Map<String, Runnable> behaviors = new ConcurrentHashMap<>();
        private final Map<String, Integer> callCounts = new ConcurrentHashMap<>();
        private final Set<String> concurrentRunning = ConcurrentHashMap.newKeySet();
        private volatile int maxConcurrentObserved = 0;

        FakeDispatchService() {
            super(null, null, Executors.newFixedThreadPool(4));
        }

        void onTask(String taskId, Runnable behavior) {
            behaviors.put(taskId, behavior);
        }

        int callCount(String taskId) {
            return callCounts.getOrDefault(taskId, 0);
        }

        @Override
        public void execute(AgentExecutionContext context, DynamicTask task) {
            callCounts.merge(task.getTaskId(), 1, Integer::sum);
            concurrentRunning.add(task.getTaskId());
            synchronized (concurrentRunning) {
                maxConcurrentObserved = Math.max(maxConcurrentObserved, concurrentRunning.size());
            }
            try {
                Runnable behavior = behaviors.get(task.getTaskId());
                if (behavior != null) {
                    behavior.run();
                }
                // 行为未显式置终态时默认成功
                if (task.getStatus() == TaskStatus.RUNNING || task.getStatus() == TaskStatus.PENDING) {
                    task.setStatus(TaskStatus.COMPLETED);
                    task.setResult("fake-result-" + task.getTaskId());
                }
            } finally {
                concurrentRunning.remove(task.getTaskId());
            }
        }

        int maxConcurrentObserved() {
            return maxConcurrentObserved;
        }
    }

    private FakeDispatchService dispatchService;
    private DynamicAgentOrchestrator orchestrator;
    private AgentExecutionContext context;

    @Before
    public void setUp() {
        dispatchService = new FakeDispatchService();
        orchestrator = new DynamicAgentOrchestrator(dispatchService, Executors.newFixedThreadPool(4));
        context = AgentExecutionContext.builder()
                .userId("test-user")
                .agentId("parent-agent")
                .parentSessionId("test-session")
                .build();
    }

    private DynamicTask task(String taskId, String... dependsOn) {
        return DynamicTask.builder()
                .taskId(taskId)
                .agentName("fakeAgent")
                .request("request-" + taskId)
                .dependsOn(List.of(dependsOn))
                .build();
    }

    @Test
    public void shouldCompleteAllIndependentTasks() {
        DynamicTaskPlan plan = DynamicTaskPlan.builder()
                .tasks(List.of(task("a"), task("b"), task("c")))
                .build();

        Map<String, Object> result = orchestrator.execute(context, plan);

        assertEquals(Boolean.TRUE, result.get("allSucceeded"));
        assertEquals(0L, result.get("failedCount"));
        assertEquals(0L, result.get("skippedCount"));
        plan.getTasks().forEach(t -> assertEquals(TaskStatus.COMPLETED, t.getStatus()));
    }

    @Test
    public void shouldExecuteDependencyChainInOrder() {
        DynamicTask a = task("a");
        DynamicTask b = task("b", "a");
        DynamicTask c = task("c", "b");
        // 验证执行顺序：下游执行时上游必须已 COMPLETED
        dispatchService.onTask("b", () ->
                assertEquals("b 执行时 a 必须已完成", TaskStatus.COMPLETED, a.getStatus()));
        dispatchService.onTask("c", () ->
                assertEquals("c 执行时 b 必须已完成", TaskStatus.COMPLETED, b.getStatus()));

        DynamicTaskPlan plan = DynamicTaskPlan.builder().tasks(List.of(a, b, c)).build();
        Map<String, Object> result = orchestrator.execute(context, plan);

        assertEquals(Boolean.TRUE, result.get("allSucceeded"));
        assertEquals(1, dispatchService.callCount("a"));
        assertEquals(1, dispatchService.callCount("b"));
        assertEquals(1, dispatchService.callCount("c"));
    }

    @Test
    public void shouldCascadeSkipDependentsWhenMiddleTaskFails() {
        // a → b(FAILED) → c，独立分支 d 不受影响
        DynamicTask a = task("a");
        DynamicTask b = task("b", "a");
        DynamicTask c = task("c", "b");
        DynamicTask d = task("d");
        dispatchService.onTask("b", () -> {
            b.setStatus(TaskStatus.FAILED);
            b.setError("模拟执行失败");
        });

        DynamicTaskPlan plan = DynamicTaskPlan.builder().tasks(List.of(a, b, c, d)).build();
        Map<String, Object> result = orchestrator.execute(context, plan);

        assertEquals(Boolean.FALSE, result.get("allSucceeded"));
        assertEquals(1L, result.get("failedCount"));
        assertEquals(1L, result.get("skippedCount"));

        assertEquals(TaskStatus.COMPLETED, a.getStatus());
        assertEquals(TaskStatus.FAILED, b.getStatus());
        // 关键断言：c 被显式跳过而非卡在 PENDING，且未被派发执行
        assertEquals(TaskStatus.SKIPPED, c.getStatus());
        assertTrue(c.getError().contains("skipped"));
        assertEquals(0, dispatchService.callCount("c"));
        // 无依赖分支照常完成
        assertEquals(TaskStatus.COMPLETED, d.getStatus());
        assertEquals(1, dispatchService.callCount("d"));
    }

    @Test
    public void shouldCascadeSkipAlongMultiLevelChain() {
        // a(FAILED) → b → c → d：跳过应沿链级联传播到最末端
        DynamicTask a = task("a");
        DynamicTask b = task("b", "a");
        DynamicTask c = task("c", "b");
        DynamicTask d = task("d", "c");
        dispatchService.onTask("a", () -> a.setStatus(TaskStatus.FAILED));

        DynamicTaskPlan plan = DynamicTaskPlan.builder().tasks(List.of(a, b, c, d)).build();
        Map<String, Object> result = orchestrator.execute(context, plan);

        assertEquals(1L, result.get("failedCount"));
        assertEquals(3L, result.get("skippedCount"));
        assertEquals(TaskStatus.FAILED, a.getStatus());
        assertEquals(TaskStatus.SKIPPED, b.getStatus());
        assertEquals(TaskStatus.SKIPPED, c.getStatus());
        assertEquals(TaskStatus.SKIPPED, d.getStatus());
        assertEquals(0, dispatchService.callCount("d"));
    }

    @Test
    public void shouldSkipRemainingTasksWhenFailFastEnabled() {
        // failFast=true：b 失败后，依赖 b 的 c 被级联 SKIPPED，依赖慢任务 e 的 d 也因 failFast 中止
        DynamicTask a = task("a");
        DynamicTask b = task("b");
        DynamicTask c = task("c", "b");  // c 依赖 b
        DynamicTask e = task("e");       // e 是慢任务
        DynamicTask d = task("d", "e");  // d 依赖 e，b 失败时 d 还在 PENDING
        dispatchService.onTask("b", () -> b.setStatus(TaskStatus.FAILED));
        dispatchService.onTask("e", () -> sleepQuietly(500));  // e 执行 500ms，确保 b 先失败

        DynamicTaskPlan plan = DynamicTaskPlan.builder()
                .tasks(List.of(a, b, c, e, d))
                .maxConcurrency(4)
                .failFast(true)
                .build();

        Map<String, Object> result = orchestrator.execute(context, plan);

        assertEquals(Boolean.FALSE, result.get("allSucceeded"));
        assertEquals(TaskStatus.FAILED, b.getStatus());
        // c 依赖 b，b 失败后 c 被级联 SKIPPED
        assertEquals(TaskStatus.SKIPPED, c.getStatus());
        assertTrue(c.getError().contains("skipped"));
        assertEquals(0, dispatchService.callCount("c"));
        // d 依赖 e（进行中），failFast 下 b 失败后 d 被 SKIPPED
        assertEquals(TaskStatus.SKIPPED, d.getStatus());
        assertTrue(d.getError().contains("failFast"));
        assertEquals(0, dispatchService.callCount("d"));
    }

    @Test
    public void shouldContinueOtherBranchesWhenFailFastDisabled() {
        // failFast=false（默认）：b 失败不中断 c 执行
        DynamicTask a = task("a");
        DynamicTask b = task("b");
        DynamicTask c = task("c");
        dispatchService.onTask("b", () -> b.setStatus(TaskStatus.FAILED));

        DynamicTaskPlan plan = DynamicTaskPlan.builder()
                .tasks(List.of(a, b, c))
                .failFast(false)
                .build();

        Map<String, Object> result = orchestrator.execute(context, plan);

        assertEquals(Boolean.FALSE, result.get("allSucceeded"));
        assertEquals(1L, result.get("failedCount"));
        assertEquals(0L, result.get("skippedCount"));
        assertEquals(TaskStatus.COMPLETED, a.getStatus());
        assertEquals(TaskStatus.COMPLETED, c.getStatus());
    }

    @Test
    public void shouldRespectMaxConcurrencyLimit() {
        // maxConcurrency=1 时，三个任务必须串行（观测到的最大并发为 1）
        DynamicTaskPlan plan = DynamicTaskPlan.builder()
                .tasks(List.of(task("a"), task("b"), task("c")))
                .maxConcurrency(1)
                .build();
        for (String id : List.of("a", "b", "c")) {
            dispatchService.onTask(id, () -> sleepQuietly(50));
        }

        Map<String, Object> result = orchestrator.execute(context, plan);

        assertEquals(Boolean.TRUE, result.get("allSucceeded"));
        assertEquals("maxConcurrency=1 时应串行执行", 1, dispatchService.maxConcurrentObserved());
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
