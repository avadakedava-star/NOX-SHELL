package cn.bugstack.ai.domain.agent.service.execution;
import cn.bugstack.ai.domain.agent.service.armory.matter.patch.MySpringAI;
import com.google.adk.models.LlmRequest;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.junit.Test;
import java.util.*;
import reactor.core.publisher.Flux;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
public class StreamingResponseTest {
    @Test public void emitsTwoDeltasAndExactlyOneFinalSnapshot(){
        var model=mock(OpenAiChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("你")))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("好"))))));
        var request=LlmRequest.builder().contents(List.of(Content.fromParts(Part.fromText("test")))).tools(Map.of()).build();
        var responses=new MySpringAI(model,model,"test").generateContent(request,true).toList().blockingGet();
        assertEquals(3,responses.size());assertTrue(responses.get(0).partial().orElse(false));assertFalse(responses.get(0).turnComplete().orElse(true));
        assertTrue(responses.get(1).partial().orElse(false));assertFalse(responses.get(2).partial().orElse(true));assertTrue(responses.get(2).turnComplete().orElse(false));
        assertEquals("你好",responses.get(2).content().orElseThrow().text());
    }
}
