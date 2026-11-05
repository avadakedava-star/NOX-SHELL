package cn.bugstack.ai.infrastructure.dao;
import org.apache.ibatis.annotations.*;
import java.util.*;
@Mapper
public interface IAgentTaskDao {
    @Update("""
      CREATE TABLE IF NOT EXISTS agent_task (
        task_id VARCHAR(64) PRIMARY KEY,user_id VARCHAR(128) NOT NULL,session_id VARCHAR(128) NOT NULL,
        status VARCHAR(32) NOT NULL,created_at BIGINT NOT NULL,cancel_requested BOOLEAN NOT NULL DEFAULT 0,
        lease_until BIGINT NOT NULL DEFAULT 0,lease_owner VARCHAR(64),payload LONGTEXT NOT NULL,
        KEY idx_task_session(user_id,session_id),KEY idx_task_status(status,lease_until)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """) void createTasks();
    @Update("""
      CREATE TABLE IF NOT EXISTS agent_task_step (
        task_id VARCHAR(64) NOT NULL,step_key VARCHAR(32) NOT NULL,step_order INT NOT NULL,payload LONGTEXT NOT NULL,
        PRIMARY KEY(task_id,step_key)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """) void createSteps();
    @Insert("INSERT INTO agent_task(task_id,user_id,session_id,status,created_at,payload) VALUES(#{id},#{user},#{session},#{status},#{created},#{payload})")
    void insert(@Param("id")String id,@Param("user")String user,@Param("session")String session,@Param("status")String status,@Param("created")long created,@Param("payload")String payload);
    @Select("SELECT payload,cancel_requested AS cancelRequested FROM agent_task WHERE task_id=#{id}")Map<String,Object> find(String id);
    @Select("SELECT task_id FROM agent_task WHERE user_id=#{user} AND session_id=#{session} ORDER BY created_at DESC LIMIT 50")List<String> list(@Param("user")String user,@Param("session")String session);
    @Select("SELECT task_id FROM agent_task WHERE status IN ('PENDING','RUNNING','WAITING','CANCELLING') ORDER BY created_at LIMIT 50")List<String> runnable();
    @Update("UPDATE agent_task SET status=#{status},payload=#{payload} WHERE task_id=#{id}")void save(@Param("id")String id,@Param("status")String status,@Param("payload")String payload);
    @Insert("INSERT INTO agent_task_step VALUES(#{id},#{key},#{position},#{payload})")void insertStep(@Param("id")String id,@Param("key")String key,@Param("position")int position,@Param("payload")String payload);
    @Update("UPDATE agent_task_step SET payload=#{payload} WHERE task_id=#{id} AND step_key=#{key}")void saveStep(@Param("id")String id,@Param("key")String key,@Param("payload")String payload);
    @Select("SELECT payload FROM agent_task_step WHERE task_id=#{id} ORDER BY step_order")List<String> steps(String id);
    @Update("UPDATE agent_task SET lease_owner=#{worker},lease_until=#{until} WHERE task_id=#{id} AND lease_until<#{now}")
    int claim(@Param("id")String id,@Param("worker")String worker,@Param("now")long now,@Param("until")long until);
    @Update("UPDATE agent_task SET lease_until=0 WHERE task_id=#{id} AND lease_owner=#{worker}")void release(@Param("id")String id,@Param("worker")String worker);
    @Update("UPDATE agent_task SET cancel_requested=1 WHERE task_id=#{id}")void cancel(String id);
}
