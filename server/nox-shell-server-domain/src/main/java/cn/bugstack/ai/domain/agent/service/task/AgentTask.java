package cn.bugstack.ai.domain.agent.service.task;
import java.util.*;
/** 持久化任务；公开视图不包含命令、环境变量和凭据。 */
public class AgentTask {
    public String id,userId,sessionId,requestId,connectionId,target,title,kind,status,stage,message,resourceId,command;
    public long createdAt,deadline,updatedAt,cancelAt;
    public int commandTimeoutSeconds;
    public boolean cancelRequested;
    public ContainerSpec container;
    public boolean terminal(){return Set.of("SUCCEEDED","FAILED","UNKNOWN","CANCELLED").contains(status);}
    public Map<String,Object> view(){
        var value=new LinkedHashMap<String,Object>();
        value.put("target",target);value.put("id",id);value.put("title",title);value.put("kind",kind);value.put("status",status);value.put("stage",stage);value.put("message",message);
        value.put("createdAt",createdAt);value.put("updatedAt",updatedAt);value.put("deadline",deadline);value.put("resourceId",resourceId);value.put("cancelRequested",cancelRequested);
        if(container!=null){value.put("containerName",container.name());value.put("image",container.image());value.put("port",container.bindAddress()+":"+container.hostPort()+":"+container.containerPort());}
        return value;
    }
    public record ContainerSpec(String image,String name,String bindAddress,int hostPort,int containerPort,
            Map<String,String> environment,List<String> arguments,List<String> volumes,String readinessCommand,int readinessTimeoutSeconds){}
}
