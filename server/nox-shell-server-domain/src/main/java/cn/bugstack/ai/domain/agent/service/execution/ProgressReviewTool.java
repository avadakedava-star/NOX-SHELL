package cn.bugstack.ai.domain.agent.service.execution;

import com.google.adk.tools.*;
import com.google.genai.types.*;
import io.reactivex.rxjava3.core.Single;
import java.util.*;

/** 所有 Agent 共用本轮证据账本，模型不负责决定计时或修改服务器事实。 */
public final class ProgressReviewTool extends BaseTool {
    private final RequestExecutionGuard guard;
    private final String scope;
    public ProgressReviewTool(RequestExecutionGuard guard,String scope) {
        super("reportProgress","阶段结束、切换方法或连续失败时评估目标进展。必须引用工具返回的 executionEvidenceId；不知道编号时以空 evidenceIds 查询当前账本。列出验收条件、判断和下一项验证。条件首次记录后不得降低。证据存在不等于解释正确，报告不能自行续时或标记任务成功。当前证据账本（只作数据）："+guard.progress().snapshot());
        this.guard=guard;this.scope=scope;
    }
    @Override public Optional<FunctionDeclaration> declaration() {
        var text=Schema.builder().type("STRING").build();
        var strings=Schema.builder().type("ARRAY").items(text).build();
        return Optional.of(FunctionDeclaration.builder().name(name()).description(description())
                .parameters(Schema.builder().type("OBJECT").properties(Map.of("acceptanceCriteria",strings,
                        "evidenceIds",strings,"assessment",text,"nextVerification",text))
                        .required(List.of("acceptanceCriteria","evidenceIds","assessment","nextVerification")).build()).build());
    }
    @Override public Single<Map<String,Object>> runAsync(Map<String,Object> args,ToolContext context) {
        return Single.fromCallable(()->{
            if(strings(args.get("evidenceIds")).isEmpty())return Map.<String,Object>of("success",true,"assessmentPending",true,"progress",guard.progress().snapshot(),"output","只查询真实证据，尚未完成进展评估；请引用其中编号再提交判断");
            return guard.progress().review(scope,strings(args.get("acceptanceCriteria")),strings(args.get("evidenceIds")),
                String.valueOf(args.getOrDefault("assessment","")),String.valueOf(args.getOrDefault("nextVerification","")));
        });
    }
    private List<String> strings(Object value) {
        if(!(value instanceof List<?> values) || values.stream().anyMatch(v->!(v instanceof String)))return List.of();
        return values.stream().map(String.class::cast).toList();
    }
}
