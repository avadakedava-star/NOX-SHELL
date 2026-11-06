package cn.bugstack.ai.infrastructure.adapter.repository;
import cn.bugstack.ai.domain.agent.adapter.repository.IAgentTaskRepository;
import cn.bugstack.ai.domain.agent.service.task.*;
import cn.bugstack.ai.infrastructure.dao.IAgentTaskDao;
import cn.bugstack.ai.infrastructure.security.PasswordEncryptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
@Repository
public class AgentTaskRepository implements IAgentTaskRepository {
    private final IAgentTaskDao dao;private final ObjectMapper mapper=new ObjectMapper();private final PasswordEncryptor crypto=new PasswordEncryptor();
    public AgentTaskRepository(IAgentTaskDao dao){this.dao=dao;}
    @PostConstruct public void initialize(){dao.createTasks();dao.createSteps();}
    private String encode(Object value){try{return crypto.encrypt(mapper.writeValueAsString(value));}catch(Exception e){throw new IllegalStateException("任务保存失败",e);}}
    private <T>T decode(String value,Class<T> type){try{return mapper.readValue(crypto.decrypt(value),type);}catch(Exception e){throw new IllegalStateException("任务读取失败",e);}}
    @Transactional public void insert(AgentTask t,List<AgentTaskStep> steps){dao.insert(t.id,t.userId,t.sessionId,t.status,t.createdAt,encode(t));for(int n=0;n<steps.size();n++){var step=steps.get(n);dao.insertStep(t.id,step.key,n,encode(step));}}
    public AgentTask find(String id){var row=dao.find(id);if(row==null)return null;var t=decode((String)row.get("payload"),AgentTask.class);t.cancelRequested=Boolean.parseBoolean(String.valueOf(row.get("cancelRequested"))) || "1".equals(String.valueOf(row.get("cancelRequested")));return t;}
    public List<AgentTask> list(String u,String s){return dao.list(u,s).stream().map(this::find).toList();}
    public List<String> runnable(){return dao.runnable();}
    public void save(AgentTask t){t.updatedAt=System.currentTimeMillis();dao.save(t.id,t.status,encode(t));}
    public List<AgentTaskStep> steps(String id){return dao.steps(id).stream().map(value->decode(value,AgentTaskStep.class)).toList();}
    public void saveStep(AgentTaskStep step){dao.saveStep(step.taskId,step.key,encode(step));}
    public boolean claim(String id,String worker,long now,long until){return dao.claim(id,worker,now,until)==1;}
    public void release(String id,String worker){dao.release(id,worker);}
    public void requestCancel(String id){dao.cancel(id);}
}
