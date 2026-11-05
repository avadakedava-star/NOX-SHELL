package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.adapter.repository.IToolExecutionRepository;
import cn.bugstack.ai.domain.agent.service.audit.ToolExecutionRecord;
import cn.bugstack.ai.infrastructure.dao.IToolExecutionDao;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public class ToolExecutionRepository implements IToolExecutionRepository {
    private final IToolExecutionDao dao;
    private final ObjectMapper mapper = new ObjectMapper();
    public ToolExecutionRepository(IToolExecutionDao dao) { this.dao = dao; }
    /** 只创建新增的审计表，不重建或修改已有业务表。 */
    @PostConstruct public void initialize() { dao.createTable(); }
    @Override public void save(ToolExecutionRecord record) {
        try {
            dao.save(record.executionId(), record.userId(), record.sessionId(), record.startedAt(), mapper.writeValueAsString(record));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("工具审计序列化失败", error);
        }
    }
    @Override public List<ToolExecutionRecord> findBySession(String userId, String sessionId) {
        return dao.findBySession(userId, sessionId).stream().map(payload -> {
            try { return mapper.readValue(payload, ToolExecutionRecord.class); }
            catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException("工具审计读取失败", error); }
        }).toList();
    }
}
