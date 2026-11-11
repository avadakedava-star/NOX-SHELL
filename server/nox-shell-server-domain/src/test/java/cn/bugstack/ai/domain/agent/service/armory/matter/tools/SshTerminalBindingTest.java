package cn.bugstack.ai.domain.agent.service.armory.matter.tools;

import cn.bugstack.ai.domain.agent.service.armory.matter.session.CustomAdkSessionService;
import org.junit.Test;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.Assert.*;

public class SshTerminalBindingTest {
    @Test public void nextRoundReplacesOldTerminalAndPreservesSessionState() {
        var sessions = new CustomAdkSessionService();
        String id = UUID.randomUUID().toString();
        sessions.createSession("app", "user", new ConcurrentHashMap<>(Map.of(
                "terminalSessionId", "old-terminal", "task", "keep-task")), id).blockingGet();
        SshSessionContextRegistry.bindSession(sessions, "app", "user", id, "new-terminal");
        var session = sessions.getSession("app", "user", id, Optional.empty()).blockingGet();
        assertEquals("new-terminal", session.state().get("terminalSessionId"));
        assertEquals("keep-task", session.state().get("task"));
        assertEquals("new-terminal", SshSessionContextRegistry.get(id).orElseThrow());
    }

    @Test public void coldSessionGetsTerminalBeforeRunnerStarts() {
        var sessions = new CustomAdkSessionService();
        String id = UUID.randomUUID().toString();
        SshSessionContextRegistry.bindSession(sessions, "app", "user", id, "current-terminal");
        assertEquals("current-terminal", sessions.getSession("app", "user", id, Optional.empty())
                .blockingGet().state().get("terminalSessionId"));
        SshSessionContextRegistry.bindSession(sessions, "app", "user", id, " ");
        assertEquals("current-terminal", sessions.getSession("app", "user", id, Optional.empty())
                .blockingGet().state().get("terminalSessionId"));
    }
}
