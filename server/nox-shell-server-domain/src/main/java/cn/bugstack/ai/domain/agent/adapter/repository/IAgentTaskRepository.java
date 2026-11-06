package cn.bugstack.ai.domain.agent.adapter.repository;
import cn.bugstack.ai.domain.agent.service.task.*;
import java.util.*;
public interface IAgentTaskRepository {
    void insert(AgentTask task,List<AgentTaskStep> steps);
    AgentTask find(String id);
    List<AgentTask> list(String user,String session);
    List<String> runnable();
    void save(AgentTask task);
    List<AgentTaskStep> steps(String taskId);
    void saveStep(AgentTaskStep step);
    boolean claim(String id,String worker,long now,long until);
    void release(String id,String worker);
    void requestCancel(String id);
}
