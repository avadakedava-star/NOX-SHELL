package cn.bugstack.ai.infrastructure.adapter.port;
import cn.bugstack.ai.domain.agent.adapter.port.ILongTaskRunner;
import cn.bugstack.ai.domain.agent.service.task.AgentTask;
import cn.bugstack.ai.domain.ssh.service.ISshConnectionService;
import cn.bugstack.ai.domain.ssh.service.policy.ShellCommandAnalyzer;
import cn.bugstack.ai.domain.ssh.model.valobj.CommandExecutionResult;
import com.jcraft.jsch.*;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 独立 SSH 会话 + 远端有编号的作业，聊天关闭和本地重启不影响远端作业。 */
@Component
public class LongTaskRunner implements ILongTaskRunner {
    private final ISshConnectionService connections;
    private final Map<String,Session> sessions=new ConcurrentHashMap<>();
    public LongTaskRunner(ISshConnectionService connections){this.connections=connections;}
    private Session session(AgentTask task)throws JSchException{
        var target=connections.getConnection(task.connectionId);
        if(target==null || !Objects.equals(task.target,target.getUsername()+"@"+target.getHost()+":"+target.getPort()))throw new IllegalStateException("任务绑定的服务器或账号已变化，未继续执行");
        Session current=sessions.get(task.id);if(current!=null && current.isConnected())return current;
        var jsch=new JSch();
        if(target.getPrivateKey()!=null && !target.getPrivateKey().isBlank())jsch.addIdentity(task.id,target.getPrivateKey().getBytes(StandardCharsets.UTF_8),null,null);
        var session=jsch.getSession(target.getUsername(),target.getHost(),target.getPort());
        session.setConfig("StrictHostKeyChecking","no");session.setPassword(target.getPassword());session.setServerAliveInterval(30000);session.connect(5000);
        sessions.put(task.id,session);return session;
    }
    public CommandExecutionResult query(AgentTask t,String command,long timeout){
        try{return new SshExecCommandRunner().execute(session(t),command,timeout);}
        catch(JSchException e){return CommandExecutionResult.notSent("CONNECTION_UNAVAILABLE",true);}
    }
    private String dir(AgentTask task,String job){
        if(!task.id.matches("[a-zA-Z0-9-]+") || !job.matches("[a-zA-Z0-9-]+"))throw new IllegalArgumentException("作业编号无效");
        String suffix="/jobs/"+task.id+"/"+job;
        return "d=\"$HOME/.nox-shell-agent"+suffix+"\"; old=\"$HOME/.walissh-agent"+suffix+"\"; "
            +"if ! test -d \"$d\" && test -d \"$old\"; then d=\"$old\"; fi; ";
    }
    public Job start(AgentTask task,String job,String command,int seconds){
        String body="#!/bin/sh\nd=\"$(dirname \"$0\")\"\ntrap 'printf 143 > \"$d/exit\"; exit 143' TERM INT\n"
            +(seconds>0?"timeout -k 5 "+seconds+"s ":"")+"bash -o pipefail -c "+ShellCommandAnalyzer.shellQuote(command)+" >\"$d/output\" 2>&1\ncode=$?\nprintf '%s' \"$code\" >\"$d/exit.tmp\"\nmv \"$d/exit.tmp\" \"$d/exit\"\n";
        String encoded=Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));
        String launch=dir(task,job)+"for tool in bash timeout setsid nohup base64; do command -v $tool >/dev/null || { printf 'EXIT:127\\n'; printf '任务执行环境缺少 %s' \"$tool\"; exit 0; }; done; umask 077; mkdir -p \"$d\" || exit 2; if mkdir \"$d/claim\" 2>/dev/null; then "
            +"printf %s "+ShellCommandAnalyzer.shellQuote(encoded)+" | base64 -d >\"$d/run.sh\" || exit 2; "
            +"nohup setsid sh \"$d/run.sh\" >/dev/null 2>&1 </dev/null & printf '%s' \"$!\" >\"$d/pid\"; fi; "+statusScript();
        return decode(query(task,launch,10000));
    }
    public Job poll(AgentTask task,String job){return decode(query(task,dir(task,job)+statusScript(),10000));}
    public Job cancel(AgentTask task,String job){
        String command=dir(task,job)+"if test -f \"$d/pid\" && ! test -f \"$d/exit\"; then p=$(cat \"$d/pid\"); "
            +"if grep -aqF -- \"$d/run.sh\" /proc/$p/cmdline 2>/dev/null; then kill -TERM -- -$p 2>/dev/null || true; fi; fi; "+statusScript();
        return decode(query(task,command,10000));
    }
    private String statusScript(){return "if test -f \"$d/exit\"; then printf 'EXIT:%s\\n' \"$(cat \"$d/exit\")\"; "
        +"elif test -f \"$d/pid\" && kill -0 \"$(cat \"$d/pid\")\" 2>/dev/null && grep -aqF -- \"$d/run.sh\" /proc/$(cat \"$d/pid\")/cmdline 2>/dev/null; then printf 'RUNNING\\n'; "
        +"elif ! test -d \"$d/claim\"; then printf 'MISSING\\n'; else printf 'UNKNOWN\\n'; fi; "
        // 输出活动指标与结果分开。CPU/IO 仅说明仍有活动，不代表业务已完成。
        +"cpu=0; io=0; if test -f \"$d/pid\"; then group=$(cat \"$d/pid\"); "
        +"for p in $(ps -eo pid,pgid 2>/dev/null | awk -v g=\"$group\" '$2==g {print $1}'); do "
        +"v=$(sed 's/.*) //' /proc/$p/stat 2>/dev/null | awk '{print $12+$13}'); cpu=$((cpu+${v:-0})); "
        +"v=$(awk '/^(read_bytes|write_bytes):/ {n+=$2} END {print n+0}' /proc/$p/io 2>/dev/null); io=$((io+${v:-0})); done; fi; "
        +"printf 'ACTIVITY:%s:%s\\n' \"$cpu\" \"$io\"; test ! -f \"$d/output\" || tail -c 12000 \"$d/output\"; true";}
    private Job decode(CommandExecutionResult result){
        if(!result.status().equals("SUCCEEDED"))return new Job("UNREACHABLE",null,"暂时无法读取远端作业状态");
        String[] lines=result.stdout().split("\n",2);String status=lines[0].trim(),output=lines.length>1?lines[1]:"";
        long cpu=0,io=0;
        if(output.startsWith("ACTIVITY:")) {var parts=output.split("\n",2);var values=parts[0].split(":");
            try{cpu=Long.parseLong(values[1]);io=Long.parseLong(values[2]);}catch(RuntimeException ignored){}
            output=parts.length>1?parts[1]:"";
        }
        if(status.startsWith("EXIT:"))return new Job("EXIT",Integer.parseInt(status.substring(5)),output,cpu,io);
        return new Job(Set.of("RUNNING","MISSING","UNKNOWN").contains(status)?status:"UNKNOWN",null,output,cpu,io);
    }
    public void close(String id){var session=sessions.remove(id);if(session!=null)session.disconnect();}
    @PreDestroy public void close(){sessions.keySet().forEach(this::close);}
}
