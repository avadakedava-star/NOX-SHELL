package cn.bugstack.ai.domain.agent.service.task;
import cn.bugstack.ai.domain.agent.adapter.port.ILongTaskRunner;
import cn.bugstack.ai.domain.ssh.service.policy.ShellCommandAnalyzer;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
@Service
public class ContainerReadinessChecker {
    public record Check(String status,String message,String output){public Check(String status,String message){this(status,message,"");}}
    private final ILongTaskRunner runner;
    public ContainerReadinessChecker(ILongTaskRunner runner){this.runner=runner;}
    public Check check(AgentTask task,JsonNode container){
        var state=container.path("State");String status=state.path("Status").asText();
        if(status.equals("exited") || status.equals("dead")){
            var logs=runner.query(task,"sudo -n docker logs --tail 20 "+ShellCommandAnalyzer.shellQuote(task.resourceId)+" 2>&1",8000);
            return new Check("FAILED","容器已经退出，应用没有正常启动",logs.stdout()+logs.stderr());
        }
        if(!state.path("Running").asBoolean())return new Check("WAITING","正在等待容器运行");
        String readiness=task.container.readinessCommand();
        if(readiness!=null && !readiness.isBlank()){
            var result=runner.query(task,"sudo -n docker exec "+ShellCommandAnalyzer.shellQuote(task.resourceId)+" sh -c "+ShellCommandAnalyzer.shellQuote(readiness),8000);
            if(result.status().equals("SUCCEEDED"))return new Check("READY","容器正在运行，应用连接检查通过");
            return new Check("WAITING","容器已运行，正在等待应用可以连接");
        }
        var health=state.path("Health").path("Status");
        if(!health.isMissingNode() && !health.asText().equals("healthy"))return new Check("WAITING","容器已运行，正在等待健康检查通过");
        return new Check("READY",health.isMissingNode()?"容器正在运行；未配置应用连接检查，不能据此确认数据库可连接":"容器运行正常，健康检查通过");
    }
}
