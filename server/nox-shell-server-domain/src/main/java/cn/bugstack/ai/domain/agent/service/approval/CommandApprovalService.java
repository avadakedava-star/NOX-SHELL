package cn.bugstack.ai.domain.agent.service.approval;

import cn.bugstack.ai.domain.agent.adapter.repository.ICommandApprovalRepository;
import cn.bugstack.ai.domain.agent.service.audit.ToolAuditContext;
import cn.bugstack.ai.domain.ssh.service.ISshTerminalService;
import cn.bugstack.ai.domain.ssh.service.ISshConnectionService;
import cn.bugstack.ai.domain.ssh.service.policy.CommandPolicyService;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class CommandApprovalService {
    private final ICommandApprovalRepository repository;
    private final ISshTerminalService terminals;
    private final ISshConnectionService connections;
    public CommandApprovalService(ICommandApprovalRepository repository,ISshTerminalService terminals,ISshConnectionService connections) {
        this.repository=repository;this.terminals=terminals;this.connections=connections;
    }
    public CommandApproval create(ToolAuditContext context,String kind,String command,String execution,String path,String expected,String content) {
        var terminal=terminals.getTerminalSession(context.terminalSessionId());
        if(terminal==null || !terminal.isActive()) throw new IllegalArgumentException("审批需要已连接的 SSH 终端");
        var connection=connections.getConnection(terminal.getConnectionId());
        if(connection==null) throw new IllegalArgumentException("审批目标连接不存在");
        String id=UUID.randomUUID().toString();
        if(kind.equals("CONFIG")) execution=ConfigChangePolicy.command(id,path,expected,content);
        if(execution==null || execution.isBlank()) throw new IllegalArgumentException("此操作没有可审批的执行方案");
        long now=System.currentTimeMillis();
        var approval=new CommandApproval(id,context.userId(),context.sessionId(),context.terminalSessionId(),terminal.getConnectionId(),
                connection.getHost()+":"+connection.getPort(),connection.getUsername(),kind,command,execution,path,expected,content,now,now+600000,"PENDING",null);
        repository.insert(approval); return approval;
    }
    public CommandApproval owned(String id,String user,String session) {
        var a=repository.find(id);
        if(a==null || !Objects.equals(a.userId(),user) || !Objects.equals(a.sessionId(),session)) throw new IllegalArgumentException("审批记录不存在或不属于当前用户会话");
        if(a.status().equals("PENDING") && a.expiresAt()<=System.currentTimeMillis()) {
            repository.transition(a.state("EXPIRED",null),"PENDING",System.currentTimeMillis()); a=repository.find(id);
        }
        return a;
    }
    public List<Map<String,Object>> list(String user,String session) {
        return repository.findBySession(user,session).stream().map(a->owned(a.id(),user,session).view()).toList();
    }
    public CommandApproval claim(String id,String user,String session,boolean approve) {
        var a=owned(id,user,session);
        if(!a.status().equals("PENDING")) throw new IllegalArgumentException("审批已处理或过期，禁止重复执行；执行中/结果未知的操作请核查服务器");
        if(approve) {
            var terminal=terminals.getTerminalSession(a.terminalSessionId());
            if(terminal==null || !terminal.isActive() || !Objects.equals(terminal.getConnectionId(),a.connectionId()))
                throw new IllegalArgumentException("原审批绑定的 SSH 终端已失效，请重新生成操作方案");
            var target=connections.getConnection(a.connectionId());
            if(target==null || !Objects.equals(target.getHost()+":"+target.getPort(),a.host()) || !Objects.equals(target.getUsername(),a.username()))
                throw new IllegalArgumentException("服务器或登录账号已变化，请重新生成方案");
            String canonical;
            if(a.kind().equals("CONFIG")) canonical=ConfigChangePolicy.command(a.id(),a.path(),a.expectedContent(),a.content());
            else {
                var decision=new CommandPolicyService().check(a.command());
                if(!decision.decision().equals("REQUIRE_APPROVAL")) throw new IllegalArgumentException("该操作已不符合审批策略，请重新生成方案");
                canonical=decision.executionCommand();
            }
            if(!Objects.equals(canonical,a.executionCommand())) throw new IllegalArgumentException("审批方案与当前策略不一致，请重新生成");
        }
        var next=a.state(approve ? "RUNNING":"REJECTED",null);
        if(!repository.transition(next,"PENDING",System.currentTimeMillis())) throw new IllegalArgumentException("审批已被处理或已过期，禁止重复执行");
        return next;
    }
    public CommandApproval finish(CommandApproval running,String state,String result) {
        var finished=running.state(state,result);
        if(!repository.transition(finished,"RUNNING",System.currentTimeMillis())) throw new IllegalStateException("执行结果未保存，请核查服务器；禁止重放审批");
        return finished;
    }
}
