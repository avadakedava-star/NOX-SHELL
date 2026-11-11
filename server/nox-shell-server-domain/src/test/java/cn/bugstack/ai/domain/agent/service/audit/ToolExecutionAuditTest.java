package cn.bugstack.ai.domain.agent.service.audit;

import cn.bugstack.ai.domain.agent.adapter.repository.IToolExecutionRepository;
import cn.bugstack.ai.domain.agent.service.armory.matter.patch.MyMessageConverter;
import cn.bugstack.ai.domain.agent.service.armory.matter.patch.MySpringAI;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.*;
import com.google.adk.events.EventActions;
import com.google.adk.models.LlmRequest;
import com.google.adk.sessions.Session;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.*;
import io.reactivex.rxjava3.core.Single;
import org.junit.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class ToolExecutionAuditTest {
    static class MemoryRepository implements IToolExecutionRepository {
        final Map<String, ToolExecutionRecord> records = new ConcurrentHashMap<>();
        final List<ToolExecutionRecord> writes = new CopyOnWriteArrayList<>();
        @Override public void save(ToolExecutionRecord record) { writes.add(record); records.put(record.executionId(), record); }
        @Override public List<ToolExecutionRecord> findBySession(String user, String session) {
            return records.values().stream().filter(r -> r.userId().equals(user) && r.sessionId().equals(session)).toList();
        }
    }
    final MemoryRepository repository = new MemoryRepository();
    final ToolExecutionAudit audit = new ToolExecutionAudit(repository);

    @Test public void reconnectRefreshesStaleStateBeforeAsyncToolDispatch() {
        String session = "reconnect-" + UUID.randomUUID();
        cn.bugstack.ai.domain.agent.service.armory.matter.tools.SshSessionContextRegistry.bind(session, "old-terminal");
        var state = new HashMap<String, Object>(Map.of("terminalSessionId", "old-terminal"));
        try (var subscription = audit.subscribe("user", session, ignored -> {})) {
            assertEquals("old-terminal", audit.context(callback("user", session, state)).terminalSessionId());
            cn.bugstack.ai.domain.agent.service.armory.matter.tools.SshSessionContextRegistry.bind(session, "new-terminal");
            var builder = request(Map.of("executeCommand", adkTool(ctx -> Single.fromCallable(() -> {
                assertEquals("new-terminal", ctx.state().get("terminalSessionId"));
                assertEquals("new-terminal", cn.bugstack.ai.domain.agent.service.armory.matter.tools.SshSessionContextRegistry.resolve(ctx));
                return Map.<String, Object>of("success", true);
            }).subscribeOn(io.reactivex.rxjava3.schedulers.Schedulers.io()))));
            AuditedToolMap.bind(callback("user", session, state), builder, audit);
            assertEquals(true, builder.build().tools().get("executeCommand").runAsync(Map.of(), null).blockingGet().get("success"));
            assertEquals("new-terminal", repository.records.values().iterator().next().terminalSessionId());
        }
    }

    @Test public void childKeepsExplicitTerminalWhenAnotherBindingExists() {
        String childSession = "child-" + UUID.randomUUID();
        cn.bugstack.ai.domain.agent.service.armory.matter.tools.SshSessionContextRegistry.bind(childSession, "other-terminal");
        var state = new HashMap<String, Object>(Map.of("terminalSessionId", "parent-terminal",
                ToolAuditContext.PARENT_EXECUTION, "parent-execution"));
        assertEquals("parent-terminal", audit.context(callback("user", childSession, state)).terminalSessionId());
    }

    @Test public void realSubAgentDispatchReceivesReconnectedTerminal() {
        String session = "dispatch-reconnect-" + UUID.randomUUID();
        cn.bugstack.ai.domain.agent.service.armory.matter.tools.SshSessionContextRegistry.bind(session, "new-terminal");
        var child = LlmAgent.builder().name("sshDiagnosisAgent").model("test-model").build();
        var factory = org.mockito.Mockito.mock(cn.bugstack.ai.domain.agent.service.armory.matter.session.factory.CustomRunnerFactory.class);
        var childRunner = new com.google.adk.runner.Runner(null, "test", null, null, null, List.of()) {
            @Override public io.reactivex.rxjava3.core.Flowable<com.google.adk.events.Event> runAsync(
                    String user, String id, Content message, RunConfig config, Map<String, Object> state) {
                return io.reactivex.rxjava3.core.Flowable.fromCallable(() -> {
                    assertEquals("new-terminal", state.get("terminalSessionId"));
                    assertEquals(session, state.get(ToolAuditContext.ROOT_SESSION));
                    return com.google.adk.events.Event.builder().id("child-result").invocationId("child-inv")
                            .author("sshDiagnosisAgent").content(Content.fromParts(Part.fromText("connected"))).build();
                }).subscribeOn(io.reactivex.rxjava3.schedulers.Schedulers.io());
            }
        };
        org.mockito.Mockito.when(factory.create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList())).thenReturn(childRunner);
        var dispatch = new cn.bugstack.ai.domain.agent.service.armory.matter.tools.SubAgentDispatchTool(child, factory);
        try (var subscription = audit.subscribe("user", session, ignored -> {})) {
            var builder = request(Map.of(dispatch.name(), dispatch));
            AuditedToolMap.bind(callback("user", session, new HashMap<>(Map.of("terminalSessionId", "old-terminal"))), builder, audit);
            var result = builder.build().tools().get(dispatch.name()).runAsync(Map.of("request", "check connection"), null).blockingGet();
            assertEquals(true, result.get("success"));
        } finally {
            cn.bugstack.ai.domain.agent.service.armory.matter.tools.SshExecuteAdkTool.clearCurrentTerminalSession();
        }
    }

    @Test public void reconnectDoesNotChangeAnotherChatsTerminal() {
        String first = "first-" + UUID.randomUUID(), second = "second-" + UUID.randomUUID();
        cn.bugstack.ai.domain.agent.service.armory.matter.tools.SshSessionContextRegistry.bind(first, "new-first");
        cn.bugstack.ai.domain.agent.service.armory.matter.tools.SshSessionContextRegistry.bind(second, "second-terminal");
        try (var one = audit.subscribe("user", first, ignored -> {});
             var two = audit.subscribe("user", second, ignored -> {})) {
            assertEquals("new-first", audit.context(callback("user", first, new HashMap<>(Map.of("terminalSessionId", "old-first")))).terminalSessionId());
            assertEquals("second-terminal", audit.context(callback("user", second, new HashMap<>(Map.of("terminalSessionId", "second-terminal")))).terminalSessionId());
        }
    }

    private CallbackContext callback(String user, String session, Map<String, Object> state) {
        var agent = LlmAgent.builder().name("testAgent").model("test-model").build();
        var invocation = InvocationContext.builder().agent(agent).invocationId("inv-" + UUID.randomUUID())
                .sessionService(new com.google.adk.sessions.InMemorySessionService())
                .session(Session.builder(session).appName("test").userId(user).state(state).build())
                .runConfig(cn.bugstack.ai.domain.agent.service.execution.ExecutionRunConfig.builder().build()).build();
        return new CallbackContext(invocation, EventActions.builder().build());
    }

    private ToolAuditContext context(String session) { return audit.context(callback("user", session, new HashMap<>())); }

    private ToolCallback callbackTool(String name, java.util.function.Function<String, String> action) {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description("test tool").inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
            }
            @Override public String call(String arguments) { return action.apply(arguments); }
        };
    }

    private LlmRequest.Builder request(Map<String, BaseTool> tools) {
        return LlmRequest.builder().model("test-model").tools(tools)
                .contents(List.of(Content.fromParts(Part.fromText("test"))));
    }

    private BaseTool adkTool(java.util.function.Function<ToolContext, Single<Map<String, Object>>> action) {
        return new BaseTool("executeCommand", "test SSH tool") {
            @Override public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder().name(name()).description(description())
                        .parameters(Schema.builder().type(Type.Known.OBJECT).properties(Map.of()).build()).build());
            }
            @Override public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext context) { return action.apply(context); }
        };
    }

    @Test public void repeatedIdenticalCallsHaveDistinctIdsAndOnlyTwoStatesEach() {
        var tool = new AuditedToolCallback(callbackTool("read", args -> "ok"), context("session"), audit);
        tool.call("{}"); tool.call("{}");
        assertEquals(2, repository.records.size());
        assertEquals(4, repository.writes.size());
        assertEquals(repository.writes.get(0).executionId(), repository.writes.get(1).executionId());
        assertNotEquals(repository.writes.get(0).executionId(), repository.writes.get(2).executionId());
    }

    @Test public void outOfOrderSameToolCallsKeepTheirOwnArgumentsAndResults() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var tool = new AuditedToolCallback(callbackTool("read", args -> {
            if (args.equals("A")) { entered.countDown(); await(release); }
            return "result-" + args;
        }), context("session"), audit);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> tool.call("A"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            tool.call("B"); release.countDown(); assertEquals("result-A", first.get(5, TimeUnit.SECONDS));
            assertTrue(repository.records.values().stream().allMatch(r -> r.result().equals("result-" + r.arguments())));
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test public void reportedFailureAndThrownExceptionAreNotSuccess() {
        new AuditedToolCallback(callbackTool("failure", args -> "{\"success\":false}"), context("session"), audit).call("{}");
        RuntimeException expected = new IllegalStateException("broken");
        var thrown = new AuditedToolCallback(callbackTool("throw", args -> { throw expected; }), context("session"), audit);
        assertSame(expected, assertThrows(IllegalStateException.class, () -> thrown.call("{}")));
        assertEquals(Set.of("REPORTED_FAILURE", "UNKNOWN"), repository.records.values().stream().map(ToolExecutionRecord::status).collect(java.util.stream.Collectors.toSet()));
    }

    @Test public void preservesMetadataAndTwoArgumentCallbackOverload() {
        AtomicBoolean used = new AtomicBoolean();
        var metadata = ToolMetadata.builder().returnDirect(true).build();
        ToolCallback original = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return callbackTool("mcp", args -> "").getToolDefinition(); }
            @Override public ToolMetadata getToolMetadata() { return metadata; }
            @Override public String call(String args) { throw new AssertionError("wrong overload"); }
            @Override public String call(String args, org.springframework.ai.chat.model.ToolContext context) {
                assertEquals("value", context.getContext().get("key")); used.set(true); return "ok";
            }
        };
        var wrapped = new AuditedToolCallback(original, context("session"), audit);
        assertSame(metadata, wrapped.getToolMetadata());
        assertEquals("ok", wrapped.call("{}", new org.springframework.ai.chat.model.ToolContext(Map.of("key", "value"))));
        assertTrue(used.get());
    }

    @Test public void adkContextSurvivesConverterAndAsyncCompletionIsRecordedOnce() {
        AtomicReference<ToolContext> actual = new AtomicReference<>();
        var source = io.reactivex.rxjava3.subjects.SingleSubject.<Map<String, Object>>create();
        var builder = request(Map.of("executeCommand", adkTool(ctx -> { actual.set(ctx); return source; })));
        AuditedToolMap.bind(callback("user", "session", new HashMap<>(Map.of("terminalSessionId", "terminal-A"))), builder, audit);
        var request = builder.build();
        assertTrue(request.tools() instanceof AuditedToolMap);
        var observer = request.tools().get("executeCommand").runAsync(Map.of(), null).test();
        assertEquals(1, repository.writes.size());
        assertEquals("RUNNING", repository.writes.get(0).status());
        assertEquals("session", actual.get().sessionId());
        assertEquals("terminal-A", actual.get().state().get("terminalSessionId"));
        assertEquals(repository.writes.get(0).executionId(), actual.get().functionCallId().orElseThrow());
        source.onSuccess(Map.of("success", true)); observer.assertComplete();
        assertEquals(2, repository.writes.size());
        assertEquals("RETURNED", repository.writes.get(1).status());
    }

    @Test public void converterIncludesDefaultMcpAndAdkAndDoesNotDoubleAudit() {
        var builder = request(Map.of("executeCommand", adkTool(ctx -> Single.just(Map.of("success", true)))));
        AuditedToolMap.bind(callback("user", "session", new HashMap<>()), builder, audit);
        Prompt prompt = new MyMessageConverter(new ObjectMapper(), List.of(callbackTool("mcp", args -> "ok"))).toLlmPrompt(builder.build());
        var callbacks = ((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks();
        assertEquals(3, callbacks.size());
        assertTrue(callbacks.stream().anyMatch(tool->tool.getToolDefinition().name().equals("reportProgress")));
        callbacks.stream().filter(tool->!tool.getToolDefinition().name().equals("reportProgress")).forEach(tool -> tool.call("{}"));
        assertEquals(2, repository.records.size());
        assertEquals(4, repository.writes.size());
    }

    @Test public void mcpOnlyRequestAlsoGetsRequestContext() {
        var builder = request(Map.of());
        AuditedToolMap.bind(callback("user", "session", new HashMap<>()), builder, audit);
        Prompt prompt = new MyMessageConverter(new ObjectMapper(), List.of(callbackTool("mcp", args -> "ok"))).toLlmPrompt(builder.build());
        ((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks().get(0).call("{}");
        assertEquals(1, repository.records.size());
    }

    @Test public void stateChangeWithoutToolExecutionCreatesNoAuditRecord() {
        var callback = callback("user", "session", new HashMap<>(Map.of("ssh_result", "summary only")));
        var builder = request(Map.of());
        AuditedToolMap.bind(callback, builder, audit);
        new MyMessageConverter(new ObjectMapper()).toLlmPrompt(builder.build());
        assertTrue(repository.records.isEmpty());
    }

    @Test public void emptyToolOutputIsReturnedUnchanged() {
        assertEquals("", new AuditedToolCallback(callbackTool("read", args -> ""), context("session"), audit).call("{}"));
        assertEquals("RETURNED", repository.records.values().iterator().next().status());
    }

    @Test public void contextAndNotificationsStayWithTheirSession() {
        List<ToolExecutionAudit.Notification> first = new ArrayList<>(), second = new ArrayList<>();
        try (var a = audit.subscribe("user", "A", first::add); var b = audit.subscribe("user", "B", second::add)) {
            new AuditedToolCallback(callbackTool("read", args -> "ok"), context("A"), audit).call("{}");
            assertEquals(2, first.size()); assertTrue(second.isEmpty());
            assertEquals(a.requestId(), first.get(0).record().requestId());
            assertNotEquals(a.requestId(), b.requestId());
        }
    }

    @Test public void inheritedChildContextKeepsOriginalUserAndParentCall() {
        var root = callback("user", "root", new HashMap<>());
        try (var subscription = audit.subscribe("user", "root", event -> {})) {
            var rootContext = audit.context(root);
            ToolContext tool = ToolContext.builder(root.invocationContext()).functionCallId("exec-parent").build();
            var child = audit.context(callback("child-user", "child-session", ToolAuditContext.childState(tool)));
            assertEquals("user", child.userId()); assertEquals("root", child.sessionId());
            assertEquals(rootContext.requestId(), child.requestId()); assertEquals("exec-parent", child.parentExecutionId());
        }
    }

    @Test public void subscriptionsRejectOverlapAndNewRequestDoesNotReuseOldId() {
        var callback = callback("user", "session", new HashMap<>());
        String first;
        try (var a = audit.subscribe("user", "session", event -> {})) {
            assertNotNull(a.guard()); // 订阅建立即开始计时，不等待首个模型回调。
            first = audit.context(callback).requestId();
            assertThrows(IllegalStateException.class, () -> audit.subscribe("user", "session", event -> {}));
        }
        try (var b = audit.subscribe("user", "session", event -> {})) { assertNotEquals(first, audit.context(callback).requestId()); }
    }

    @Test public void nonStreamingRootRequestAlsoGetsNewIdForEachInvocation() {
        var first = callback("user", "session", new HashMap<>());
        var firstContext = audit.context(first);
        var second = callback("user", "session", new HashMap<>(first.state()));
        assertNotEquals(firstContext.requestId(), audit.context(second).requestId());
    }

    @Test public void redactsStructuredSecretsAndBoundsOutputWithoutChangingActualResult() {
        String output = "{\"password\":\"secret-value\",\"nested\":{\"api_key\":\"secret-key\"}}";
        assertEquals(output, new AuditedToolCallback(callbackTool("read", args -> output), context("session"), audit).call("{\"token\":\"hidden\"}"));
        var record = repository.records.values().iterator().next();
        assertFalse(record.arguments().contains("hidden")); assertFalse(record.result().contains("secret-value"));
        assertFalse(record.result().contains("secret-key"));
    }

    @Test public void redactsShellSecretsAndTruncatesAuditCopyOnly() throws Exception {
        String output = "x".repeat(20000);
        assertEquals(output, new AuditedToolCallback(callbackTool("read", args -> output), context("session"), audit)
                .call("{\"command\":\"client --password secret-cli --api-key=secret-key\"}"));
        var record = repository.records.values().iterator().next();
        assertFalse(record.arguments().contains("secret-cli")); assertFalse(record.arguments().contains("secret-key"));
        assertTrue(new ObjectMapper().readTree(record.arguments()).has("command"));
        assertTrue(record.result().endsWith("[已截断]")); assertTrue(record.result().length() < output.length());
    }

    @Test public void auditStartFailurePreventsExecutionButFinishFailureDoesNotRepeatTool() {
        AtomicInteger calls = new AtomicInteger();
        IToolExecutionRepository failing = new IToolExecutionRepository() {
            @Override public void save(ToolExecutionRecord record) { throw new IllegalStateException("database unavailable"); }
            @Override public List<ToolExecutionRecord> findBySession(String u, String s) { return List.of(); }
        };
        var tool = new AuditedToolCallback(callbackTool("write", args -> { calls.incrementAndGet(); return "ok"; }), context("session"), new ToolExecutionAudit(failing));
        assertThrows(IllegalStateException.class, () -> tool.call("{}")); assertEquals(0, calls.get());
        IToolExecutionRepository failFinish = new MemoryRepository() {
            @Override public void save(ToolExecutionRecord record) { if (record.finishedAt() != null) throw new IllegalStateException("database unavailable"); super.save(record); }
        };
        assertEquals("ok", new AuditedToolCallback(callbackTool("write", args -> { calls.incrementAndGet(); return "ok"; }), context("session"), new ToolExecutionAudit(failFinish)).call("{}"));
        assertEquals(1, calls.get());
    }

    /** 使用真实 OpenAiChatModel 自动工具循环，仅把 HTTP API 替换为本地响应，验证外层返回前已记录。 */
    @Test public void realSpringAiInternalLoopReportsBeforeOuterCallReturns() {
        List<ToolExecutionAudit.Notification> events = new ArrayList<>();
        AtomicBoolean finalApiReached = new AtomicBoolean();
        var api = new FakeApi(() -> {
            assertEquals(2, events.size());
            assertEquals("RETURNED", events.get(1).record().status());
            finalApiReached.set(true);
        });
        var model = OpenAiChatModel.builder().openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model("test-model").build()).build();
        var builder = request(Map.of("executeCommand", adkTool(ctx -> Single.just(Map.of("success", true)))));
        try (var subscription = audit.subscribe("user", "session", events::add)) {
            AuditedToolMap.bind(callback("user", "session", new HashMap<>()), builder, audit);
            var response = new MySpringAI(model, "test-model").generateContent(builder.build(), false).blockingFirst();
            assertTrue(finalApiReached.get());
            assertEquals(1, repository.records.size());
            assertTrue(response.content().orElseThrow().text().contains("done"));
        }
    }

    @Test public void realSpringAiDefaultMcpCallbackIsWrappedAfterOptionsMerge() {
        AtomicInteger executions = new AtomicInteger();
        var api = new FakeApi(() -> assertEquals(2, repository.writes.size()));
        var mcp = callbackTool("executeCommand", args -> { executions.incrementAndGet(); return "{\"success\":true}"; });
        var model = OpenAiChatModel.builder().openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model("test-model").toolCallbacks(List.of(mcp)).build()).build();
        var builder = request(Map.of());
        AuditedToolMap.bind(callback("user", "session", new HashMap<>()), builder, audit);
        new MySpringAI(model, "test-model").generateContent(builder.build(), false).blockingFirst();
        assertEquals(1, executions.get()); assertEquals(1, repository.records.size());
    }

    @Test public void actualAdkRunnerBeforeModelCallbackBindsToolContext() {
        AtomicBoolean executed = new AtomicBoolean();
        var api = new FakeApi(() -> assertEquals(2, repository.writes.size()));
        var model = OpenAiChatModel.builder().openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model("test-model").build()).build();
        var tool = adkTool(ctx -> {
            assertNotNull(ctx); assertEquals("runner-session", ctx.sessionId());
            assertEquals("terminal", ctx.state().get("terminalSessionId"));
            executed.set(true); return Single.just(Map.of("success", true));
        });
        var agent = LlmAgent.builder().name("runnerAgent").model(new MySpringAI(model, "test-model"))
                .tools(tool).beforeModelCallbackSync((ctx, req) -> {
                    AuditedToolMap.bind(ctx, req, audit); return Optional.empty();
                }).build();
        var runner = com.google.adk.runner.Runner.builder().agent(agent).appName("test")
                .sessionService(new com.google.adk.sessions.InMemorySessionService()).build();
        runner.runAsync("user", "runner-session", Content.fromParts(Part.fromText("test")),
                RunConfig.builder().autoCreateSession(true).build(), Map.of("terminalSessionId", "terminal")).toList().blockingGet();
        assertTrue(executed.get()); assertEquals(1, repository.records.size());
    }

    @Test public void actualSshFunctionToolReceivesContextAndDoesNotUseStaleTerminal() {
        var ssh = new cn.bugstack.ai.domain.agent.service.armory.matter.tools.SshExecuteAdkTool();
        var tool = com.google.adk.tools.FunctionTool.create(ssh, "executeCommand");
        var builder = request(Map.of("executeCommand", tool));
        AuditedToolMap.bind(callback("user", "session", new HashMap<>()), builder, audit);
        cn.bugstack.ai.domain.agent.service.armory.matter.tools.SshExecuteAdkTool.setCurrentTerminalSession("stale-terminal");
        try {
            var result = builder.build().tools().get("executeCommand").runAsync(Map.of("command", "pwd"), null).blockingGet();
            assertEquals(false, result.get("success"));
            assertTrue(result.get("output").toString().contains("未绑定"));
            assertEquals(1, repository.records.size());
            var record = repository.records.values().iterator().next();
            assertEquals("executeCommand", record.toolName()); assertTrue(record.arguments().contains("pwd"));
            assertEquals("REPORTED_FAILURE", record.status());
        } finally { cn.bugstack.ai.domain.agent.service.armory.matter.tools.SshExecuteAdkTool.clearCurrentTerminalSession(); }
    }

    @Test public void realStreamingToolLoopAlsoReportsBeforeFinalAnswer() {
        var api = new FakeApi(() -> assertEquals(2, repository.writes.size()));
        var model = OpenAiChatModel.builder().openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model("test-model").build()).build();
        var builder = request(Map.of("executeCommand", adkTool(ctx -> Single.just(Map.of("success", true)))));
        AuditedToolMap.bind(callback("user", "session", new HashMap<>()), builder, audit);
        var responses = new MySpringAI(model, model, "test-model").generateContent(builder.build(), true).toList().blockingGet();
        assertFalse(responses.isEmpty()); assertEquals(1, repository.records.size());
        assertEquals("RETURNED", repository.records.values().iterator().next().status());
    }

    @Test public void disposingAsyncToolRecordsUnknownRatherThanPretendingItStopped() {
        var builder = request(Map.of("executeCommand", adkTool(ctx -> Single.never())));
        AuditedToolMap.bind(callback("user", "session", new HashMap<>()), builder, audit);
        var observer = builder.build().tools().get("executeCommand").runAsync(Map.of(), null).test();
        observer.dispose();
        assertEquals("UNKNOWN", repository.records.values().iterator().next().status());
        assertEquals(2, repository.writes.size());
    }

    static class FakeApi extends OpenAiApi {
        private int calls;
        private final Runnable beforeFinal;
        FakeApi(Runnable beforeFinal) {
            super("https://unused.invalid", () -> "test", new LinkedMultiValueMap<>(), "/chat/completions", "/embeddings",
                    RestClient.builder().requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory())
                            .messageConverters(List.of(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(new ObjectMapper()))),
                    WebClient.builder().clientConnector((method, uri, callback) -> reactor.core.publisher.Mono.error(new AssertionError("unexpected HTTP request"))),
                    new DefaultResponseErrorHandler());
            this.beforeFinal = beforeFinal;
        }
        @Override public ResponseEntity<ChatCompletion> chatCompletionEntity(ChatCompletionRequest request, MultiValueMap<String, String> headers) {
            String json;
            if (calls++ == 0) json = """
                {"id":"first","object":"chat.completion","created":1,"model":"test-model","choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","tool_calls":[{"id":"model-call-1","type":"function","function":{"name":"executeCommand","arguments":"{}"}}]}}]}
                """;
            else {
                beforeFinal.run();
                json = """
                    {"id":"second","object":"chat.completion","created":2,"model":"test-model","choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"done"}}]}
                    """;
            }
            try { return ResponseEntity.ok(new ObjectMapper().readValue(json, ChatCompletion.class)); }
            catch (Exception error) { throw new IllegalStateException(error); }
        }
        @Override public reactor.core.publisher.Flux<ChatCompletionChunk> chatCompletionStream(ChatCompletionRequest request, MultiValueMap<String, String> headers) {
            String json;
            if (calls++ == 0) json = """
                {"id":"first","object":"chat.completion.chunk","created":1,"model":"test-model","choices":[{"index":0,"finish_reason":"tool_calls","delta":{"role":"assistant","tool_calls":[{"index":0,"id":"model-call-1","type":"function","function":{"name":"executeCommand","arguments":"{}"}}]}}]}
                """;
            else {
                beforeFinal.run();
                json = """
                    {"id":"second","object":"chat.completion.chunk","created":2,"model":"test-model","choices":[{"index":0,"finish_reason":"stop","delta":{"role":"assistant","content":"done"}}]}
                    """;
            }
            try { return reactor.core.publisher.Flux.just(new ObjectMapper().readValue(json, ChatCompletionChunk.class)); }
            catch (Exception error) { return reactor.core.publisher.Flux.error(error); }
        }
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("timeout"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }

    private static class RepeatingApi extends FakeApi {
        final AtomicInteger httpCalls=new AtomicInteger();
        private final String toolName;
        private final boolean changeArguments;
        private final int finalAt;
        private final java.util.function.IntFunction<String> commandForCall;
        RepeatingApi(String toolName, boolean changeArguments, int finalAt) {
            this(toolName, changeArguments, finalAt, count -> "rm -fr /");
        }
        RepeatingApi(String toolName, boolean changeArguments, int finalAt, java.util.function.IntFunction<String> commandForCall) {
            super(()->{});this.toolName=toolName;this.changeArguments=changeArguments;this.finalAt=finalAt;this.commandForCall=commandForCall;
        }
        @Override public ResponseEntity<ChatCompletion> chatCompletionEntity(ChatCompletionRequest request,MultiValueMap<String,String> headers) {
            int count=httpCalls.incrementAndGet();
            Map<String,Object> message;
            if(count==finalAt) message=Map.of("role","assistant","content","done");
            else {
                Map<String,Object> args=toolName.equals("executeCommand") ? Map.of("command",commandForCall.apply(count)) : Map.of("query",changeArguments ? "q"+count : "same");
                try { message=Map.of("role","assistant","tool_calls",List.of(Map.of("id","model-"+count,"type","function","function",Map.of("name",toolName,"arguments",new ObjectMapper().writeValueAsString(args))))); }
                catch(Exception error){throw new IllegalStateException(error);}
            }
            try {
                String json=new ObjectMapper().writeValueAsString(Map.of("id","response-"+count,"object","chat.completion","created",1,"model","test-model",
                        "choices",List.of(Map.of("index",0,"finish_reason",count==finalAt ? "stop":"tool_calls","message",message))));
                return ResponseEntity.ok(new ObjectMapper().readValue(json,ChatCompletion.class));
            }catch(Exception error){throw new IllegalStateException(error);}
        }
    }

    private void runGuardedLoop(ToolExecutionAudit audit,RepeatingApi api,ToolCallback tool) {
        var model=OpenAiChatModel.builder().openAiApi(api)
                .toolCallingManager(new cn.bugstack.ai.domain.agent.service.execution.GuardedToolCallingManager())
                .defaultOptions(OpenAiChatOptions.builder().model("test-model").toolCallbacks(List.of(tool)).build()).build();
        var builder=request(Map.of());AuditedToolMap.bind(callback("user","guarded-session",new HashMap<>()),builder,audit);
        new MySpringAI(model,"test-model").generateContent(builder.build(),false).toList().blockingGet();
    }

    @Test public void actualInternalLoopPausesOnRepeatedEvidenceDespiteDifferentArguments() {
        var api=new RepeatingApi("read",true,Integer.MAX_VALUE);AtomicInteger executions=new AtomicInteger();
        var error=assertThrows(RuntimeException.class,()->runGuardedLoop(audit,api,callbackTool("read",args->{executions.incrementAndGet();return "{\"success\":false}";})));
        assertEquals("PROGRESS_STALLED",cn.bugstack.ai.domain.agent.service.execution.ExecutionHaltedException.find(error).reason());
        assertEquals(9,executions.get());assertEquals(9,api.httpCalls.get());
    }

    @Test public void actualInternalLoopDoesNotExecuteSameFailedToolFourthTime() {
        var api=new RepeatingApi("read",false,5);AtomicInteger executions=new AtomicInteger();
        runGuardedLoop(audit,api,callbackTool("read",args->{executions.incrementAndGet();return "{\"success\":false}";}));
        assertEquals(3,executions.get());assertEquals(4,repository.records.size());
        assertEquals(1,repository.records.values().stream().filter(r->r.status().equals("BLOCKED")).count());
    }

    @Test public void actualInternalLoopCanCompleteFortySuccessfulToolRounds() {
        var api=new RepeatingApi("read",true,41);AtomicInteger executions=new AtomicInteger();
        runGuardedLoop(audit,api,callbackTool("read",args->{executions.incrementAndGet();return args;}));
        assertEquals(40,executions.get());assertEquals(41,api.httpCalls.get());
        assertEquals(40,repository.records.size());
        assertTrue(repository.records.values().stream().allMatch(r->r.status().equals("RETURNED")));
    }

    @Test public void repeatedDangerousCommandStopsAfterThreeRejectionsWithoutDispatch() {
        var api=new RepeatingApi("executeCommand",false,Integer.MAX_VALUE);AtomicInteger executions=new AtomicInteger();
        var error=assertThrows(RuntimeException.class,()->runGuardedLoop(audit,api,callbackTool("executeCommand",args->{executions.incrementAndGet();return "ok";})));
        assertEquals("POLICY_BLOCKED",cn.bugstack.ai.domain.agent.service.execution.ExecutionHaltedException.find(error).reason());
        assertEquals(0,executions.get());assertEquals(3,api.httpCalls.get());assertTrue(repository.records.values().stream().allMatch(r->r.status().equals("BLOCKED")));
    }

    @Test public void unknownToolExceptionStopsInsteadOfBlindRetry() {
        var api=new RepeatingApi("read",true,Integer.MAX_VALUE);AtomicInteger executions=new AtomicInteger();
        var error=assertThrows(RuntimeException.class,()->runGuardedLoop(audit,api,callbackTool("read",args->{executions.incrementAndGet();throw new IllegalStateException("lost result");})));
        assertEquals("RESULT_UNKNOWN",cn.bugstack.ai.domain.agent.service.execution.ExecutionHaltedException.find(error).reason());assertEquals(1,executions.get());assertEquals(1,api.httpCalls.get());
    }

    @Test public void ordinaryDescendantFailureIsReportedAsWarningWithoutForcingWholeTaskFailure() {
        var root=callback("user","session",new HashMap<>()); var parentContext=audit.context(root);
        var parent=audit.started(parentContext,"dispatch",Map.of());
        var tool=ToolContext.builder(root.invocationContext()).functionCallId(parent.executionId()).build();
        var child=audit.context(callback("user","child",ToolAuditContext.childState(tool)));
        var childRecord=audit.started(child,"read",Map.of());audit.returned(childRecord,Map.of("success",false));
        var returned=audit.normalizeChildResult(parent,Map.of("success",true));assertEquals(true,returned.get("success"));assertNotNull(returned.get("toolWarning"));audit.returned(parent,returned);
        assertTrue(parentContext.guard().summary().contains("连续失败 1 次"));
    }

    @Test public void streamingInternalLoopAlsoDetectsRepeatedEvidence() {
        AtomicInteger apiCalls=new AtomicInteger();
        var api=new FakeApi(()->{}) {
            @Override public reactor.core.publisher.Flux<ChatCompletionChunk> chatCompletionStream(ChatCompletionRequest request,MultiValueMap<String,String> headers) {
                int count=apiCalls.incrementAndGet();
                String json="{\"id\":\"chunk-"+count+"\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"test-model\",\"choices\":[{\"index\":0,\"finish_reason\":\"tool_calls\",\"delta\":{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"call-"+count+"\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{\\\"q\\\":"+count+"}\"}}]}}]}";
                try{return reactor.core.publisher.Flux.just(new ObjectMapper().readValue(json,ChatCompletionChunk.class));}
                catch(Exception error){return reactor.core.publisher.Flux.error(error);}
            }
        };
        var model=OpenAiChatModel.builder().openAiApi(api).toolCallingManager(new cn.bugstack.ai.domain.agent.service.execution.GuardedToolCallingManager())
                .defaultOptions(OpenAiChatOptions.builder().model("test-model").toolCallbacks(List.of(callbackTool("read",args->"{\"success\":false}"))).build()).build();
        var builder=request(Map.of());AuditedToolMap.bind(callback("user","stream-session",new HashMap<>()),builder,audit);
        var error=assertThrows(RuntimeException.class,()->new MySpringAI(model,model,"test-model").generateContent(builder.build(),true).toList().blockingGet());
        assertEquals("PROGRESS_STALLED",cn.bugstack.ai.domain.agent.service.execution.ExecutionHaltedException.find(error).reason());assertEquals(9,apiCalls.get());
    }

    @Test public void requestDeadlineEndsHangingModelStreamWithoutExecutingTools() {
        var api=new FakeApi(()->{}) {
            @Override public reactor.core.publisher.Flux<ChatCompletionChunk> chatCompletionStream(ChatCompletionRequest request,MultiValueMap<String,String> headers) {return reactor.core.publisher.Flux.never();}
        };
        var limited=new ToolExecutionAudit(repository,new cn.bugstack.ai.domain.agent.service.execution.ExecutionPolicyProperties(3,5,1,30000,2,2));
        var model=OpenAiChatModel.builder().openAiApi(api).toolCallingManager(new cn.bugstack.ai.domain.agent.service.execution.GuardedToolCallingManager())
                .defaultOptions(OpenAiChatOptions.builder().model("test-model").build()).build();
        var builder=request(Map.of());AuditedToolMap.bind(callback("user","timeout-session",new HashMap<>()),builder,limited);
        var error=assertThrows(RuntimeException.class,()->new MySpringAI(model,model,"test-model").generateContent(builder.build(),true).toList().blockingGet());
        assertEquals("PROGRESS_REVIEW_REQUIRED",cn.bugstack.ai.domain.agent.service.execution.ExecutionHaltedException.find(error).reason());assertTrue(repository.records.isEmpty());
    }

    @Test public void reconnectingSameServerDoesNotResetSameCommandFailures() throws Exception {
        var terminalService=org.mockito.Mockito.mock(cn.bugstack.ai.domain.ssh.service.ISshTerminalService.class);
        for(String terminal:List.of("terminal-A","terminal-B")) org.mockito.Mockito.when(terminalService.getTerminalSession(terminal))
                .thenReturn(cn.bugstack.ai.domain.ssh.model.entity.TerminalSessionEntity.builder().sessionId(terminal).connectionId("same-server").build());
        var field=ToolExecutionAudit.class.getDeclaredField("terminalService");field.setAccessible(true);field.set(audit,terminalService);
        var original=context("session");
        var first=new ToolAuditContext(original.requestId(),original.userId(),original.sessionId(),original.invocationId(),original.sourceAgent(),"terminal-A",null,original.invocation(),original.guard());
        var second=new ToolAuditContext(original.requestId(),original.userId(),original.sessionId(),original.invocationId(),original.sourceAgent(),"terminal-B",null,original.invocation(),original.guard());
        audit.returned(audit.started(first,"executeCommand",Map.of("command","pwd")),Map.of("success",false));
        audit.returned(audit.started(second,"executeCommand",Map.of("command","pwd")),Map.of("success",false));
        audit.returned(audit.started(second,"executeCommand",Map.of("command","pwd")),Map.of("success",false));
        assertEquals("BLOCKED",audit.started(first,"executeCommand",Map.of("command","pwd")).status());
    }

    @Test public void shellSyntaxAndMutationsReachActualToolLoopWithoutApproval() {
        var api=new RepeatingApi("executeCommand",false,4,count -> count == 1 ? "ls *.log" : count == 2 ? "sudo -n systemctl restart nginx" : "echo ok > /tmp/result");
        AtomicInteger executions=new AtomicInteger();
        runGuardedLoop(audit,api,callbackTool("executeCommand",args->{executions.incrementAndGet();return "{\"success\":true}";}));
        assertEquals(3,executions.get()); assertEquals(4,api.httpCalls.get());
        assertEquals(3,repository.records.values().stream().filter(r->r.status().equals("RETURNED")).count());
    }

    @Test public void childShellQueryDoesNotLeaveParentOrSessionFalselyFailed() {
        var root=callback("user","root-session",new HashMap<>()); var parentContext=audit.context(root);
        var parent=audit.started(parentContext,"dispatch",Map.of());
        var tool=ToolContext.builder(root.invocationContext()).functionCallId(parent.executionId()).build();
        var child=audit.context(callback("user","child-session",ToolAuditContext.childState(tool)));
        var query=audit.started(child,"executeCommand",Map.of("command","ls *.log"));
        assertEquals("RUNNING",query.status());
        audit.returned(query,Map.of("success",true));
        assertNull(child.guard().sessionOutcome("child-session")); child.guard().checkActive();
        var result=audit.normalizeChildResult(parent,Map.of("success",true));
        audit.returned(parent,result); assertEquals("RETURNED",repository.records.get(parent.executionId()).status());
        assertTrue(child.guard().summary().contains("实际调用 2 次，连续失败 0 次"));
    }

    @Test public void equivalentCompoundCommandsShareFailureBudget() {
        var context=context("session");
        for (String command : List.of("uname -r && df -h", "'uname' '-r'&&df -h", "uname  -r && 'df' '-h'"))
            audit.returned(audit.started(context,"executeCommand",Map.of("command",command)),Map.of("success",false));
        assertEquals("BLOCKED",audit.started(context,"executeCommand",Map.of("command","uname -r&&df -h")).status());
    }

    @Test public void streamingPolicyErrorIsDeliveredOnceAndKeepsStopReason() {
        var dropped=new java.util.concurrent.CopyOnWriteArrayList<Throwable>();
        io.reactivex.rxjava3.plugins.RxJavaPlugins.setErrorHandler(dropped::add);
        try {
            var api=new FakeApi(()->{}) {
                @Override public reactor.core.publisher.Flux<ChatCompletionChunk> chatCompletionStream(ChatCompletionRequest request,MultiValueMap<String,String> headers) {
                    return reactor.core.publisher.Flux.error(new cn.bugstack.ai.domain.agent.service.execution.ExecutionHaltedException("POLICY_BLOCKED","blocked"));
                }
            };
            var model=OpenAiChatModel.builder().openAiApi(api).build();
            var error=assertThrows(RuntimeException.class,()->new MySpringAI(model,model,"test-model").generateContent(request(Map.of()).build(),true).toList().blockingGet());
            assertEquals("POLICY_BLOCKED",cn.bugstack.ai.domain.agent.service.execution.ExecutionHaltedException.find(error).reason());
            assertTrue(dropped.toString(),dropped.isEmpty());
        } finally { io.reactivex.rxjava3.plugins.RxJavaPlugins.reset(); }
    }
    @Test public void dockerDiagnosticQueriesFromReportedFailureNowReachRealToolLoop() {
        var api=new RepeatingApi("executeCommand",false,3,count -> count == 1 ? "getent group docker" : "systemctl status docker --no-pager -l");
        AtomicInteger executions=new AtomicInteger();
        runGuardedLoop(audit,api,callbackTool("executeCommand",args->{executions.incrementAndGet();return "{\"success\":true}";}));
        assertEquals(2,executions.get());
        assertTrue(repository.records.values().stream().allMatch(r->r.status().equals("RETURNED")));
    }
    @Test public void systemctlOptionsArePassedToServerWithoutPermissionFiltering() {
        var api=new RepeatingApi("executeCommand",false,3,count -> count == 1 ? "systemctl status docker --unknown-option" : "systemctl status docker --no-pager -l");
        AtomicInteger executions=new AtomicInteger();
        runGuardedLoop(audit,api,callbackTool("executeCommand",args->{executions.incrementAndGet();return "{\"success\":true}";}));
        assertEquals(2,executions.get());
        assertEquals(0,repository.records.values().stream().filter(r->r.status().equals("BLOCKED")).count());
    }
    @Test public void productionRunConfigAlsoDisablesFrameworkDefaultLlmCallLimit() throws Exception {
        var invocation=callback("user","unlimited-session",new HashMap<>()).invocationContext();
        assertEquals(0,invocation.runConfig().maxLlmCalls());
        for(int i=0;i<1000;i++) invocation.incrementLlmCallsCount();
    }
}
