package cn.bugstack.ai.test.infrastructure;

import cn.bugstack.ai.domain.agent.service.audit.ToolExecutionRecord;
import cn.bugstack.ai.infrastructure.adapter.repository.ToolExecutionRepository;
import cn.bugstack.ai.infrastructure.dao.IToolExecutionDao;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.Assume;
import org.junit.Test;
import org.yaml.snakeyaml.Yaml;
import java.util.Map;
import java.util.UUID;
import static org.junit.Assert.*;

/** 可选的本机 MySQL 集成验证；只写入自己的测试记录并按执行编号清理。 */
public class ToolExecutionPersistenceTest {
    @Test public void approvalIsDurableAndClaimCanOnlySucceedOnce() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("nox-shell.audit.mysqlTest"));
        Map<?,?> config;try(var input=getClass().getResourceAsStream("/application-dev.yml")){config=new Yaml().load(input);}
        var datasource=(Map<?,?>)((Map<?,?>)config.get("spring")).get("datasource");
        var source=new UnpooledDataSource("com.mysql.cj.jdbc.Driver",datasource.get("url").toString(),datasource.get("username").toString(),datasource.get("password").toString());
        var configuration=new Configuration(new Environment("approval-test",new JdbcTransactionFactory(),source));
        configuration.addMapper(cn.bugstack.ai.infrastructure.dao.ICommandApprovalDao.class);
        var factory=new SqlSessionFactoryBuilder().build(configuration);String id="approval-test-"+UUID.randomUUID();
        var a=new cn.bugstack.ai.domain.agent.service.approval.CommandApproval(id,"approval-test-user",id,"terminal","connection","host:22","ubuntu",
                "COMMAND","systemctl restart nginx","stored-canonical",null,null,null,System.currentTimeMillis(),System.currentTimeMillis()+60000,"PENDING",null);
        try {
            try(var session=factory.openSession(true)){
                var repository=new cn.bugstack.ai.infrastructure.adapter.repository.CommandApprovalRepository(session.getMapper(cn.bugstack.ai.infrastructure.dao.ICommandApprovalDao.class));
                repository.initialize();repository.insert(a);
            }
            try(var session=factory.openSession(true)){
                var repository=new cn.bugstack.ai.infrastructure.adapter.repository.CommandApprovalRepository(session.getMapper(cn.bugstack.ai.infrastructure.dao.ICommandApprovalDao.class));
                assertEquals(a,repository.find(id));assertTrue(repository.findBySession("other",id).isEmpty());
                assertTrue(repository.transition(a.state("RUNNING",null),"PENDING",System.currentTimeMillis()));
                assertFalse(repository.transition(a.state("RUNNING",null),"PENDING",System.currentTimeMillis()));
                assertTrue(repository.transition(a.state("SUCCEEDED","saved-result"),"RUNNING",System.currentTimeMillis()));
                assertEquals("saved-result",repository.find(id).result());
            }
        } finally {
            try(var connection=source.getConnection();var statement=connection.prepareStatement("DELETE FROM command_approval WHERE approval_id=?")){
                statement.setString(1,id);statement.executeUpdate();
            }
        }
    }
    @Test public void createsTableIdempotentlyAndReadsRecordsAcrossRepositoryInstances() throws Exception {
        Assume.assumeTrue("使用 -Dnox-shell.audit.mysqlTest=true 启用本机数据库测试", Boolean.getBoolean("nox-shell.audit.mysqlTest"));
        Map<String, Object> config;
        try (var input = getClass().getResourceAsStream("/application-dev.yml")) { config = new Yaml().load(input); }
        Map<?, ?> spring = (Map<?, ?>) config.get("spring");
        Map<?, ?> datasource = (Map<?, ?>) spring.get("datasource");
        var source = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", datasource.get("url").toString(),
                datasource.get("username").toString(), datasource.get("password").toString());
        var mybatis = new Configuration(new Environment("audit-test", new JdbcTransactionFactory(), source));
        mybatis.addMapper(IToolExecutionDao.class);
        var factory = new SqlSessionFactoryBuilder().build(mybatis);
        String id = "exec-test-" + UUID.randomUUID();
        String sessionId = "audit-test-" + UUID.randomUUID();
        try (var session = factory.openSession(true)) {
            var repository = new ToolExecutionRepository(session.getMapper(IToolExecutionDao.class));
            repository.initialize(); repository.initialize();
            var record = new ToolExecutionRecord(id, "test-request", "audit-test-user", sessionId,
                    "test-invocation", "test-agent", null, null, "test-tool", "RUNNING", "{}", null, null, System.currentTimeMillis(), null);
            repository.save(record);
            repository.save(record.finished("RETURNED", "测试结果", null));
        }
        try (var session = factory.openSession(true)) {
            var another = new ToolExecutionRepository(session.getMapper(IToolExecutionDao.class));
            var records = another.findBySession("audit-test-user", sessionId);
            assertEquals(1, records.size()); assertEquals(id, records.get(0).executionId());
            assertEquals("RETURNED", records.get(0).status()); assertEquals("测试结果", records.get(0).result());
            assertTrue(another.findBySession("different-user", sessionId).isEmpty());
        } finally {
            try (var connection = source.getConnection(); var statement = connection.prepareStatement("DELETE FROM tool_execution WHERE execution_id=?")) {
                statement.setString(1, id); statement.executeUpdate();
            }
        }
    }
}
