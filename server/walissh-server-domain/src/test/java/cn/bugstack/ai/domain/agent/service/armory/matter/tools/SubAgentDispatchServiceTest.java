package cn.bugstack.ai.domain.agent.service.armory.matter.tools;

import cn.bugstack.ai.domain.agent.model.valobj.dynamic.AgentExecutionContext;
import cn.bugstack.ai.domain.agent.model.valobj.dynamic.DynamicTask;
import cn.bugstack.ai.domain.agent.model.valobj.dynamic.TaskStatus;
import cn.bugstack.ai.domain.agent.service.armory.catalog.AgentCatalog;
import cn.bugstack.ai.domain.agent.service.armory.matter.session.factory.CustomRunnerFactory;
import com.google.adk.agents.BaseAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 子 Agent 派发服务测试 - 覆盖失败处理的多场景：
 * <ul>
 *   <li>正常执行成功 → COMPLETED</li>
 *   <li>Agent 不存在 → 直接 FAILED（确定性错误，不重试）</li>
 *   <li>执行期异常 + maxRetries=0 → FAILED 且 attempts=1</li>
 *   <li>执行期异常 + 重试成功 → COMPLETED 且 attempts>1</li>
 *   <li>重试耗尽 → FAILED，error 中包含尝试次数</li>
 *   <li>超时异常按重试策略处理</li>
 *   <li>dispatch 批量场景：单个失败不拖垮整体</li>
 * </ul>
 * 说明：Runner.runAsync 是 final 方法，Mockito 无法直接 stub，
 * 测试通过 mock CustomRunnerFactory.create() 的返回值，再用匿名子类覆盖 runAsync 行为。
 * 验证旧 maxRetries 参数不会导致工作流重放。
 */
public class SubAgentDispatchServiceTest {

    private AgentCatalog agentCatalog;
    private CustomRunnerFactory runnerFactory;
    private SubAgentDispatchService dispatchService;
    private AgentExecutionContext context;

    @Before
    public void setUp() {
        agentCatalog = new AgentCatalog();
        runnerFactory = mock(CustomRunnerFactory.class);
        dispatchService = new SubAgentDispatchService(agentCatalog, runnerFactory,
                Executors.newFixedThreadPool(4));
        context = AgentExecutionContext.builder()
                .userId("test-user")
                .agentId("parent-agent")
                .parentSessionId("test-session")
                .build();
    }

    /** 注册一个行为可定制的假 Agent 到目录 */
    private void registerAgent(String name, BaseAgent agent) {
        agentCatalog.register("parent-agent", Map.of(name, agent));
    }

    /** 构造一个总是成功的假 Runner（返回固定文本事件） */
    private Runner successRunner(String reply) {
        return new Runner(null, "test", null, null, null, List.of()) {
            @Override
            public Flowable<Event> runAsync(String userId, String sessionId, Content newMessage,
                                            com.google.adk.agents.RunConfig runConfig,
                                            Map<String, Object> initialState) {
                Event event = Event.builder()
                        .id("event-1").invocationId("inv-1").author("agent")
                        .content(Content.fromParts(Part.fromText(reply)))
                        .build();
                return Flowable.just(event);
            }
        };
    }

    /** 构造一个行为可编程的假 Runner */
    private Runner behaviorRunner(java.util.function.Supplier<Flowable<Event>> behavior) {
        return new Runner(null, "test", null, null, null, List.of()) {
            @Override
            public Flowable<Event> runAsync(String userId, String sessionId, Content newMessage,
                                            com.google.adk.agents.RunConfig runConfig,
                                            Map<String, Object> initialState) {
                return behavior.get();
            }
        };
    }

    /** 构造一个固定名称的假 Agent */
    private BaseAgent fakeAgent(String name) {
        BaseAgent agent = mock(BaseAgent.class);
        lenient().when(agent.name()).thenReturn(name);
        return agent;
    }

    @Test
    public void shouldCompleteTaskWhenAgentSucceeds() {
        registerAgent("sshAgent", fakeAgent("sshAgent"));
        when(runnerFactory.create(any(), anyString(), anyList())).thenReturn(successRunner("诊断正常"));

        DynamicTask task = DynamicTask.builder()
                .taskId("t1").agentName("sshAgent").request("检查磁盘").build();

        dispatchService.execute(context, task);

        assertEquals(TaskStatus.COMPLETED, task.getStatus());
        assertEquals("诊断正常", task.getResult());
        assertEquals(Integer.valueOf(1), task.getAttempts());
    }

    @Test
    public void shouldFailImmediatelyWhenAgentNotFound() {
        // 目录为空，agent 找不到
        DynamicTask task = DynamicTask.builder()
                .taskId("t1").agentName("ghostAgent").request("检查")
                .maxRetries(3)  // 即使配置了重试也不应重试（确定性错误）
                .build();

        long start = System.currentTimeMillis();
        dispatchService.execute(context, task);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(TaskStatus.FAILED, task.getStatus());
        assertTrue(task.getError().contains("agent not found"));
        // 未进入重试循环，attempts 保持默认 0，且应瞬时返回（无退避等待）
        assertEquals(Integer.valueOf(0), task.getAttempts());
        assertTrue("agent not found 应立即失败，无退避等待", elapsed < 1000);
    }

    @Test
    public void shouldFailWithoutRetryByDefault() {
        registerAgent("sshAgent", fakeAgent("sshAgent"));
        AtomicInteger callCount = new AtomicInteger();
        when(runnerFactory.create(any(), anyString(), anyList())).thenReturn(behaviorRunner(() -> {
            callCount.incrementAndGet();
            return Flowable.error(new RuntimeException("SSH 连接被拒"));
        }));

        DynamicTask task = DynamicTask.builder()
                .taskId("t1").agentName("sshAgent").request("写配置").build(); // maxRetries 默认 0

        long start = System.currentTimeMillis();
        dispatchService.execute(context, task);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(TaskStatus.FAILED, task.getStatus());
        assertEquals(Integer.valueOf(1), task.getAttempts());
        assertEquals(1, callCount.get());
        assertTrue("默认不重试，应无退避等待", elapsed < 1000);
    }

    @Test
    public void shouldNotReplayWholeAgentAfterFailure() {
        registerAgent("sshAgent", fakeAgent("sshAgent"));
        AtomicInteger callCount = new AtomicInteger();
        when(runnerFactory.create(any(), anyString(), anyList())).thenReturn(behaviorRunner(() -> {
            if (callCount.incrementAndGet() < 3) {
                return Flowable.error(new RuntimeException("瞬时网络抖动"));
            }
            Event event = Event.builder()
                    .id("event-ok").invocationId("inv-ok").author("agent")
                    .content(Content.fromParts(Part.fromText("第三次成功")))
                    .build();
            return Flowable.just(event);
        }));

        DynamicTask task = DynamicTask.builder()
                .taskId("t1").agentName("sshAgent").request("读取日志")
                .maxRetries(3).build();

        dispatchService.execute(context, task);

        assertEquals(TaskStatus.FAILED, task.getStatus());
        assertEquals(Integer.valueOf(1), task.getAttempts());
        assertEquals(1, callCount.get());
    }

    @Test
    public void shouldIgnoreRequestedWholeTaskRetries() {
        registerAgent("sshAgent", fakeAgent("sshAgent"));
        AtomicInteger callCount = new AtomicInteger();
        when(runnerFactory.create(any(), anyString(), anyList())).thenReturn(behaviorRunner(() -> {
            callCount.incrementAndGet();
            return Flowable.error(new RuntimeException("持续故障"));
        }));

        DynamicTask task = DynamicTask.builder()
                .taskId("t1").agentName("sshAgent").request("读取指标")
                .maxRetries(2).build(); // 旧计划的重试参数不能授权重放整个 Agent

        dispatchService.execute(context, task);

        assertEquals(TaskStatus.FAILED, task.getStatus());
        assertEquals(Integer.valueOf(1), task.getAttempts());
        assertEquals(1, callCount.get());
        assertTrue("error 应包含尝试次数", task.getError().contains("attempts=1"));
        assertTrue("error 应包含根因", task.getError().contains("持续故障"));
    }

    @Test
    public void shouldNotReplayWholeAgentAfterTimeout() {
        registerAgent("sshAgent", fakeAgent("sshAgent"));
        AtomicInteger callCount = new AtomicInteger();
        when(runnerFactory.create(any(), anyString(), anyList())).thenReturn(behaviorRunner(() -> {
            if (callCount.incrementAndGet() == 1) {
                // 首次超时（RxJava timeout 抛出 TimeoutException 包装）
                return Flowable.error(new java.util.concurrent.TimeoutException("子Agent响应超时"));
            }
            Event event = Event.builder()
                    .id("event-ok").invocationId("inv-ok").author("agent")
                    .content(Content.fromParts(Part.fromText("重试后拿到结果")))
                    .build();
            return Flowable.just(event);
        }));

        DynamicTask task = DynamicTask.builder()
                .taskId("t1").agentName("sshAgent").request("慢查询")
                .maxRetries(1).build();

        dispatchService.execute(context, task);

        assertEquals(TaskStatus.UNKNOWN, task.getStatus());
        assertEquals(Integer.valueOf(1), task.getAttempts());
        assertEquals(1, callCount.get());
    }

    @Test
    public void shouldIsolateFailureInBatchDispatch() {
        // goodAgent 和 badAgent 分别注册，用 runnerFactory.create 的第二个参数（agentName）区分行为
        BaseAgent goodAgent = fakeAgent("goodAgent");
        BaseAgent badAgent = fakeAgent("badAgent");
        agentCatalog.register("parent-agent", Map.of(
                "goodAgent", goodAgent,
                "badAgent", badAgent
        ));

        when(runnerFactory.create(org.mockito.ArgumentMatchers.eq(goodAgent), anyString(), anyList()))
                .thenReturn(successRunner("OK"));
        when(runnerFactory.create(org.mockito.ArgumentMatchers.eq(badAgent), anyString(), anyList()))
                .thenReturn(behaviorRunner(() -> Flowable.error(new RuntimeException("bad 挂了"))));

        DynamicTask good = DynamicTask.builder().taskId("good").agentName("goodAgent").request("查询").build();
        DynamicTask bad = DynamicTask.builder().taskId("bad").agentName("badAgent").request("查询").build();
        List<DynamicTask> tasks = new ArrayList<>(List.of(good, bad));

        Map<String, Object> result = dispatchService.dispatch(context, tasks);

        assertEquals(Boolean.FALSE, result.get("allSucceeded"));
        assertEquals(TaskStatus.COMPLETED, good.getStatus());
        assertEquals("OK", good.getResult());
        assertEquals(TaskStatus.FAILED, bad.getStatus());
        assertTrue(bad.getError().contains("bad 挂了"));
    }
}
