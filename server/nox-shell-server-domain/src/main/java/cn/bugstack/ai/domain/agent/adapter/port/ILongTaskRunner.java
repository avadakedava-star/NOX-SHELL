package cn.bugstack.ai.domain.agent.adapter.port;
import cn.bugstack.ai.domain.agent.service.task.AgentTask;
import cn.bugstack.ai.domain.ssh.model.valobj.CommandExecutionResult;
/** 与聊天终端独立的远端执行通道。远端作业按 task/step 编号幂等启动。 */
public interface ILongTaskRunner {
    record Job(String status,Integer exitCode,String output,long cpuTicks,long ioBytes){
        public Job(String status,Integer exitCode,String output){this(status,exitCode,output,0,0);}
    }
    CommandExecutionResult query(AgentTask task,String command,long timeoutMs);
    Job start(AgentTask task,String jobId,String command,int timeoutSeconds);
    Job poll(AgentTask task,String jobId);
    Job cancel(AgentTask task,String jobId);
    void close(String taskId);
}
