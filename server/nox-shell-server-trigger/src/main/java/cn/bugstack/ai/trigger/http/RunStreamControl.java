package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.domain.agent.service.execution.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.processors.BehaviorProcessor;
import io.reactivex.rxjava3.processors.FlowableProcessor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 一轮 SSE 的单一收尾入口；业务失败通过事件返回，不重复交给 Servlet 异常处理。 */
final class RunStreamControl {
    private final SseEmitter emitter;
    private final String requestId;
    private final RequestExecutionGuard guard;
    private final ObjectMapper mapper=new ObjectMapper();
    private final AtomicBoolean ended=new AtomicBoolean();
    private final FlowableProcessor<Boolean> cancelled=BehaviorProcessor.createDefault(false).toSerialized();
    RunStreamControl(SseEmitter emitter,String requestId,RequestExecutionGuard guard) {
        this.emitter=emitter;this.requestId=requestId;this.guard=guard;
        emitter.onCompletion(this::disconnect);
        emitter.onTimeout(this::disconnect);
        emitter.onError(error->disconnect());
    }
    boolean closed(){return ended.get();}
    void cancelUpstream(){cancelled.onNext(true);}
    Flowable<Boolean> cancellation(){return cancelled.filter(Boolean::booleanValue);}
    void disconnect(){if(ended.compareAndSet(false,true)){guard.halt("CANCELLED","客户端断开或停止，后续操作已取消");cancelUpstream();}}
    <T> Flowable<T> events(Flowable<T> source){
        // 独立总时限，不因收到心跳/文字而续期；到期会取消模型/工具订阅。
        Flowable<Long> deadline=Flowable.timer(guard.remainingMillis(),TimeUnit.MILLISECONDS)
                .flatMap(tick->{guard.halt("REQUEST_TIMEOUT","本轮执行超过请求时限；已发送命令的结果需核查");return Flowable.error(new ExecutionHaltedException("REQUEST_TIMEOUT",guard.summary()));});
        return Flowable.defer(()->closed() ? Flowable.<T>empty() : source).subscribeOn(io.reactivex.rxjava3.schedulers.Schedulers.io()).takeUntil(deadline).takeUntil(cancellation());
    }
    synchronized void send(Map<String,Object> payload){
        if(closed()) return;
        try {write(payload);} catch(java.io.IOException error){disconnect();}
    }
    private void write(Map<String,Object> payload) throws java.io.IOException {
        var body=new LinkedHashMap<>(payload);body.put("requestId",requestId);body.put("timestamp",System.currentTimeMillis());
        synchronized(emitter){emitter.send(SseEmitter.event().name(String.valueOf(body.get("event"))).data(mapper.writeValueAsString(body)));}
    }
    synchronized void heartbeat(){
        if(closed())return;
        try {synchronized(emitter){emitter.send(SseEmitter.event().comment("heartbeat"));}}
        catch(java.io.IOException error){disconnect();}
    }
    synchronized void finish(String status,String reason,Map<String,Object> details){
        if(!ended.compareAndSet(false,true))return;
        guard.halt("REQUEST_FINISHED","本轮已结束，禁止继续自动操作");
        cancelUpstream();
        try {var body=new LinkedHashMap<>(details);body.put("event","run_finished");body.put("status",status);body.put("stopReason",reason);write(body);}
        catch(java.io.IOException ignored) { /* 客户端已断开，执行侧仍完成清理。 */ }
        finally {emitter.complete();}
    }
}
