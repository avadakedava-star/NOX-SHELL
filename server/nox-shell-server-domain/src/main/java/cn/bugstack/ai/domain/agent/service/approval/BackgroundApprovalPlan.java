package cn.bugstack.ai.domain.agent.service.approval;

import cn.bugstack.ai.domain.agent.service.task.AgentTask;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 审批期间保存原始后台任务参数，点击批准后才提交作业。 */
public record BackgroundApprovalPlan(String title, String command, int timeoutSeconds, AgentTask.ContainerSpec container) {
    public static boolean isBackground(String kind) {
        return "BACKGROUND_COMMAND".equals(kind) || "BACKGROUND_CONTAINER".equals(kind);
    }

    public String encode() {
        try { return new ObjectMapper().writeValueAsString(this); }
        catch (JsonProcessingException error) { throw new IllegalArgumentException("任务审批方案保存失败", error); }
    }

    public static BackgroundApprovalPlan decode(String json) {
        try { return new ObjectMapper().readValue(json, BackgroundApprovalPlan.class); }
        catch (JsonProcessingException error) { throw new IllegalArgumentException("任务审批方案无效", error); }
    }
}
