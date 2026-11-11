package cn.bugstack.ai.domain.agent.service.armory.matter.tools;
import cn.bugstack.ai.domain.agent.service.task.*;
import cn.bugstack.ai.domain.agent.service.audit.*;
import com.google.adk.tools.ToolContext;
import com.google.adk.tools.Annotations.Schema;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.Supplier;
@Service
public class LongTaskAdkTool {
    private final LongTaskService tasks;private final ToolExecutionAudit audit;private final TaskPolicy policy;
    public LongTaskAdkTool(LongTaskService tasks,ToolExecutionAudit audit,TaskPolicy policy){this.tasks=tasks;this.audit=audit;this.policy=policy;}
    private ToolAuditContext context(ToolContext context){
        if(context==null)throw new IllegalArgumentException("长任务必须绑定用户会话");
        var guard=audit.guard(context);if(guard==null)throw new IllegalArgumentException("请求执行上下文已失效");guard.checkActive();
        return new ToolAuditContext(String.valueOf(context.state().get(ToolAuditContext.ROOT_REQUEST)),String.valueOf(context.state().get(ToolAuditContext.ROOT_USER)),String.valueOf(context.state().get(ToolAuditContext.ROOT_SESSION)),
            context.invocationId(),"longTask",(String)context.state().get("terminalSessionId"),context.functionCallId().orElse(null),context.invocationContext(),guard);
    }
    private Map<String,Object> result(Supplier<Map<String,Object>> action){try{return action.get();}catch(IllegalArgumentException error){return Map.of("success",false,"executionStatus","FAILED","failureType","INVALID_ARGUMENT","output",error.getMessage());}}
    private Map<String,Object> submitted(AgentTask task){return Map.of("success",true,"executionStatus","TASK_SUBMITTED","taskId",task.id,"taskStatus",task.status,"output","任务已保存并交由后台执行，不代表部署成功。立即向用户说明任务编号和当前阶段，结束本轮答复，不循环查询；页面会持续显示进度。","task",tasks.view(task));}
    public Map<String,Object> submitLongCommand(
        @Schema(name="title",description="任务的简短中文名称")String title,
        @Schema(name="command",description="可能超过30秒的安装、下载、构建命令；仍检查危险操作黑名单。以独立远端作业执行，退出码/日志会保存。不要附加睡眠轮询")String command,
        @Schema(name="timeoutSeconds",description="0或不传：按原作业状态和活动观察，不按聊天总时限杀进程。仅用户明确要求限定执行时长时填1到86400秒；该显式限制会终止远端命令",optional=true)Integer timeoutSeconds,
        @Schema(name="toolContext",optional=true)ToolContext toolContext){return result(()->submitted(tasks.submitCommand(context(toolContext),title,command,timeoutSeconds==null?0:timeoutSeconds)));}
    public Map<String,Object> deployContainer(
        @Schema(name="image",description="完整镜像名称与标签，例如 mysql:8.0")String image,
        @Schema(name="name",description="容器名称；同名已有容器会先核查配置，不删除或覆盖")String name,
        @Schema(name="hostPort",description="明确指定的主机端口；占用时会报告，不擅自换端口")int hostPort,
        @Schema(name="containerPort",description="容器内服务端口，例如MySQL为3306")int containerPort,
        @Schema(name="environment",description="用户授权的容器环境变量；不得编造密码",optional=true)Map<String,String> environment,
        @Schema(name="arguments",description="镜像名后面的参数数组，每项独立引用",optional=true)List<String> arguments,
        @Schema(name="volumes",description="数据卷映射数组，例如 mysql8-data:/var/lib/mysql；生产数据库请明确指定持久化卷",optional=true)List<String> volumes,
        @Schema(name="bindAddress",description="主机绑定IPv4地址，默认127.0.0.1；对外开放需明确传入0.0.0.0",optional=true)String bindAddress,
        @Schema(name="readinessCommand",description="容器内有界的只读连接检查命令；不要sleep；mysql镜像默认自动验证mysqladmin连接",optional=true)String readinessCommand,
        @Schema(name="toolContext",optional=true)ToolContext toolContext){return result(()->{
            String readiness=readinessCommand;String base=image.substring(image.lastIndexOf('/')+1).toLowerCase(Locale.ROOT);
            if((readiness==null || readiness.isBlank()) && (base.startsWith("mysql:") || base.startsWith("mysql@")))readiness="mysqladmin --connect-timeout=3 -h127.0.0.1 -uroot -p\"$MYSQL_ROOT_PASSWORD\" ping --silent";
            var spec=new AgentTask.ContainerSpec(image,name,bindAddress==null?"127.0.0.1":bindAddress,hostPort,containerPort,environment==null?Map.of():Map.copyOf(environment),arguments==null?List.of():List.copyOf(arguments),volumes==null?List.of():List.copyOf(volumes),readiness,policy.readinessSeconds());
            return submitted(tasks.submitContainer(context(toolContext),spec));
        });}
    public Map<String,Object> getTask(@Schema(name="taskId",description="已有任务编号，只查询不重新提交")String taskId,@Schema(name="toolContext",optional=true)ToolContext toolContext){return result(()->{var c=context(toolContext);return Map.of("success",true,"task",tasks.detail(taskId,c.userId(),c.sessionId()));});}
    public Map<String,Object> cancelTask(@Schema(name="taskId",description="仅在用户明确要求取消后台任务时调用；停止生成不等于取消任务")String taskId,@Schema(name="toolContext",optional=true)ToolContext toolContext){return result(()->{var c=context(toolContext);tasks.cancel(taskId,c.userId(),c.sessionId());return Map.of("success",true,"output","已请求取消后续步骤，正在核查远端执行状态；已有资源不会删除");});}
}
