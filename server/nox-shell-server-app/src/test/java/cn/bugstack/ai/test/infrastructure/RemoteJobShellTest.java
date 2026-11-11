package cn.bugstack.ai.test.infrastructure;
import cn.bugstack.ai.infrastructure.adapter.port.LongTaskRunner;
import cn.bugstack.ai.domain.agent.adapter.port.ILongTaskRunner;
import cn.bugstack.ai.domain.agent.service.task.AgentTask;
import cn.bugstack.ai.domain.ssh.service.ISshConnectionService;
import cn.bugstack.ai.domain.ssh.model.valobj.CommandExecutionResult;
import org.junit.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
/** 显式 opt-in 的本地 Linux shell 集成测试，不连接用户服务器。 */
public class RemoteJobShellTest {
    Local runner;AgentTask task;
    static class Local extends LongTaskRunner {
        final String home="/tmp/nox-shell-jobtest-"+UUID.randomUUID();
        Local(){super(mock(ISshConnectionService.class));}
        CommandExecutionResult shell(String command){
            try{
                var process=new ProcessBuilder("wsl.exe","-d","docker-desktop","--exec","sh","-s").start();
                process.getOutputStream().write(command.getBytes(StandardCharsets.UTF_8));process.getOutputStream().close();
                String stdout=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);String stderr=new String(process.getErrorStream().readAllBytes(),StandardCharsets.UTF_8);
                return CommandExecutionResult.completed(process.waitFor(),stdout,stderr);
            }catch(Exception e){throw new RuntimeException(e);}
        }
        @Override public CommandExecutionResult query(AgentTask task,String command,long timeout){return shell("export HOME="+home+"; export PATH="+home+"/bin:$PATH; "+command);}
    }
    @Before public void setup(){
        Assume.assumeTrue(Boolean.getBoolean("nox-shell.test.wsl"));runner=new Local();task=new AgentTask();task.id=UUID.randomUUID().toString();
        // Docker Desktop 的 BusyBox sh 支持 pipefail；只为本地测试提供 bash 名称，不修改系统目录。
        assertEquals("SUCCEEDED",runner.shell("mkdir -p "+runner.home+"/bin; printf '%s\\n' '#!/bin/sh' 'exec /bin/sh \"$@\"' > "+runner.home+"/bin/bash; chmod 700 "+runner.home+"/bin/bash").status());
    }
    ILongTaskRunner.Job await(String id)throws Exception{for(int n=0;n<40;n++){var result=runner.poll(task,id);if(result.status().equals("EXIT"))return result;Thread.sleep(100);}throw new AssertionError("作业未结束");}
    @Test public void sameJobIdRunsOnceEvenWhenStartIsRequestedAgain()throws Exception{
        runner.start(task,"COMMAND-1","printf x >> \"$HOME/once\"; sleep 1; printf done",10);
        runner.start(task,"COMMAND-1","printf x >> \"$HOME/once\"; sleep 1; printf done",10);
        var result=await("COMMAND-1");assertEquals(Integer.valueOf(0),result.exitCode());assertEquals("done",result.output());assertEquals("x",runner.query(task,"cat \"$HOME/once\"",1000).stdout());
    }
    @Test public void timeoutPersistsExitCodeAndCancellationStopsTheExecutionGroup()throws Exception{
        runner.start(task,"TIMEOUT-1","sleep 5",1);assertTrue(java.util.Set.of(124,143).contains(await("TIMEOUT-1").exitCode()));
        runner.start(task,"CANCEL-1","sleep 20; printf unexpected",30);runner.cancel(task,"CANCEL-1");
        var result=await("CANCEL-1");assertEquals(Integer.valueOf(143),result.exitCode());assertFalse(result.output().contains("unexpected"));
    }
    @Test public void adaptiveJobHasNoImplicitKillAndReportsProcessActivity()throws Exception{
        runner.start(task,"ADAPTIVE-1","while :; do :; done",0);
        Thread.sleep(250);
        var running=runner.poll(task,"ADAPTIVE-1");
        assertEquals("RUNNING",running.status());assertTrue("应采集到进程组 CPU 活动",running.cpuTicks()>0);
        assertFalse(running.output().contains("ACTIVITY:"));
        runner.cancel(task,"ADAPTIVE-1");assertEquals(Integer.valueOf(143),await("ADAPTIVE-1").exitCode());
    }
    @Test public void existingLegacyJobIsReadWithoutRunningAgain(){
        String legacy=runner.home+"/.walissh-agent/jobs/"+task.id+"/COMMAND-1";
        assertEquals("SUCCEEDED",runner.shell("mkdir -p "+legacy+"/claim; printf 0 > "+legacy+"/exit; printf legacy > "+legacy+"/output").status());
        var result=runner.start(task,"COMMAND-1","printf replayed > \"$HOME/replayed\"",10);
        assertEquals("EXIT",result.status());assertEquals("legacy",result.output());
        assertEquals("SUCCEEDED",runner.shell("test ! -e "+runner.home+"/replayed").status());
    }
}
