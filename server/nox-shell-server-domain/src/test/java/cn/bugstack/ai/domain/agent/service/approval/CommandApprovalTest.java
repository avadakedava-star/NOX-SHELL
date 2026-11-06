package cn.bugstack.ai.domain.agent.service.approval;

import cn.bugstack.ai.domain.agent.adapter.repository.ICommandApprovalRepository;
import cn.bugstack.ai.domain.agent.adapter.repository.IToolExecutionRepository;
import cn.bugstack.ai.domain.agent.service.audit.*;
import cn.bugstack.ai.domain.agent.service.execution.*;
import cn.bugstack.ai.domain.ssh.service.*;
import cn.bugstack.ai.domain.ssh.model.entity.*;
import cn.bugstack.ai.domain.ssh.model.valobj.CommandExecutionResult;
import cn.bugstack.ai.domain.ssh.service.policy.CommandPolicyService;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class CommandApprovalTest {
    static class MemoryRepository implements ICommandApprovalRepository {
        final Map<String,CommandApproval> items=new ConcurrentHashMap<>();
        public void insert(CommandApproval a){items.put(a.id(),a);}
        public CommandApproval find(String id){return items.get(id);}
        public List<CommandApproval> findBySession(String u,String s){return items.values().stream().filter(a->a.userId().equals(u)&&a.sessionId().equals(s)).toList();}
        public synchronized boolean transition(CommandApproval a,String old,long now){var prior=items.get(a.id());if(prior==null||!prior.status().equals(old)||a.status().equals("RUNNING")&&prior.expiresAt()<=now)return false;items.put(a.id(),a);return true;}
    }
    final MemoryRepository repository=new MemoryRepository();
    final ISshTerminalService terminals=mock(ISshTerminalService.class);
    final ISshConnectionService connections=mock(ISshConnectionService.class);
    final Map<String,ToolExecutionRecord> records=new ConcurrentHashMap<>();
    final ToolExecutionAudit audit=new ToolExecutionAudit(new IToolExecutionRepository(){
        public void save(ToolExecutionRecord a){records.put(a.executionId(),a);}
        public List<ToolExecutionRecord> findBySession(String u,String s){return new ArrayList<>(records.values());}
    });
    final CommandApprovalService service=new CommandApprovalService(repository,terminals,connections);
    final ApprovedCommandExecutor executor=new ApprovedCommandExecutor(service,terminals,audit,ExecutionPolicyProperties.defaults());
    public CommandApprovalTest() throws Exception {
        when(terminals.getTerminalSession("terminal")).thenReturn(TerminalSessionEntity.builder().sessionId("terminal").connectionId("server").status(1).build());
        when(connections.getConnection("server")).thenReturn(SshConnectionEntity.builder().connectionId("server").host("host").port(22).username("ubuntu").build());
        when(terminals.executeStructured(anyString(),anyString(),anyLong())).thenReturn(CommandExecutionResult.completed(0,"ok",""));
        var field=ToolExecutionAudit.class.getDeclaredField("commandApprovals");field.setAccessible(true);field.set(audit,service);
    }
    ToolAuditContext context(){return new ToolAuditContext("request","user","chat","invocation","agent","terminal",null,null,
            new RequestExecutionGuard(ExecutionPolicyProperties.defaults(),System.currentTimeMillis()+60000));}
    CommandApproval pending(){var d=new CommandPolicyService().check("sudo -n systemctl restart nginx");return service.create(context(),"COMMAND","sudo -n systemctl restart nginx",d.executionCommand(),null,null,null);}
    @Test public void mutationsAndConfigToolsDoNotCreateApprovals() {
        for (String command : List.of("sudo -n systemctl restart nginx", "apt-get install -y nginx", "chmod +x /tmp/a", "echo ok > /tmp/a")) {
            var context=context(); var record=audit.started(context,"executeCommand",Map.of("command",command));
            assertEquals("RUNNING",record.status()); assertNull(context.guard().stopReason());
        }
        assertEquals("RUNNING",audit.started(context(),"updateConfigFile",Map.of("path","/tmp/app.conf","expectedContent","old","content","new")).status());
        assertTrue(repository.items.isEmpty());
    }
    @Test public void staleCommandApprovalsCannotReplayUnderNewPolicy() {
        var a=pending();
        assertThrows(IllegalArgumentException.class,()->executor.decide(a.id(),"user","chat",true));
        verify(terminals,never()).executeStructured(anyString(),anyString(),anyLong());
    }
    @Test public void rejectionNeverRunsCommandAndCannotBeApprovedLater() {
        var a=pending(); assertEquals("REJECTED",executor.decide(a.id(),"user","chat",false).get("status"));
        assertThrows(IllegalArgumentException.class,()->executor.decide(a.id(),"user","chat",true));
        verify(terminals,never()).executeStructured(anyString(),anyString(),anyLong());
    }
    @Test public void configAllowsAnyAbsoluteFileAndRetainsBackupAndCompare() {
        for (String path : List.of("/tmp/app.conf", "/etc/ssh/sshd_config", "/etc/docker/daemon.json")) {
            String execution=ConfigChangePolicy.command("id",path,"old","new");
            assertTrue(execution.contains("'/usr/bin/python3'"));
        }
        for (String path : List.of("relative.conf", "/etc/../passwd"))
            assertThrows(IllegalArgumentException.class,()->ConfigChangePolicy.command("id",path,"old","new"));
        assertThrows(IllegalArgumentException.class,()->ConfigChangePolicy.command("id","/tmp/a","same","same"));
    }
}
