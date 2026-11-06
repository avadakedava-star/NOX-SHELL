package cn.bugstack.ai.trigger.http;
import cn.bugstack.ai.domain.agent.service.execution.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;
public class RunStreamControlTest {
    static class Emitter extends SseEmitter {
        final List<String> frames=new ArrayList<>();boolean completed;
        @Override public void send(SseEventBuilder event){for(var item:event.build())frames.add(String.valueOf(item.getData()));}
        @Override public void complete(){completed=true;}
    }
    private RequestExecutionGuard guard(long millis){return new RequestExecutionGuard(ExecutionPolicyProperties.defaults(),System.currentTimeMillis()+millis);}
    @Test public void terminalIsPublishedOnceAndLateEventsAreIgnored(){
        var emitter=new Emitter();var guard=guard(60000);var control=new RunStreamControl(emitter,"request-1",guard);
        control.finish("failed","MODEL_ERROR",Map.of("content","模型断开"));
        control.finish("completed","completed",Map.of());control.send(Map.of("event","text","content","late"));
        assertEquals(1,emitter.frames.stream().filter(x->x.contains("\"event\":\"run_finished\"")).count());
        assertTrue(emitter.completed);assertTrue(control.closed());assertEquals("REQUEST_FINISHED",guard.stopReason());
    }
    @Test public void totalDeadlineCancelsAnOtherwiseSilentStream(){
        var cancelled=new AtomicBoolean();var guard=guard(80);var control=new RunStreamControl(new Emitter(),"request",guard);
        var error=assertThrows(RuntimeException.class,()->control.events(Flowable.never().doOnCancel(()->cancelled.set(true))).blockingSubscribe());
        assertEquals("REQUEST_TIMEOUT",ExecutionHaltedException.find(error).reason());assertTrue(cancelled.get());
    }
    @Test public void finalSnapshotDoesNotDuplicateStreamedTextAcrossModelTurns(){
        var text=new StreamTextBuffer();text.accept("root","你",true);text.accept("root","好",true);
        text.accept("root","你好",false);assertEquals("你好",text.fullText());text.endMessage("root");
        text.accept("root","完成",true);text.accept("root","完成",false);
        assertEquals("你好\n\n完成",text.fullText());
    }
    @Test public void disconnectCancelsUpstreamWithoutSendingAnExtraFailure(){
        var emitter=new Emitter();var guard=guard(60000);var control=new RunStreamControl(emitter,"request",guard);
        var cancelled=new AtomicBoolean();var test=control.events(Flowable.never().doOnCancel(()->cancelled.set(true))).test();
        control.disconnect();test.awaitDone(1,java.util.concurrent.TimeUnit.SECONDS).assertComplete();
        assertEquals("CANCELLED",guard.stopReason());assertTrue(control.closed());assertTrue(emitter.frames.isEmpty());
    }
}
