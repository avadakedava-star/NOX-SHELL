package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.dto.ChatRequestDTO;
import cn.bugstack.ai.cases.IAIAgentReActServiceCase;
import cn.bugstack.ai.domain.agent.service.IChatService;
import cn.bugstack.ai.domain.agent.service.audit.*;
import cn.bugstack.ai.domain.agent.adapter.repository.IToolExecutionRepository;
import cn.bugstack.ai.domain.agent.service.execution.RunFailureSummaryService;
import cn.bugstack.ai.domain.agent.service.event.AgentEventPublisher;
import com.google.adk.events.Event;
import com.google.genai.types.*;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class ChatExecutionModeTest {
    private void field(Object target, String name, Object value) throws Exception {
        var f=AgentServiceController.class.getDeclaredField(name);f.setAccessible(true);f.set(target,value);
    }
    private void checkMode(String mode, boolean enhanced, boolean valid) throws Exception {
        var emitter=new AgentStreamLifecycleTest.Emitter();
        var controller=new AgentServiceController(){@Override protected org.springframework.web.servlet.mvc.method.annotation.SseEmitter createSseEmitter(long timeout){return emitter;}};
        var chat=mock(IChatService.class);var cases=mock(IAIAgentReActServiceCase.class);
        when(chat.createSession("a","u")).thenReturn("created-session");
        var answer=Event.builder().author("root").invocationId("i")
                .content(Content.fromParts(Part.fromText("完成"))).turnComplete(true).build();
        when(chat.handleMessageStream(anyString(),anyString(),anyString(),anyString(),any())).thenReturn(Flowable.just(answer));
        when(cases.executeEvents(any(),any())).thenAnswer(invocation -> {
            ChatRequestDTO request=invocation.getArgument(0);
            ToolExecutionAudit.Subscription subscription=invocation.getArgument(1);
            assertEquals("created-session",request.getSessionId());
            subscription.guard().checkActive();
            return Flowable.just(answer);
        });
        var repository=new IToolExecutionRepository(){public void save(ToolExecutionRecord r){}public List<ToolExecutionRecord> findBySession(String u,String s){return List.of();}};
        field(controller,"chatService",chat);field(controller,"reactServiceCase",cases);
        field(controller,"failureSummaryService",mock(RunFailureSummaryService.class));
        field(controller,"toolExecutionAudit",new ToolExecutionAudit(repository));field(controller,"agentEventPublisher",new AgentEventPublisher());
        var request=new ChatRequestDTO();request.setAgentId("a");request.setUserId("u");request.setMessage("检查");request.setExecutionMode(mode);
        controller.chatStream(request);
        assertTrue(emitter.completed.await(3,TimeUnit.SECONDS));
        assertEquals(1,emitter.frames.stream().filter(x->x.contains("\"event\":\"run_finished\"")).count());
        if (!valid) {
            verifyNoInteractions(cases);verify(chat,never()).createSession(anyString(),anyString());
            assertTrue(emitter.frames.stream().anyMatch(x->x.contains("INITIALIZATION_ERROR")));
        } else if (enhanced) {
            verify(cases).executeEvents(same(request),any());
            verify(chat,never()).handleMessageStream(anyString(),anyString(),anyString(),anyString(),any());
        } else {
            verify(chat).handleMessageStream("a","u","created-session","检查",null);verifyNoInteractions(cases);
        }
    }
    @Test public void omittedModeKeepsStandardRoute() throws Exception {checkMode(null,false,true);}
    @Test public void explicitStandardKeepsStandardRoute() throws Exception {checkMode("standard",false,true);}
    @Test public void enhancedUsesCaseAndSharedLifecycle() throws Exception {checkMode("enhanced",true,true);}
    @Test public void unknownModeDoesNotExecuteAnything() throws Exception {checkMode("wrong",false,false);}
}
