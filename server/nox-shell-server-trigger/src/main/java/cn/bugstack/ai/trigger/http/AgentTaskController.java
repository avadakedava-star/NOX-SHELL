package cn.bugstack.ai.trigger.http;
import cn.bugstack.ai.domain.agent.service.task.LongTaskService;
import cn.bugstack.ai.api.response.Response;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/api/v1/agent_task")
@CrossOrigin(origins="*")
public class AgentTaskController {
    private final LongTaskService tasks;
    public AgentTaskController(LongTaskService tasks){this.tasks=tasks;}
    @GetMapping("/list")public Response<List<Map<String,Object>>> list(@RequestParam String userId,@RequestParam String sessionId){return Response.<List<Map<String,Object>>>builder().code("0000").data(tasks.list(userId,sessionId)).build();}
    @GetMapping("/detail")public Response<Map<String,Object>> detail(@RequestParam String taskId,@RequestParam String userId,@RequestParam String sessionId){return Response.<Map<String,Object>>builder().code("0000").data(tasks.detail(taskId,userId,sessionId)).build();}
    public record Action(String taskId,String userId,String sessionId){}
    @PostMapping("/cancel")public Response<String> cancel(@RequestBody Action action){tasks.cancel(action.taskId(),action.userId(),action.sessionId());return Response.<String>builder().code("0000").data("已请求取消，正在核查").build();}
    @PostMapping("/resume")public Response<String> resume(@RequestBody Action action){tasks.resume(action.taskId(),action.userId(),action.sessionId());return Response.<String>builder().code("0000").data("已恢复核查，原作业不会重复启动").build();}
    @ExceptionHandler(IllegalArgumentException.class)public Response<Void> invalid(IllegalArgumentException error){return Response.<Void>builder().code("0001").info(error.getMessage()).build();}
}
