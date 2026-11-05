package cn.bugstack.ai.trigger.http;
import cn.bugstack.ai.api.dto.ChatRequestDTO;
import cn.bugstack.ai.domain.agent.service.IChatService;
import cn.bugstack.ai.domain.agent.service.audit.*;
import cn.bugstack.ai.domain.agent.adapter.repository.IToolExecutionRepository;
import cn.bugstack.ai.domain.agent.service.execution.*;
import cn.bugstack.ai.domain.agent.service.event.AgentEventPublisher;
import com.google.adk.events.Event;
import com.google.genai.types.*;
import io.reactivex.rxjava3.core.Flowable;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
public class AgentStreamLifecycleTest {
    static class Emitter extends SseEmitter {
        final List<String> frames=new CopyOnWriteArrayList<>();final CountDownLatch completed=new CountDownLatch(1);boolean exceptionCompletion;
        @Override public void send(SseEventBuilder event){for(var item:event.build())frames.add(String.valueOf(item.getData()));}
        @Override public void complete(){completed.countDown();}
        @Override public void completeWithError(Throwable e){exceptionCompletion=true;completed.countDown();}
    }
    private void field(Object controller,String name,Object value)throws Exception{var f=AgentServiceController.class.getDeclaredField(name);f.setAccessible(true);f.set(controller,value);}
    private Emitter run(Flowable<Event> stream,RunFailureSummaryService summary)throws Exception{
        var emitter=new Emitter();var controller=new AgentServiceController(){@Override protected SseEmitter createSseEmitter(long timeout){return emitter;}};
        var chat=mock(IChatService.class);when(chat.handleMessageStream(anyString(),anyString(),anyString(),anyString(),any())).thenReturn(stream);
        var repository=new IToolExecutionRepository(){public void save(ToolExecutionRecord r){}public List<ToolExecutionRecord> findBySession(String u,String s){return List.of();}};
        field(controller,"chatService",chat);field(controller,"failureSummaryService",summary);field(controller,"toolExecutionAudit",new ToolExecutionAudit(repository));field(controller,"agentEventPublisher",new AgentEventPublisher());
        var request=new ChatRequestDTO();request.setAgentId("a");request.setUserId("u");request.setSessionId(UUID.randomUUID().toString());request.setMessage("检查状态");
        controller.chatStream(request);assertTrue(emitter.completed.await(3,TimeUnit.SECONDS));return emitter;
    }
    @Test public void modelExceptionProducesExplanationAndExactlyOneNormalSseTermination()throws Exception{
        var summary=mock(RunFailureSummaryService.class);when(summary.summarize(anyString(),any(),any())).thenReturn("结束原因：模型连接中断；检查未完成。");
        var result=run(Flowable.error(new IllegalStateException("模型连接中断")),summary);
        assertFalse(result.exceptionCompletion);
        assertEquals(1,result.frames.stream().filter(x->x.contains("\"event\":\"run_finished\"")).count());
        assertTrue(result.frames.stream().anyMatch(x->x.contains("模型连接中断")));
        verify(summary).summarize(eq("a"),argThat(report->report.cause().contains("模型连接中断")),any());
    }
    @Test public void failedToolEventDoesNotTerminateRootBeforeItsAnswer()throws Exception{
        var summary=mock(RunFailureSummaryService.class);
        var failed=Event.builder().author("root").invocationId("invocation").content(Content.fromParts(Part.fromFunctionResponse("read",Map.of("success",false,"output","No such file")))).build();
        var answer=Event.builder().author("root").invocationId("invocation").content(Content.fromParts(Part.fromText("文件不存在，其余查询已完成"))).partial(false).turnComplete(true).build();
        var result=run(Flowable.just(failed,answer),summary);
        assertTrue(result.frames.stream().anyMatch(x->x.contains("\"status\":\"completed\"")));
        assertTrue(result.frames.stream().anyMatch(x->x.contains("文件不存在，其余查询已完成")));verifyNoInteractions(summary);
    }
}
