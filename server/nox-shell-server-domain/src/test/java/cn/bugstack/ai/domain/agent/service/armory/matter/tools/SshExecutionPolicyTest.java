package cn.bugstack.ai.domain.agent.service.armory.matter.tools;

import cn.bugstack.ai.domain.ssh.model.valobj.CommandExecutionResult;
import cn.bugstack.ai.domain.agent.service.audit.ToolAuditContext;
import cn.bugstack.ai.domain.agent.service.audit.ToolExecutionAudit;
import cn.bugstack.ai.domain.agent.service.execution.AgentPermissionMode;
import cn.bugstack.ai.domain.agent.service.execution.ExecutionPolicyProperties;
import cn.bugstack.ai.domain.agent.service.execution.RequestExecutionGuard;
import com.google.adk.tools.ToolContext;
import java.util.Map;
import cn.bugstack.ai.domain.ssh.service.ISshTerminalService;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class SshExecutionPolicyTest {
    private SshExecuteAdkTool tool(ISshTerminalService service) throws Exception {
        var tool=new SshExecuteAdkTool(); var field=SshExecuteAdkTool.class.getDeclaredField("sshTerminalService");field.setAccessible(true);field.set(tool,service);
        SshExecuteAdkTool.setCurrentTerminalSession("terminal"); return tool;
    }
    @After public void cleanup(){SshExecuteAdkTool.clearCurrentTerminalSession();}
    @Test public void connectedChatUsesSelectedModeBeforeDispatch() throws Exception {
        var service = mock(ISshTerminalService.class);
        when(service.executeStructured(anyString(), anyString(), anyLong())).thenReturn(CommandExecutionResult.completed(0, "ok", ""));
        var tool = tool(service);
        var audit = mock(ToolExecutionAudit.class);
        var field = SshExecuteAdkTool.class.getDeclaredField("toolExecutionAudit");
        field.setAccessible(true);
        field.set(tool, audit);

        var adk = mock(ToolContext.class, RETURNS_DEEP_STUBS);
        when(adk.state().get("terminalSessionId")).thenReturn("terminal");
        var guard = new RequestExecutionGuard(ExecutionPolicyProperties.defaults(), System.currentTimeMillis() + 60000);
        var context = new ToolAuditContext("request", "user", "chat", "invocation", "agent", "terminal", null, null, guard);
        when(audit.guard(adk)).thenReturn(guard);
        when(audit.toolContext(adk)).thenReturn(context);

        var mixed = tool.executeCommand("which nginx; nginx -t 2>&1; journalctl -u nginx -n 30 --no-pager", adk);
        assertEquals("REWRITE_REQUIRED", mixed.get("policyDecision"));
        assertEquals(false, mixed.get("commandDispatched"));
        verifyNoInteractions(service);

        String command = "docker start demo";
        when(audit.requestApproval(context, "COMMAND", command, command, null, null, null))
                .thenReturn(Map.of("executionStatus", "WAITING_APPROVAL", "approvalId", "approval"));
        assertEquals("WAITING_APPROVAL", tool.executeCommand(command, adk).get("executionStatus"));
        verifyNoInteractions(service);
        assertEquals(true, tool.executeCommand("docker ps", adk).get("success"));

        var direct = new RequestExecutionGuard(ExecutionPolicyProperties.defaults(), System.currentTimeMillis() + 60000,
                System::currentTimeMillis, AgentPermissionMode.DIRECT);
        when(audit.guard(adk)).thenReturn(direct);
        assertEquals(true, tool.executeCommand(command, adk).get("success"));
        verify(service, times(1)).executeStructured(eq("terminal"), eq(command), anyLong());
        verify(audit, times(1)).requestApproval(context, "COMMAND", command, command, null, null, null);
    }

    @Test public void blockedCommandNeverCallsSshService() throws Exception {
        var service=mock(ISshTerminalService.class); var tool=tool(service);
        assertEquals("BLOCKED",tool.executeCommand("sudo /bin/rm -r -f /",null).get("executionStatus"));
        verifyNoInteractions(service);
    }
    @Test public void preDispatchTransientFailureRetriesOnlyTwice() throws Exception {
        var service=mock(ISshTerminalService.class);
        when(service.executeStructured(anyString(),anyString(),anyLong())).thenReturn(CommandExecutionResult.notSent("COMMAND_BUSY",true),CommandExecutionResult.notSent("COMMAND_BUSY",true),CommandExecutionResult.completed(0,"ok",""));
        var result=tool(service).executeCommand("pwd",null);
        assertEquals(true,result.get("success"));assertEquals(3,result.get("attemptCount"));
        verify(service,times(3)).executeStructured(anyString(),anyString(),anyLong());
    }
    @Test public void retryLimitIsAppliedEvenIfConnectionNeverBecomesReady() throws Exception {
        var service=mock(ISshTerminalService.class);
        when(service.executeStructured(anyString(),anyString(),anyLong())).thenReturn(CommandExecutionResult.notSent("COMMAND_BUSY",true));
        var result=tool(service).executeCommand("pwd",null);
        assertEquals(false,result.get("success")); assertEquals(3,result.get("attemptCount"));
        verify(service,times(3)).executeStructured(anyString(),anyString(),anyLong());
    }
    @Test public void unknownRemoteResultIsNeverRetried() throws Exception {
        var service=mock(ISshTerminalService.class);
        when(service.executeStructured(anyString(),anyString(),anyLong())).thenReturn(CommandExecutionResult.unknown("partial","","COMMAND_TIMEOUT"));
        var result=tool(service).executeCommand("df -h",null);
        assertEquals("UNKNOWN",result.get("executionStatus")); assertEquals(1,result.get("attemptCount"));
        verify(service,times(1)).executeStructured(anyString(),anyString(),anyLong());
    }
    @Test public void exitCodeDeterminesSuccessInsteadOfOutputWords() throws Exception {
        var service=mock(ISshTerminalService.class);
        when(service.executeStructured(anyString(),anyString(),anyLong())).thenReturn(CommandExecutionResult.completed(0,"failed jobs: 0",""),CommandExecutionResult.completed(1,"",""));
        var tool=tool(service); assertEquals(true,tool.executeCommand("pwd",null).get("success"));
        assertEquals(false,tool.executeCommand("pwd",null).get("success"));
        verify(service,times(2)).executeStructured(anyString(),anyString(),anyLong());
    }
    @Test public void permissionFailureDoesNotEscalateOrRetry() throws Exception {
        var service=mock(ISshTerminalService.class);
        when(service.executeStructured(anyString(),anyString(),anyLong())).thenReturn(CommandExecutionResult.completed(1,"","permission denied"));
        var result=tool(service).executeCommand("cat /etc/test",null);
        assertEquals("PERMISSION_DENIED",result.get("failureType"));assertEquals(1,result.get("attemptCount"));
        verify(service).executeStructured(eq("terminal"),eq("cat /etc/test"),anyLong());
    }
    @Test public void safeCompoundQueryReachesSshWithEveryArgumentQuoted() throws Exception {
        var service=mock(ISshTerminalService.class);
        when(service.executeStructured(anyString(),anyString(),anyLong())).thenReturn(CommandExecutionResult.completed(0,"ok",""));
        assertEquals(true,tool(service).executeCommand("uname -r && df -h /",null).get("success"));
        verify(service).executeStructured(eq("terminal"),eq("uname -r && df -h /"),anyLong());
    }
    @Test public void wholeCompoundIsBlockedBeforeAnySshCommandWhenOneBranchIsDangerous() throws Exception {
        var service=mock(ISshTerminalService.class);
        assertEquals("C002",tool(service).executeCommand("pwd && rm -rf /tmp/a",null).get("ruleId"));
        verifyNoInteractions(service);
    }
    @Test public void systemctlFullStatusQueryFromUserReportIsActuallyDispatched() throws Exception {
        var service=mock(ISshTerminalService.class);
        when(service.executeStructured(anyString(),anyString(),anyLong())).thenReturn(CommandExecutionResult.completed(0,"active (running)",""));
        assertEquals(true,tool(service).executeCommand("systemctl status docker --no-pager -l",null).get("success"));
        verify(service).executeStructured(eq("terminal"),eq("systemctl status docker --no-pager -l"),anyLong());
    }
    @Test public void mutationCommandIsDispatchedUnchangedWithoutApproval() throws Exception {
        var service=mock(ISshTerminalService.class);
        when(service.executeStructured(anyString(),anyString(),anyLong())).thenReturn(CommandExecutionResult.completed(0,"ok",""));
        String command="sudo -n systemctl restart nginx && echo done > /tmp/status";
        assertEquals(true,tool(service).executeCommand(command,null).get("success"));
        verify(service).executeStructured(eq("terminal"),eq(command),anyLong());
    }
    @Test public void arbitraryConfigFileToolExecutesDirectlyWithoutApproval() throws Exception {
        var service=mock(ISshTerminalService.class);
        when(service.executeStructured(anyString(),anyString(),anyLong())).thenReturn(CommandExecutionResult.completed(0,"updated",""));
        assertEquals(true,tool(service).updateConfigFile("/tmp/app.conf","old","new",null).get("success"));
        verify(service).executeStructured(eq("terminal"),contains("'/usr/bin/python3'"),anyLong());
    }
    @Test public void mutationIsNotAutomaticallyReplayedAfterFailure() throws Exception {
        var service=mock(ISshTerminalService.class);
        when(service.executeStructured(anyString(),anyString(),anyLong())).thenReturn(CommandExecutionResult.notSent("COMMAND_BUSY",true));
        assertEquals(1,tool(service).executeCommand("sudo -n systemctl restart nginx",null).get("attemptCount"));
        verify(service,times(1)).executeStructured(anyString(),anyString(),anyLong());
    }
}
