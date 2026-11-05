package cn.bugstack.ai.infrastructure.dao;

import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface ICommandApprovalDao {
    @Update("""
        CREATE TABLE IF NOT EXISTS command_approval (
          approval_id VARCHAR(64) PRIMARY KEY,user_id VARCHAR(128) NOT NULL,session_id VARCHAR(128) NOT NULL,
          status VARCHAR(32) NOT NULL,expires_at BIGINT NOT NULL,payload LONGTEXT NOT NULL,
          KEY idx_approval_session(user_id,session_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """)
    void createTable();
    @Insert("INSERT INTO command_approval VALUES(#{id},#{user},#{session},#{status},#{expires},#{payload})")
    void insert(@Param("id") String id,@Param("user") String user,@Param("session") String session,
                @Param("status") String status,@Param("expires") long expires,@Param("payload") String payload);
    @Select("SELECT payload FROM command_approval WHERE approval_id=#{id}") String find(String id);
    @Select("SELECT payload FROM command_approval WHERE user_id=#{user} AND session_id=#{session} ORDER BY expires_at DESC LIMIT 100")
    List<String> list(@Param("user") String user,@Param("session") String session);
    @Update("""
        UPDATE command_approval SET status=#{status},payload=#{payload}
        WHERE approval_id=#{id} AND status=#{expected}
          AND (#{status} != 'RUNNING' OR expires_at > #{now})
        """)
    int transition(@Param("id") String id,@Param("expected") String expected,@Param("status") String status,
                   @Param("payload") String payload,@Param("now") long now);
}
