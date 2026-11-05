package cn.bugstack.ai.domain.agent.adapter.repository;

import cn.bugstack.ai.domain.agent.service.approval.CommandApproval;
import java.util.List;

public interface ICommandApprovalRepository {
    void insert(CommandApproval approval);
    CommandApproval find(String id);
    List<CommandApproval> findBySession(String userId,String sessionId);
    boolean transition(CommandApproval approval,String expectedStatus,long now);
}
