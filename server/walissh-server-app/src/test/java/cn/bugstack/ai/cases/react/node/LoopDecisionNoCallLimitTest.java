package cn.bugstack.ai.cases.react.node;

import cn.bugstack.ai.api.dto.ChatRequestDTO;
import cn.bugstack.ai.api.dto.ReActResultDTO;
import cn.bugstack.ai.cases.react.factory.DefaultReActFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class LoopDecisionNoCallLimitTest {
    private final LoopDecisionNode node = new LoopDecisionNode() {
        @Override public StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
                ChatRequestDTO request, DefaultReActFactory.DynamicContext context) {
            return (req, ctx) -> ctx.getResult();
        }
    };
    private DefaultReActFactory.DynamicContext context() {
        return DefaultReActFactory.DynamicContext.builder().currentStep(new AtomicInteger(1000))
                .totalToolCallCount(new AtomicInteger(1000)).result(ReActResultDTO.builder().build()).build();
    }
    @Test public void enhancedModeDoesNotMarkSuccessfulLongRunAsBudgetExceeded() throws Exception {
        var context=context(); node.doApply(new ChatRequestDTO(),context);
        assertEquals("completed",context.getStopReason());
        assertFalse(context.getResult().isMaxStepsReached());
    }
    @Test public void enhancedModePreservesFailureStopFromRealExecutionGuard() throws Exception {
        var context=context(); context.setStopReason("CONSECUTIVE_FAILURE_LIMIT");
        node.doApply(new ChatRequestDTO(),context);
        assertEquals("CONSECUTIVE_FAILURE_LIMIT",context.getStopReason());
    }
}
