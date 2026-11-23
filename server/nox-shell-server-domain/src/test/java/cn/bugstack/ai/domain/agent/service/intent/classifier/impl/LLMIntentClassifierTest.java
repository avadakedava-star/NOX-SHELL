package cn.bugstack.ai.domain.agent.service.intent.classifier.impl;

import cn.bugstack.ai.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import cn.bugstack.ai.domain.agent.model.valobj.prompt.PromptContextVO;
import cn.bugstack.ai.domain.agent.service.prompt.dynamic.DynamicPromptBuilder;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LLMIntentClassifierTest {

    @Test
    public void rendersScalarEntitiesFromModelResponse() {
        var result = new LLMIntentClassifier().parseResponse("""
                {"intent":"EXECUTE","confidence":0.9,"entities":{
                  "service":"nginx","requiresApproval":true,"port":80,
                  "unknown":null,"extra":{"source":"model"}}}
                """);

        assertEquals(IntentTypeEnumVO.EXECUTE, result.getIntent());
        assertEquals(Map.of("service", "nginx", "requiresApproval", "true", "port", "80"),
                result.getEntities());
        var context = PromptContextVO.builder().intentResult(result).build();
        String prompt = new DynamicPromptBuilder().buildEphemeralContext(context);
        assertTrue(prompt.contains("requiresApproval=true"));
    }
}
