package cn.bugstack.ai.domain.agent.service.task;
import cn.bugstack.ai.domain.agent.service.armory.matter.tools.LongTaskAdkTool;
import cn.bugstack.ai.domain.agent.service.audit.ToolExecutionAudit;
import com.google.adk.tools.FunctionTool;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
public class TaskToolSchemaTest {
    @Test public void allTaskToolsHaveUsableAdkDeclarations(){
        var tools=new LongTaskAdkTool(mock(LongTaskService.class),mock(ToolExecutionAudit.class),new TaskPolicy(600,600,180,1200));
        for(String name:List.of("submitLongCommand","deployContainer","getTask","cancelTask")){
            var declaration=FunctionTool.create(tools,name).declaration().orElseThrow();assertEquals(name,declaration.name().orElseThrow());assertTrue(declaration.parameters().isPresent());
        }
        var properties=FunctionTool.create(tools,"deployContainer").declaration().orElseThrow().parameters().orElseThrow().properties().orElseThrow();
        assertTrue(properties.containsKey("environment"));assertTrue(properties.containsKey("volumes"));
    }
}
