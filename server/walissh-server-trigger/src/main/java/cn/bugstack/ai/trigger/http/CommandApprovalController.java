package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.domain.agent.service.approval.*;
import cn.bugstack.ai.api.response.Response;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/command_approval")
public class CommandApprovalController {
    private final CommandApprovalService approvals;
    private final ApprovedCommandExecutor executor;
    public CommandApprovalController(CommandApprovalService approvals,ApprovedCommandExecutor executor) {this.approvals=approvals;this.executor=executor;}
    @GetMapping("/list") public Response<List<Map<String,Object>>> list(@RequestParam String userId,@RequestParam String sessionId) {
        return Response.<List<Map<String,Object>>>builder().code("0000").data(approvals.list(userId,sessionId)).build();
    }
    public record Decision(String approvalId,String userId,String sessionId,boolean approve) { }
    @PostMapping("/decide") public Response<Map<String,Object>> decide(@RequestBody Decision decision) {
        try {return Response.<Map<String,Object>>builder().code("0000").data(executor.decide(decision.approvalId(),decision.userId(),decision.sessionId(),decision.approve())).build();}
        catch(IllegalArgumentException e){return Response.<Map<String,Object>>builder().code("0001").info(e.getMessage()).build();}
    }
}
