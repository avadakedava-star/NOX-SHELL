package cn.bugstack.ai.test.infrastructure;

import cn.bugstack.ai.infrastructure.adapter.port.SshExecCommandRunner;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.Session;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class SshExecCommandRunnerTest {
    private ChannelExec channel(String stdout,String stderr,int code,boolean closed)throws Exception{
        var channel=mock(ChannelExec.class);when(channel.getInputStream()).thenReturn(new ByteArrayInputStream(stdout.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        when(channel.getExtInputStream()).thenReturn(new ByteArrayInputStream(stderr.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        when(channel.isClosed()).thenReturn(closed);when(channel.getExitStatus()).thenReturn(code);return channel;
    }
    private Session session(){var session=mock(Session.class);when(session.isConnected()).thenReturn(true);return session;}
    private SshExecCommandRunner runner(ChannelExec channel){return new SshExecCommandRunner(){@Override protected ChannelExec open(Session session){return channel;}};}
    @Test public void capturesActualExitCodeAndBothOutputStreams()throws Exception{
        var channel=channel("out","err",7,true);var result=runner(channel).execute(session(),"pwd",1000);
        assertEquals("FAILED",result.status());assertEquals(Integer.valueOf(7),result.exitCode());assertEquals("out",result.stdout());assertEquals("err",result.stderr());verify(channel).disconnect();
    }
    @Test public void successfulExitIsNotOverriddenByErrorWords()throws Exception{
        assertEquals("SUCCEEDED",runner(channel("failed count: 0","",0,true)).execute(session(),"pwd",1000).status());
    }
    @Test public void failureBeforeOpeningChannelIsSafeToRetry(){
        var runner=new SshExecCommandRunner(){@Override protected ChannelExec open(Session session)throws Exception{throw new IOException("before dispatch");}};
        var result=runner.execute(session(),"pwd",1000);assertEquals("NOT_SENT",result.status());assertFalse(result.commandDispatched());assertTrue(result.retryable());
    }
    @Test public void connectFailureIsUnknownBecauseExecMayAlreadyHaveBeenSent()throws Exception{
        var channel=channel("","",-1,false);doThrow(new com.jcraft.jsch.JSchException("lost ack")).when(channel).connect(anyInt());
        var result=runner(channel).execute(session(),"pwd",1000);assertEquals("UNKNOWN",result.status());assertTrue(result.commandDispatched());assertFalse(result.retryable());
    }
    @Test public void commandTimeoutDoesNotBecomeSuccess()throws Exception{
        var result=runner(channel("partial","",-1,false)).execute(session(),"pwd",1);assertEquals("UNKNOWN",result.status());assertEquals("COMMAND_TIMEOUT",result.failureType());
    }
    @Test public void missingExitStatusRemainsUnknown()throws Exception{
        assertEquals("UNKNOWN",runner(channel("","",-1,true)).execute(session(),"pwd",1000).status());
    }
    @Test public void outputCapPreventsUnboundedBuffering()throws Exception{
        var result=runner(channel("x".repeat(70000),"",0,true)).execute(session(),"pwd",1000);
        assertEquals("OUTPUT_LIMIT",result.failureType());assertTrue(result.stdout().length()<=65536);
    }
    @Test public void disconnectedSessionDoesNotSendCommand()throws Exception{
        var session=mock(Session.class);when(session.isConnected()).thenReturn(false);var channel=channel("","",0,true);
        var result=runner(channel).execute(session,"pwd",1000);assertEquals("NOT_SENT",result.status());verify(channel,never()).connect(anyInt());
    }
}
