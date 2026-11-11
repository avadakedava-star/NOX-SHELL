package cn.bugstack.ai.cases.react;

import cn.bugstack.ai.api.dto.*;
import cn.bugstack.ai.cases.react.node.RootNode;
import cn.bugstack.ai.cases.react.factory.DefaultReActFactory;
import cn.bugstack.ai.domain.agent.service.audit.*;
import cn.bugstack.ai.domain.agent.adapter.repository.IToolExecutionRepository;
import com.google.adk.events.Event;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class CaseEventBridgeTest {
    private ToolExecutionAudit audit() {
        return new ToolExecutionAudit(new IToolExecutionRepository(){
            public void save(ToolExecutionRecord r){}
            public List<ToolExecutionRecord> findBySession(String u,String s){return List.of();}
        });
    }
    private AIAgentReActServiceCase service(RootNode root) throws Exception {
        var service=new AIAgentReActServiceCase();
        var f=AIAgentReActServiceCase.class.getDeclaredField("rootNode");f.setAccessible(true);f.set(service,root);return service;
    }
    @Test public void enhancedEntryActuallyLoadsSavedHistory() throws Exception {
        var memory=mock(cn.bugstack.ai.domain.agent.service.ILongTermMemoryService.class);
        var saved=cn.bugstack.ai.domain.agent.model.entity.ChatMessageEntity.builder()
                .role("user").content("上次检查的是 nginx").build();
        when(memory.getRecentMessages("s",50)).thenReturn(List.of(saved));
        var root=new RootNode(){
            @Override public cn.bugstack.wrench.design.framework.tree.StrategyHandler<ChatRequestDTO,DefaultReActFactory.DynamicContext,ReActResultDTO> get(
                    ChatRequestDTO request,DefaultReActFactory.DynamicContext context) {
                return (r,c)->{
                    assertEquals("上次检查的是 nginx",c.getMessageHistory().get(0).get("content"));
                    assertEquals("继续检查",c.getMessageHistory().get(1).get("content"));
                    return c.getResult();
                };
            }
        };
        var field=RootNode.class.getDeclaredField("longTermMemoryService");field.setAccessible(true);field.set(root,memory);
        var request=new ChatRequestDTO();request.setSessionId("s");request.setUserId("u");request.setMessage("继续检查");
        try(var subscription=audit().subscribe("u","s",notification->{})) {
            service(root).executeEvents(request,subscription).test().assertComplete().assertNoErrors();
            verify(memory).getRecentMessages("s",50);
        }
    }

    @Test public void forwardsEventsWithoutClosingSharedSubscription() throws Exception {
        var root=mock(RootNode.class);var event=Event.builder().author("root").invocationId("i").build();
        try(var subscription=audit().subscribe("u","s",notification->{})) {
            when(root.apply(any(),any())).thenAnswer(invocation->{
                DefaultReActFactory.DynamicContext context=invocation.getArgument(1);
                assertSame(subscription,context.getAuditSubscription());assertNull(context.getEmitter());
                context.getEventSink().onNext(event);return new ReActResultDTO();
            });
            var events=service(root).executeEvents(new ChatRequestDTO(),subscription).toList().blockingGet();
            assertEquals(List.of(event),events);subscription.guard().checkActive();
        }
    }
    @Test public void caseFailureReachesUnifiedErrorHandler() throws Exception {
        var root=mock(RootNode.class);
        when(root.apply(any(),any())).thenAnswer(invocation->{
            DefaultReActFactory.DynamicContext context=invocation.getArgument(1);
            context.setErrorMessage("模型连接中断");return new ReActResultDTO();
        });
        try(var subscription=audit().subscribe("u","s",notification->{})) {
            service(root).executeEvents(new ChatRequestDTO(),subscription).test()
                    .assertError(error->error.getMessage().contains("模型连接中断"));
        }
    }
}
