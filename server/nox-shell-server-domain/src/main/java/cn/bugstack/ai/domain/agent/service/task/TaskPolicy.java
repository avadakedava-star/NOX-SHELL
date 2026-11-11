package cn.bugstack.ai.domain.agent.service.task;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public record TaskPolicy(int commandSeconds,int pullSeconds,int readinessSeconds,int taskSeconds,int maxObservationSeconds){
    public TaskPolicy(int command,int pull,int readiness,int task){this(command,pull,readiness,task,86400);}
    @org.springframework.beans.factory.annotation.Autowired
    public TaskPolicy(@Value("${nox-shell.tasks.command-seconds:600}")int commandSeconds,
        @Value("${nox-shell.tasks.pull-seconds:600}")int pullSeconds,
        @Value("${nox-shell.tasks.readiness-seconds:180}")int readinessSeconds,
        @Value("${nox-shell.tasks.task-seconds:1200}")int taskSeconds,
        @Value("${nox-shell.tasks.max-observation-seconds:86400}")int maxObservationSeconds){
        if(commandSeconds<1 || pullSeconds<1 || readinessSeconds<1 || taskSeconds<1)throw new IllegalArgumentException("任务时限必须为正数");
        if(maxObservationSeconds<taskSeconds)throw new IllegalArgumentException("最终观察上限不能短于观察窗口");
        this.commandSeconds=commandSeconds;this.pullSeconds=pullSeconds;this.readinessSeconds=readinessSeconds;this.taskSeconds=taskSeconds;this.maxObservationSeconds=maxObservationSeconds;
    }
}
