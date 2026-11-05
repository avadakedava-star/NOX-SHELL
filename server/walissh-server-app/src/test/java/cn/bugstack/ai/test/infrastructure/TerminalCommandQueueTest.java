package cn.bugstack.ai.test.infrastructure;

import cn.bugstack.ai.infrastructure.adapter.port.*;
import cn.bugstack.ai.domain.ssh.model.valobj.CommandExecutionResult;
import com.jcraft.jsch.*;
import org.junit.Test;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class TerminalCommandQueueTest {
    private TerminalSessionPort port(SshExecCommandRunner runner) throws Exception {
        var port=new TerminalSessionPort(){ @Override protected SshExecCommandRunner commandRunner(){return runner;} };
        var shell=mock(ChannelShell.class); when(shell.isConnected()).thenReturn(true); when(shell.getSession()).thenReturn(mock(Session.class));
        var field=TerminalSessionPort.class.getDeclaredField("channels"); field.setAccessible(true);
        ((Map<String,ChannelShell>)field.get(port)).put("terminal",shell); return port;
    }
    @Test public void concurrentCommandsWaitRatherThanReturnBusyAfterOneSecond() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var secondStarted=new CountDownLatch(1); var executions=new AtomicInteger(); var secondBudget=new AtomicLong();
        var runner=new SshExecCommandRunner(){@Override public CommandExecutionResult execute(Session session,String command,long timeout){
            if(executions.incrementAndGet()==1){entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}}
            else secondBudget.set(timeout);
            return CommandExecutionResult.completed(0,command,"");
        }};
        var port=port(runner); var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->port.executeStructured("terminal","first",5000)); assertTrue(entered.await(2,TimeUnit.SECONDS));
            var second=pool.submit(()->{secondStarted.countDown();return port.executeStructured("terminal","second",5000);});
            assertTrue(secondStarted.await(2,TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,()->second.get(1300,TimeUnit.MILLISECONDS));
            assertEquals(1,executions.get()); release.countDown();
            assertEquals("SUCCEEDED",first.get(2,TimeUnit.SECONDS).status());
            assertEquals("SUCCEEDED",second.get(2,TimeUnit.SECONDS).status());
            assertEquals(2,executions.get()); assertTrue(secondBudget.get()>0 && secondBudget.get()<4000);
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test public void queueTimeoutNeverDispatchesAndDoesNotGetFreshExecutionBudget() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var executions=new AtomicInteger();
        var runner=new SshExecCommandRunner(){@Override public CommandExecutionResult execute(Session session,String command,long timeout){
            executions.incrementAndGet();entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}
            return CommandExecutionResult.completed(0,"ok","");
        }};
        var port=port(runner);var pool=Executors.newSingleThreadExecutor();
        try {
            var first=pool.submit(()->port.executeStructured("terminal","first",5000)); assertTrue(entered.await(2,TimeUnit.SECONDS));
            var queued=port.executeStructured("terminal","second",80);
            assertEquals("NOT_SENT",queued.status());assertEquals("COMMAND_QUEUE_TIMEOUT",queued.failureType());
            assertFalse(queued.commandDispatched());assertFalse(queued.retryable());assertEquals(1,executions.get());
            release.countDown();assertEquals("SUCCEEDED",first.get(2,TimeUnit.SECONDS).status());
        } finally {release.countDown();pool.shutdownNow();}
    }
}
