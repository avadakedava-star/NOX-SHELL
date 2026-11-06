package cn.bugstack.ai.domain.agent.service.task;
import java.util.*;
public class AgentTaskStep {
    public String taskId,key,title,status="PENDING",command="",output="",remoteId;
    public Integer exitCode;
    public int attempts;
    public long startedAt,finishedAt;
    public AgentTaskStep(){}
    public AgentTaskStep(String taskId,String key,String title){this.taskId=taskId;this.key=key;this.title=title;}
    public Map<String,Object> view(){var v=new LinkedHashMap<String,Object>();v.put("key",key);v.put("title",title);v.put("status",status);v.put("exitCode",exitCode);v.put("startedAt",startedAt);v.put("finishedAt",finishedAt);return v;}
}
