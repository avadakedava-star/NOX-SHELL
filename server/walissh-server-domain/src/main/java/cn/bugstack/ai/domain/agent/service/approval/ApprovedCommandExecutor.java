package cn.bugstack.ai.domain.agent.service.approval;

import cn.bugstack.ai.domain.agent.service.audit.*;
import cn.bugstack.ai.domain.agent.service.execution.*;
import cn.bugstack.ai.domain.ssh.service.ISshTerminalService;
import cn.bugstack.ai.domain.ssh.model.valobj.CommandExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class ApprovedCommandExecutor {
    private final CommandApprovalService approvals;
    private final ISshTerminalService terminals;
    private final ToolExecutionAudit audit;
    private final ExecutionPolicyProperties policy;
    private final int commandTimeoutMs;
    public ApprovedCommandExecutor(CommandApprovalService approvals,ISshTerminalService terminals,ToolExecutionAudit audit,ExecutionPolicyProperties policy) {
        this(approvals,terminals,audit,policy,300000);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public ApprovedCommandExecutor(CommandApprovalService approvals,ISshTerminalService terminals,ToolExecutionAudit audit,ExecutionPolicyProperties policy,
            @org.springframework.beans.factory.annotation.Value("${walissh.approval.command-timeout-ms:300000}") int commandTimeoutMs) {
        if(commandTimeoutMs<1000 || commandTimeoutMs>600000) throw new IllegalArgumentException("审批操作超时应在 1 秒到 10 分钟之间");
        this.approvals=approvals;this.terminals=terminals;this.audit=audit;this.policy=policy;this.commandTimeoutMs=commandTimeoutMs;
    }
    public Map<String,Object> decide(String id,String user,String session,boolean approve) {
        var a=approvals.claim(id,user,session,approve);
        if(!approve) return a.view();
        var guard=new RequestExecutionGuard(policy,System.currentTimeMillis()+commandTimeoutMs+5000L);
        var context=new ToolAuditContext("approval-"+id,user,session,"approval-"+id,"userApprovedChange",a.terminalSessionId(),null,null,guard);
        ToolExecutionRecord record;
        try { record=audit.started(context,"approvedChange",Map.of("approvalId",id,"command",a.command(),"connectionId",a.connectionId())); }
        catch(RuntimeException e) { approvals.finish(a,"FAILED","审计开始失败，命令未发送"); throw e; }
        CommandExecutionResult result;
        try { result=terminals.executeStructured(a.terminalSessionId(),a.executionCommand(),commandTimeoutMs); }
        catch(RuntimeException e) { result=CommandExecutionResult.unknown("","执行异常，请核查服务器，禁止直接重做","EXECUTION_EXCEPTION"); }
        if(result==null) result=CommandExecutionResult.unknown("","执行结果丢失，请核查服务器","EXECUTION_RESULT_MISSING");
        var toolResult=result.toToolResult(a.command());
        toolResult.put("approvalId",id); audit.returned(record,toolResult);
        String encoded;
        try { encoded=new ObjectMapper().writeValueAsString(toolResult); } catch(Exception e) {throw new IllegalStateException("执行已发生，但结果编码失败，请核查服务器",e);}
        String state=result.status().equals("SUCCEEDED") ? "SUCCEEDED" : result.status().equals("UNKNOWN") ? "UNKNOWN" : "FAILED";
        return approvals.finish(a,state,encoded).view();
    }
}
