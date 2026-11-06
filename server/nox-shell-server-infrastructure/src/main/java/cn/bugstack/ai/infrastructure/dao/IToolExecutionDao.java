package cn.bugstack.ai.infrastructure.dao;

import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface IToolExecutionDao {
    @Update("""
        CREATE TABLE IF NOT EXISTS tool_execution (
          execution_id VARCHAR(64) NOT NULL PRIMARY KEY,
          user_id VARCHAR(128) NOT NULL,
          session_id VARCHAR(128) NOT NULL,
          started_at BIGINT NOT NULL,
          payload LONGTEXT NOT NULL,
          KEY idx_tool_execution_session (user_id, session_id, started_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """)
    void createTable();

    @Insert("""
        INSERT INTO tool_execution(execution_id,user_id,session_id,started_at,payload)
        VALUES(#{executionId},#{userId},#{sessionId},#{startedAt},#{payload})
        ON DUPLICATE KEY UPDATE payload=VALUES(payload)
        """)
    void save(@Param("executionId") String executionId, @Param("userId") String userId,
              @Param("sessionId") String sessionId, @Param("startedAt") long startedAt,
              @Param("payload") String payload);

    @Select("""
        SELECT payload FROM tool_execution WHERE user_id=#{userId} AND session_id=#{sessionId}
        ORDER BY started_at DESC, execution_id DESC LIMIT 1000
        """)
    List<String> findBySession(@Param("userId") String userId, @Param("sessionId") String sessionId);
}
