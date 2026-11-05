package cn.bugstack.ai.domain.agent.service.task;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentTaskRepository;
import cn.bugstack.ai.domain.agent.adapter.port.ILongTaskRunner;
import cn.bugstack.ai.domain.agent.service.audit.*;
import cn.bugstack.ai.domain.ssh.service.*;
import cn.bugstack.ai.domain.ssh.service.policy.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** 一次 tick 只做一个有界动作；长命令在远端作业运行，等待不占用模型轮次。 */
@Service
public class LongTaskService {
    private final IAgentTaskRepository repository;private final ILongTaskRunner runner;private final ISshTerminalService terminals;private final ISshConnectionService connections;
    private final TaskPolicy policy;private final ToolExecutionAudit audit;private final ContainerReadinessChecker readiness;
    private final ObjectMapper mapper=new ObjectMapper();
    private final Set<String> active=ConcurrentHashMap.newKeySet();private final String worker=UUID.randomUUID().toString();
    private final ExecutorService workers=Executors.newFixedThreadPool(2,r->{var t=new Thread(r,"long-task");t.setDaemon(true);return t;});
    private final LongSupplier clock;
    // 当前本地 UI 的聊天标识为 walissh-user，连接标识为 default；不改变旧会话和连接归属。
    @org.springframework.beans.factory.annotation.Value("${walissh.tasks.local-connection-owner:default}")
    private String localConnectionOwner="default";
    @org.springframework.beans.factory.annotation.Autowired
    public LongTaskService(IAgentTaskRepository repository,ILongTaskRunner runner,ISshTerminalService terminals,ISshConnectionService connections,TaskPolicy policy,ToolExecutionAudit audit,ContainerReadinessChecker readiness){
        this(repository,runner,terminals,connections,policy,audit,readiness,System::currentTimeMillis);
    }
    public LongTaskService(IAgentTaskRepository repository,ILongTaskRunner runner,ISshTerminalService terminals,ISshConnectionService connections,TaskPolicy policy,ToolExecutionAudit audit,ContainerReadinessChecker readiness,LongSupplier clock){
        this.repository=repository;this.runner=runner;this.terminals=terminals;this.connections=connections;this.policy=policy;this.audit=audit;this.readiness=readiness;this.clock=clock;
    }
    private AgentTask task(ToolAuditContext context,String title,String kind){
        context.guard().checkActive();
        var terminal=terminals.getTerminalSession(context.terminalSessionId());
        if(terminal==null || !terminal.isActive())throw new IllegalArgumentException("请先连接目标 SSH 终端");
        var connection=connections.getConnection(terminal.getConnectionId());
        if(connection==null || !(Objects.equals(connection.getUserId(),context.userId()) || context.userId().equals("walissh-user") && Objects.equals(connection.getUserId(),localConnectionOwner)))throw new IllegalArgumentException("当前用户不能在该连接提交任务");
        var task=new AgentTask();task.id=UUID.randomUUID().toString();task.userId=context.userId();task.sessionId=context.sessionId();task.requestId=context.requestId();
        task.connectionId=connection.getConnectionId();task.target=connection.getUsername()+"@"+connection.getHost()+":"+connection.getPort();task.title=title;task.kind=kind;
        task.status="PENDING";task.stage=kind.equals("CONTAINER")?"PRECHECK":"COMMAND";task.message="任务已保存，等待执行";
        task.createdAt=clock.getAsLong();task.updatedAt=task.createdAt;task.deadline=task.createdAt+policy.taskSeconds()*1000L;return task;
    }
    public synchronized AgentTask submitCommand(ToolAuditContext context,String title,String command,int seconds){
        var decision=new CommandPolicyService().check(command);if(!decision.allowed())throw new IllegalArgumentException(decision.reason());
        var task=task(context,title,"COMMAND");task.command=command;task.commandTimeoutSeconds=seconds>0?Math.min(seconds,policy.taskSeconds()):policy.commandSeconds();
        for(var old:repository.list(task.userId,task.sessionId))if(!old.terminal() && Objects.equals(old.requestId,task.requestId) && Objects.equals(old.connectionId,task.connectionId) && Objects.equals(old.command,command))return old;
        repository.insert(task,List.of(new AgentTaskStep(task.id,"COMMAND",title)));return task;
    }
    public synchronized AgentTask submitContainer(ToolAuditContext context,AgentTask.ContainerSpec spec){
        if(!spec.name().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,62}") || !spec.image().matches("[A-Za-z0-9][A-Za-z0-9_./:@-]*"))throw new IllegalArgumentException("镜像或容器名称无效");
        if(spec.hostPort()<1 || spec.hostPort()>65535 || spec.containerPort()<1 || spec.containerPort()>65535)throw new IllegalArgumentException("端口必须在1到65535之间");
        if(!spec.bindAddress().matches("[0-9.]+"))throw new IllegalArgumentException("绑定地址必须是 IPv4 地址");
        for(var key:spec.environment().keySet())if(!key.matches("[A-Za-z_][A-Za-z0-9_]*"))throw new IllegalArgumentException("环境变量名称无效");
        if(spec.readinessCommand()!=null && !spec.readinessCommand().isBlank() && !new CommandPolicyService().check(spec.readinessCommand()).allowed())throw new IllegalArgumentException("就绪检查命令命中危险操作规则");
        var task=task(context,"部署容器 "+spec.name(),"CONTAINER");task.container=spec;
        for(var old:repository.list(task.userId,task.sessionId))if(!old.terminal() && Objects.equals(old.connectionId,task.connectionId) && old.container!=null && old.container.name().equals(spec.name())){
            if(!old.container.equals(spec))throw new IllegalArgumentException("同名容器已有不同配置的部署任务，请先查看该任务");return old;
        }
        repository.insert(task,List.of(new AgentTaskStep(task.id,"PRECHECK","检查目标和已有容器"),new AgentTaskStep(task.id,"IMAGE","准备镜像"),new AgentTaskStep(task.id,"CREATE","创建或核查容器"),new AgentTaskStep(task.id,"READY","等待启动并验证连接")));return task;
    }
    public AgentTask owned(String id,String user,String session){var task=repository.find(id);if(task==null || !Objects.equals(task.userId,user) || !Objects.equals(task.sessionId,session))throw new IllegalArgumentException("任务不存在或不属于当前会话");return task;}
    public List<Map<String,Object>> list(String user,String session){return repository.list(user,session).stream().map(task->view(task)).toList();}
    public Map<String,Object> view(AgentTask task){var value=task.view();value.put("steps",repository.steps(task.id).stream().map(AgentTaskStep::view).toList());return value;}
    public void cancel(String id,String user,String session){var task=owned(id,user,session);if(!task.terminal())repository.requestCancel(id);}
    public void resume(String id,String user,String session){var task=owned(id,user,session);if(!task.status.equals("UNKNOWN"))throw new IllegalArgumentException("只有结果待核查任务需要恢复；已完成任务不会重放");task.status="WAITING";task.message="将先查询远端作业和已有资源，不重放已启动作业";task.deadline=clock.getAsLong()+policy.taskSeconds()*1000L;repository.save(task);}
    @Scheduled(fixedDelay=2000) public void schedule(){
        for(String id:repository.runnable()){
            if(active.size()>=2)break;if(!active.add(id))continue;
            workers.submit(()->{try{tick(id);}finally{active.remove(id);}});
        }
    }
    public void tick(String id){
        long now=clock.getAsLong();if(!repository.claim(id,worker,now,now+45000))return;
        AgentTask task=null;
        try {
            task=repository.find(id);if(task==null || task.terminal())return;
            var steps=repository.steps(id);
            if(task.cancelRequested || task.status.equals("CANCELLING") || now>=task.deadline){cancelTick(task,steps,now>=task.deadline);return;}
            String stage=task.stage;
            var step=steps.stream().filter(s->s.key.equals(stage)).findFirst().orElseThrow();
            if(task.kind.equals("COMMAND")){command(task,step,task.command,task.commandTimeoutSeconds);return;}
            switch(task.stage){
                case "PRECHECK" -> precheck(task,step);
                case "IMAGE" -> image(task,step);
                case "CREATE" -> create(task,step);
                case "READY" -> ready(task,step);
                default -> throw new IllegalStateException("未知任务步骤");
            }
        }catch(ProbeUnavailable e){if(task!=null){task.status="WAITING";task.message="暂时无法连接服务器，将继续查询已有作业，不重复执行";repository.save(task);}}
        catch(Exception e){if(task!=null){task.status="FAILED";task.message="任务停止："+brief(task,e.getMessage());
            for(var step:repository.steps(id))if(step.key.equals(task.stage)){finishStep(step,1,safe(task,e.getMessage()));break;}
            repository.save(task);}}
        finally{repository.release(id,worker);if(task!=null && task.terminal())runner.close(id);}
    }
    private String q(String text){return ShellCommandAnalyzer.shellQuote(text);}
    private cn.bugstack.ai.domain.ssh.model.valobj.CommandExecutionResult query(AgentTask t,String command){
        var result=runner.query(t,command,10000);
        if(Set.of("NOT_SENT","UNKNOWN").contains(result.status()))throw new ProbeUnavailable();return result;
    }
    private JsonNode inspect(AgentTask t){
        var result=query(t,"sudo -n docker inspect "+q(t.container.name()));
        if(result.exitCode()!=null && result.exitCode()==0){try{return mapper.readTree(result.stdout()).get(0);}catch(Exception e){throw new IllegalStateException("无法解析容器检查结果",e);}}
        if((result.stderr()+result.stdout()).toLowerCase(Locale.ROOT).contains("no such"))return null;
        throw new IllegalStateException("无法查询容器："+result.stderr()+result.stdout());
    }
    private void precheck(AgentTask t,AgentTaskStep step){
        var version=query(t,"sudo -n docker version --format '{{.Server.Version}}'");
        if(!version.status().equals("SUCCEEDED"))throw new IllegalStateException("Docker 不可用或 sudo 权限不足："+version.stderr());
        var existing=inspect(t);if(existing!=null){acceptExisting(t,existing);}
        else {
            var port=query(t,"ss -H -ltn 'sport = :"+t.container.hostPort()+"'");
            if(!port.status().equals("SUCCEEDED"))throw new IllegalStateException("无法检查主机端口是否可用");
            if(!port.stdout().isBlank())throw new IllegalArgumentException("主机端口 "+t.container.hostPort()+" 已被占用，请明确指定其它端口");
        }
        finishStep(step,0,"Docker 可用，目标已检查");advance(t,"IMAGE","目标检查完成，正在准备镜像");
    }
    private void acceptExisting(AgentTask t,JsonNode existing){
        var spec=t.container;var config=existing.path("Config");
        if(!config.path("Image").asText().equals(spec.image()))throw new IllegalArgumentException("同名容器使用其它镜像，未覆盖或删除");
        var env=new HashMap<String,String>();config.path("Env").forEach(value->{String[] pair=value.asText().split("=",2);if(pair.length==2)env.put(pair[0],pair[1]);});
        if(!spec.environment().entrySet().stream().allMatch(e->Objects.equals(env.get(e.getKey()),e.getValue())))throw new IllegalArgumentException("同名容器的环境配置不同，未修改已有容器");
        var ports=existing.path("HostConfig").path("PortBindings").path(spec.containerPort()+"/tcp");boolean match=false;
        for(var binding:ports)if(binding.path("HostPort").asText().equals(String.valueOf(spec.hostPort())) && binding.path("HostIp").asText().equals(spec.bindAddress()))match=true;
        if(!match)throw new IllegalArgumentException("同名容器端口映射不同，未重新创建");
        var cmd=new ArrayList<String>();config.path("Cmd").forEach(v->cmd.add(v.asText()));
        if(!spec.arguments().isEmpty() && !cmd.equals(spec.arguments()))throw new IllegalArgumentException("同名容器启动参数不同，未覆盖");
        var mounts=new HashSet<String>();existing.path("HostConfig").path("Binds").forEach(v->mounts.add(v.asText()));
        if(!mounts.containsAll(spec.volumes()))throw new IllegalArgumentException("同名容器的数据卷配置不同，未覆盖");
        t.resourceId=existing.path("Id").asText();
    }
    private void image(AgentTask t,AgentTaskStep step){
        var exists=query(t,"sudo -n docker image inspect "+q(t.container.image()));
        if(exists.status().equals("SUCCEEDED")){finishStep(step,0,"镜像已存在");advance(t,"CREATE","镜像已就绪，正在核查容器");return;}
        var job=job(t,step,"sudo -n docker pull "+q(t.container.image()),policy.pullSeconds());
        if(job.status().equals("EXIT")){
            if(job.exitCode()==0){finishStep(step,0,job.output());advance(t,"CREATE","镜像已就绪，正在创建容器");}
            else if(step.attempts<2 && networkFailure(job.output())){step.status="PENDING";step.remoteId=null;repository.saveStep(step);t.status="WAITING";t.message="镜像下载遇到临时网络错误，将进行一次重试";repository.save(t);}
            else failJob(t,step,job,"镜像下载失败");
        }else handleJobState(t,job,"镜像正在下载");
    }
    private boolean networkFailure(String output){String s=output.toLowerCase(Locale.ROOT);return s.contains("timeout")||s.contains("connection reset")||s.contains("temporary failure")||s.contains("tls handshake");}
    private void create(AgentTask t,AgentTaskStep step){
        var existing=inspect(t);
        if(existing!=null){acceptExisting(t,existing);finishStep(step,0,"容器已存在，配置匹配，继续验证");advance(t,"READY","容器已创建，正在等待应用启动");return;}
        var spec=t.container;StringBuilder command=new StringBuilder("sudo -n docker run -d --name ").append(q(spec.name())).append(" --label ").append(q("walissh.task="+t.id)).append(" --restart unless-stopped -p ").append(q(spec.bindAddress()+":"+spec.hostPort()+":"+spec.containerPort()));
        spec.environment().forEach((k,v)->command.append(" -e ").append(q(k+"="+v)));
        spec.volumes().forEach(v->command.append(" -v ").append(q(v)));
        command.append(' ').append(q(spec.image()));spec.arguments().forEach(arg->command.append(' ').append(q(arg)));
        var job=job(t,step,command.toString(),60);
        if(job.status().equals("EXIT") && job.exitCode()!=0)failJob(t,step,job,"容器创建未完成，将保留已有资源供核查");
        else handleJobState(t,job,"容器创建已提交，正在核查实际结果");
    }
    private void ready(AgentTask t,AgentTaskStep step){
        if(step.startedAt==0){step.startedAt=clock.getAsLong();step.status="RUNNING";repository.saveStep(step);}
        var existing=inspect(t);if(existing==null){t.status="UNKNOWN";t.message="已记录的容器目前不存在，需要核查；未重新创建";repository.save(t);return;}
        if(!existing.path("Id").asText().equals(t.resourceId)){t.status="UNKNOWN";t.message="同名容器的身份发生变化，已停止检查，未操作替换后的容器";repository.save(t);return;}
        var result=readiness.check(t,existing);
        if(result.status().equals("READY")){finishStep(step,0,result.message());t.status="SUCCEEDED";t.message=result.message()+"。容器："+t.container.name()+"；端口："+t.container.bindAddress()+":"+t.container.hostPort();repository.save(t);}
        else if(result.status().equals("FAILED")){finishStep(step,1,result.message()+"\n"+result.output());t.status="FAILED";t.message=result.message()+"："+brief(t,result.output());repository.save(t);}
        else if(clock.getAsLong()-step.startedAt>=t.container.readinessTimeoutSeconds()*1000L){finishStep(step,124,result.message());t.status="FAILED";t.message="容器已创建，但在启动等待期限内未通过验证；请查看日志，不要重新创建";repository.save(t);}
        else{t.status="WAITING";t.message=result.message();repository.save(t);}
    }
    private ILongTaskRunner.Job job(AgentTask t,AgentTaskStep step,String command,int seconds){
        boolean fresh=step.remoteId==null;
        if(fresh){step.attempts++;step.remoteId=step.key+"-"+step.attempts;step.command=command;step.status="RUNNING";step.startedAt=clock.getAsLong();repository.saveStep(step);}
        var result=runner.poll(t,step.remoteId);
        // 远端原子 claim 保证同一个作业编号只启动一次；重启恢复始终先查询。
        if(result.status().equals("MISSING")) {
            if(fresh || step.key.equals("IMAGE") || new CommandPolicyService().check(step.command).readOnly())
                result=runner.start(t,step.remoteId,step.command,Math.min(seconds,Math.max(1,(int)((t.deadline-clock.getAsLong())/1000))));
            else result=new ILongTaskRunner.Job("UNKNOWN",null,"本地记录作业已经发起，但远端标记缺失，不能证明操作未执行；未重发写命令");
        }
        step.output=result.output();repository.saveStep(step);return result;
    }
    private void command(AgentTask t,AgentTaskStep step,String command,int seconds){
        var result=job(t,step,command,seconds);
        if(result.status().equals("EXIT")){finishStep(step,result.exitCode(),result.output());t.status=result.exitCode()==0?"SUCCEEDED":"FAILED";t.message=result.exitCode()==0?"长任务已完成，执行记录已保存":result.exitCode()==124?"命令超过设定时限，远端执行器已发出终止信号，请核查实际结果":"命令执行失败："+brief(t,result.output());repository.save(t);}
        else handleJobState(t,result,"命令正在后台执行，关闭聊天不会取消它");
    }
    private void handleJobState(AgentTask t,ILongTaskRunner.Job job,String progress){
        t.status=job.status().equals("UNKNOWN")?"UNKNOWN":job.status().equals("UNREACHABLE")?"WAITING":"RUNNING";
        t.message=job.status().equals("UNKNOWN")?"远端作业有执行标记，但结果无法确认；没有重发命令，请先核查":job.status().equals("UNREACHABLE")?"暂时无法读取进度，将继续查询已有作业":progress;repository.save(t);
    }
    private void failJob(AgentTask t,AgentTaskStep step,ILongTaskRunner.Job job,String message){finishStep(step,job.exitCode(),job.output());t.status="FAILED";t.message=message+"："+brief(t,job.output());repository.save(t);}
    private void advance(AgentTask t,String stage,String message){t.stage=stage;t.status="RUNNING";t.message=message;repository.save(t);}
    private void finishStep(AgentTaskStep step,Integer code,String output){step.exitCode=code;step.output=output;step.status=code!=null && code==0?"SUCCEEDED":"FAILED";step.finishedAt=clock.getAsLong();repository.saveStep(step);}
    private void cancelTick(AgentTask t,List<AgentTaskStep> steps,boolean deadline){
        var running=steps.stream().filter(step->step.status.equals("RUNNING") && step.remoteId!=null).findFirst();
        if(running.isPresent()){
            var step=running.get();var result=runner.cancel(t,step.remoteId);
            if(result.status().equals("RUNNING") || result.status().equals("UNREACHABLE")){
                t.status="CANCELLING";t.message="已请求停止远端作业，正在确认结果；已有资源不会删除";
                if(t.cancelAt==0)t.cancelAt=clock.getAsLong();
                if(clock.getAsLong()-t.cancelAt>15000){t.status="UNKNOWN";t.message="无法确认远端是否停止，请核查；不会重发命令";}repository.save(t);return;
            }
            if(result.status().equals("UNKNOWN")){t.status="UNKNOWN";t.message="无法确认远端作业结果，请核查";repository.save(t);return;}
            finishStep(step,result.exitCode(),result.output());
        }
        t.status=deadline?"FAILED":"CANCELLED";t.message=deadline?"任务超过总时限，后续步骤已停止；已有容器和数据保留":"任务后续步骤已取消；已有容器和数据保留";repository.save(t);
    }
    private String brief(AgentTask task,String output){
        String text=safe(task,output).trim();String lower=text.toLowerCase(Locale.ROOT);
        if(lower.contains("permission denied") || lower.contains("password is required"))return "执行账号权限不足，请检查授权；具体输出已保存在执行详情";
        if(lower.contains("address already in use") || lower.contains("port is already allocated"))return "主机端口已被占用，未替换已有服务";
        if(lower.contains("timeout") || lower.contains("timed out"))return "连接或下载超时，未完成当前步骤";
        if(lower.contains("unauthorized") || lower.contains("pull access denied"))return "镜像仓库拒绝访问，请检查镜像名称和仓库登录权限";
        if(lower.contains("no matching manifest") || lower.contains("manifest unknown"))return "找不到适用的镜像版本，请检查标签和服务器架构";
        if(text.isBlank())return "没有取得可用的错误说明，请查看执行详情中的退出码";
        String[] lines=text.split("\n");String last=lines[lines.length-1];return last.length()>180?last.substring(0,180)+"…（完整输出在执行详情）":last;
    }
    private String safe(AgentTask task,String value){String result=audit.safeText(value==null?"":value);if(task.container!=null)for(String secret:task.container.environment().values())if(secret.length()>=4)result=result.replace(secret,"[REDACTED]");return result.length()>1600?result.substring(result.length()-1600):result;}
    public Map<String,Object> detail(String id,String user,String session){var t=owned(id,user,session);var view=view(t);view.put("details",repository.steps(id).stream().map(step->{var value=step.view();value.put("output",safe(t,step.output));return value;}).toList());return view;}
    @PreDestroy public void shutdown(){workers.shutdownNow();}
    private static class ProbeUnavailable extends RuntimeException{}
}
