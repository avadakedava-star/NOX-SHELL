package cn.bugstack.ai.domain.agent.service.task;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public record TaskPolicy(int commandSeconds,int pullSeconds,int readinessSeconds,int taskSeconds){
    public TaskPolicy(@Value("${walissh.tasks.command-seconds:600}")int commandSeconds,
        @Value("${walissh.tasks.pull-seconds:600}")int pullSeconds,
        @Value("${walissh.tasks.readiness-seconds:180}")int readinessSeconds,
        @Value("${walissh.tasks.task-seconds:1200}")int taskSeconds){
        if(commandSeconds<1 || pullSeconds<1 || readinessSeconds<1 || taskSeconds<1)throw new IllegalArgumentException("任务时限必须为正数");
        this.commandSeconds=commandSeconds;this.pullSeconds=pullSeconds;this.readinessSeconds=readinessSeconds;this.taskSeconds=taskSeconds;
    }
}
