package cn.bugstack.ai.domain.agent.service.execution;
import cn.bugstack.ai.domain.agent.service.audit.ToolExecutionRecord;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
public class RunFailureSummaryTest {
    private RunFailureSummaryService service(ChatModel model){return new RunFailureSummaryService(null){@Override protected ChatModel model(String id){return model;}};}
    @Test public void summaryUsesFactsWithoutAnyTools(){
        var model=mock(ChatModel.class);when(model.call(any(Prompt.class))).thenAnswer(call->{
            Prompt prompt=call.getArgument(0);assertNull(prompt.getOptions());assertTrue(prompt.getContents().contains("No such file"));
            return new ChatResponse(List.of(new Generation(new AssistantMessage("文件不存在，不是权限不足"))));
        });
        var record=new ToolExecutionRecord("id","request","user","session","invocation","child","terminal",null,"executeCommand","REPORTED_FAILURE","cat /etc/docker/daemon.json","{\"exitCode\":1,\"commandDispatched\":true,\"stderr\":\"No such file\"}",null,1L,2L);
        var report=RunFailureReport.from("EXECUTION_ERROR","读取失败",List.of(record));
        assertTrue(service(model).summarize("agent",report).contains("文件不存在，不是权限不足"));assertTrue(report.modelFacts().contains("exitCode=1"));assertFalse(report.fallback().contains("executeCommand"));
    }
    @Test public void modelFailureStillReturnsConcreteCause(){
        var model=mock(ChatModel.class);when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("network"));
        var report=RunFailureReport.from("REQUEST_TIMEOUT","模型等待超过180秒",List.of());
        assertEquals(report.fallback(),service(model).summarize("agent",report));
    }
    @Test public void containerVerificationTimeoutHasReadableFallbackWithoutAuditDump(){
        var create=new ToolExecutionRecord("create","request","user","session","invocation","sshChangeAgent","terminal",null,"executeCommand","RETURNED",
                "{\"command\":\"sudo -n docker run -d --name mysql8 mysql:8.0\"}",
                "{\"exitCode\":0,\"stdout\":\""+"a".repeat(64)+"\"}",null,1L,2L);
        var check=new ToolExecutionRecord("check","request","user","session","invocation","sshChangeAgent","terminal",null,"executeCommand","UNKNOWN",
                "{\"command\":\"sleep 30; sudo -n docker ps --filter name=mysql8\"}",
                "{\"failureType\":\"COMMAND_TIMEOUT\",\"commandDispatched\":true,\"timeoutMs\":30000}",null,3L,4L);
        var report=RunFailureReport.from("RESULT_UNKNOWN","操作结果未知",List.of(create,check));
        String text=report.fallback();
        assertTrue(text.contains("容器已经创建"));assertTrue(text.contains("等待 30 秒"));assertTrue(text.contains("总时限只有 30 秒"));
        assertTrue(text.contains("不要重新创建"));assertTrue(text.length()<250);
        for(String raw:List.of("RESULT_UNKNOWN","executeCommand","sshChangeAgent","sudo","exitCode","aaaaaaaa"))assertFalse(text.contains(raw));
        assertTrue(report.modelFacts().contains("COMMAND_TIMEOUT"));
        var model=mock(ChatModel.class);when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("offline"));
        assertEquals(text,service(model).summarize("agent",report));
    }
}
