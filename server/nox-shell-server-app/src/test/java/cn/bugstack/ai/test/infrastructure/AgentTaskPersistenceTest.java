package cn.bugstack.ai.test.infrastructure;
import cn.bugstack.ai.infrastructure.adapter.repository.AgentTaskRepository;
import cn.bugstack.ai.infrastructure.dao.IAgentTaskDao;
import cn.bugstack.ai.domain.agent.service.task.*;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
public class AgentTaskPersistenceTest {
    @Test public void encryptedTaskRoundTripPreservesProgressButNeverStoresPlainSecrets(){
        var dao=mock(IAgentTaskDao.class);var repo=new AgentTaskRepository(dao);repo.initialize();
        var task=new AgentTask();task.id="task";task.userId="user";task.sessionId="chat";task.status="RUNNING";task.kind="COMMAND";task.stage="COMMAND";task.command="client --password sample-secret";
        var step=new AgentTaskStep(task.id,"COMMAND","安装");step.remoteId="COMMAND-1";step.status="RUNNING";
        repo.insert(task,List.of(step));var payload=ArgumentCaptor.forClass(String.class);
        verify(dao).insert(eq("task"),eq("user"),eq("chat"),eq("RUNNING"),anyLong(),payload.capture());assertFalse(payload.getValue().contains("sample-secret"));
        var stepPayload=ArgumentCaptor.forClass(String.class);verify(dao).insertStep(eq("task"),eq("COMMAND"),eq(0),stepPayload.capture());
        when(dao.find("task")).thenReturn(Map.of("payload",payload.getValue(),"cancelRequested",1));when(dao.steps("task")).thenReturn(List.of(stepPayload.getValue()));
        var restarted=new AgentTaskRepository(dao);var restored=restarted.find("task");assertEquals("client --password sample-secret",restored.command);assertTrue(restored.cancelRequested);
        assertEquals("COMMAND-1",restarted.steps("task").get(0).remoteId);assertFalse(restored.view().toString().contains("sample-secret"));
        verify(dao).createTasks();verify(dao).createSteps();
    }
}
