package cn.bugstack.ai.domain.agent.adapter.repository;

import cn.bugstack.ai.domain.agent.service.audit.ToolExecutionRecord;
import java.util.List;

public interface IToolExecutionRepository {
    void save(ToolExecutionRecord record);
    List<ToolExecutionRecord> findBySession(String userId, String sessionId);
}
