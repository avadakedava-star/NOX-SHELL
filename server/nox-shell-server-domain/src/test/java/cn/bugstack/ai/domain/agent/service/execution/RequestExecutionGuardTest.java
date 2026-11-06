package cn.bugstack.ai.domain.agent.service.execution;

import cn.bugstack.ai.domain.ssh.model.valobj.CommandExecutionResult;
import org.junit.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class RequestExecutionGuardTest {
    private RequestExecutionGuard guard() { return new RequestExecutionGuard(ExecutionPolicyProperties.defaults(), System.currentTimeMillis()+60000); }
    private void finish(RequestExecutionGuard guard, String signature, String id, String status, String reason) {
        assertNull(guard.reserve(signature)); guard.register(id, null, "session"); guard.finished(signature, id, null, status, reason);
    }
    @Test public void blocksSameOperationAfterThreeFailuresButAllowsAnotherDiagnostic() {
        var guard = guard(); finish(guard,"A","1","REPORTED_FAILURE","COMMAND_FAILED"); finish(guard,"A","2","REPORTED_FAILURE","COMMAND_FAILED");
        finish(guard,"A","3","REPORTED_FAILURE","COMMAND_FAILED");
        assertNotNull(guard.reserve("A")); assertNull(guard.reserve("B"));
    }
    @Test public void stopsEntireRequestAfterFiveFailuresAcrossDifferentOperations() {
        var guard=guard(); for(int n=0;n<4;n++) finish(guard,"cmd"+n,"id"+n,"REPORTED_FAILURE","COMMAND_FAILED");
        guard.checkActive();
        finish(guard,"cmd4","id4","REPORTED_FAILURE","COMMAND_FAILED");
        assertEquals("CONSECUTIVE_FAILURE_LIMIT", assertThrows(ExecutionHaltedException.class,()->guard.reserve("new")).reason());
    }
    @Test public void successfulLeafResetsConsecutiveFailures() {
        var guard=guard(); finish(guard,"A","1","REPORTED_FAILURE",null); finish(guard,"B","2","RETURNED",null); finish(guard,"C","3","REPORTED_FAILURE",null);
        guard.checkActive(); assertTrue(guard.summary().contains("连续失败 1 次"));
    }
    @Test public void deterministicErrorsBlockSameCommandImmediately() {
        var guard=guard(); finish(guard,"A","1","REPORTED_FAILURE","PERMISSION_DENIED"); assertNotNull(guard.reserve("A")); assertNull(guard.reserve("B"));
    }
    @Test public void unknownResultStopsWithoutRetry() {
        var guard=guard(); finish(guard,"A","1","UNKNOWN",null);
        assertEquals("RESULT_UNKNOWN",assertThrows(ExecutionHaltedException.class,guard::checkActive).reason());
    }
    @Test public void rejectingConcurrentDuplicateDoesNotReleaseOriginalReservation() {
        var guard=guard(); assertNull(guard.reserve("A")); assertNotNull(guard.reserve("A"));
        guard.blocked("denied",null,"s"); assertNotNull(guard.reserve("A"));
    }
    @Test public void concurrentDistinctOperationsAreNotLimitedByCallCount() throws Exception {
        var guard=guard(); var pool=Executors.newFixedThreadPool(8); AtomicInteger accepted=new AtomicInteger();
        try {
            var futures=new java.util.ArrayList<Future<?>>();
            for(int n=0;n<50;n++){ final int id=n; futures.add(pool.submit(()->{try {if(guard.reserve("cmd"+id)==null) accepted.incrementAndGet();}catch(ExecutionHaltedException ignored){}})); }
            for(var future:futures)future.get(5,TimeUnit.SECONDS);
            assertEquals(50,accepted.get());
        }finally{pool.shutdownNow();}
    }
    @Test public void parentFailureDoesNotCountSameChildFailureTwice() {
        var guard=guard(); assertNull(guard.reserve("parent")); guard.register("parent",null,"p");
        assertNull(guard.reserve("child")); guard.register("child","parent","c"); guard.finished("child","child","parent","REPORTED_FAILURE","COMMAND_FAILED");
        guard.finished("parent","parent",null,"REPORTED_FAILURE","CHILD_TOOL_FAILED");
        assertTrue(guard.summary().contains("连续失败 1 次")); assertEquals("REPORTED_FAILURE",guard.sessionOutcome("c"));
    }
    @Test public void expiredDeadlinePreventsAnyExecution() {
        var guard=new RequestExecutionGuard(ExecutionPolicyProperties.defaults(),System.currentTimeMillis()-1);
        assertEquals("REQUEST_TIMEOUT",assertThrows(ExecutionHaltedException.class,()->guard.reserve("x")).reason());
    }
    @Test public void onlyReadOnlyNotSentTransientFailuresCanRetryAtMostTwice() {
        var notSent=CommandExecutionResult.notSent("COMMAND_BUSY",true);
        assertTrue(ToolRetryPolicy.mayRetry(notSent,true,1,2)); assertTrue(ToolRetryPolicy.mayRetry(notSent,true,2,2));
        assertFalse(ToolRetryPolicy.mayRetry(notSent,true,3,2)); assertFalse(ToolRetryPolicy.mayRetry(notSent,false,1,2));
        assertFalse(ToolRetryPolicy.mayRetry(CommandExecutionResult.unknown("","","COMMAND_TIMEOUT"),true,1,2));
        assertFalse(ToolRetryPolicy.mayRetry(CommandExecutionResult.completed(1,"",""),true,1,2));
    }
    @Test public void successfulCallsAndRejectedRequestsDoNotHaveATotalCountLimit() {
        var guard=guard();
        for(int i=0;i<300;i++) finish(guard,"same","success-"+i,"RETURNED",null);
        assertTrue(guard.summary().contains("实际调用 300 次"));
        assertNull(guard.reserve("running"));
        for(int i=0;i<100;i++) assertNotNull(guard.reserve("running"));
        guard.checkActive();
    }
    @Test public void failuresStillStopExecutionAfterManySuccessfulCalls() {
        var guard=guard();
        for(int i=0;i<300;i++) finish(guard,"same","success-"+i,"RETURNED",null);
        for(int i=0;i<5;i++) finish(guard,"bad-"+i,"failure-"+i,"REPORTED_FAILURE","COMMAND_FAILED");
        assertEquals("CONSECUTIVE_FAILURE_LIMIT",assertThrows(ExecutionHaltedException.class,guard::checkActive).reason());
    }
    @Test public void rewriteBudgetIsRequestWideAndDoesNotConsumeExecutionFailureBudget() {
        var guard=guard(); assertTrue(guard.allowPolicyRewrite()); assertEquals(1,guard.policyRewritesRemaining());
        guard.blocked("rewrite-1",null,"s",true);
        assertNull(guard.sessionOutcome("s")); assertTrue(guard.summary().contains("连续失败 0 次"));
        assertTrue(guard.allowPolicyRewrite()); guard.checkActive();
        assertFalse(guard.allowPolicyRewrite());
        assertEquals("POLICY_REWRITE_LIMIT",assertThrows(ExecutionHaltedException.class,guard::checkActive).reason());
    }
    @Test public void localQueueContentionDoesNotConsumeRemoteFailureBudget() {
        var guard=guard();
        for(int i=0;i<10;i++) finish(guard,"busy","busy-"+i,"REPORTED_FAILURE", i%2==0 ? "COMMAND_BUSY" : "COMMAND_QUEUE_TIMEOUT");
        guard.checkActive(); assertNull(guard.reserve("busy"));
        assertTrue(guard.summary().contains("连续失败 0 次"));
    }
    @Test public void lateParallelSuccessDoesNotEraseLatchedFailureCount() {
        var guard=guard(); assertNull(guard.reserve("running")); guard.register("running",null,"session");
        for(int i=0;i<5;i++) finish(guard,"bad"+i,"bad"+i,"REPORTED_FAILURE","COMMAND_FAILED");
        guard.finished("running","running",null,"RETURNED",null);
        assertEquals("CONSECUTIVE_FAILURE_LIMIT",guard.stopReason());
        assertTrue(guard.summary().contains("连续失败 5 次"));
    }
}
