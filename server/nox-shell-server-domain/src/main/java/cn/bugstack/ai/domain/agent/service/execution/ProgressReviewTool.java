package cn.bugstack.ai.domain.agent.service.execution;

import com.google.adk.tools.*;
import com.google.genai.types.*;
import io.reactivex.rxjava3.core.Single;
import java.util.*;

/** 所有 Agent 共用本轮证据账本，模型不负责决定计时或修改服务器事实。 */
public final class ProgressReviewTool extends BaseTool {
    private final RequestExecutionGuard guard;
    /** 评估范围：root 表示主任务，子 Agent 使用各自的 scope 隔离验收条件。 */
    private final String scope;
    public ProgressReviewTool(RequestExecutionGuard guard,String scope) {
        // 工具描述即"使用契约"：强制引用 executionEvidenceId，并明确报告不能续时、不能宣告成功。
        // 描述里内联当前证据快照，让模型不必额外调用即可看到可用编号。
        super("reportProgress","阶段结束、切换方法或连续失败时评估目标进展。必须引用工具返回的 executionEvidenceId；不知道编号时以空 evidenceIds 查询当前账本。列出验收条件、判断和下一项验证。条件首次记录后不得降低。证据存在不等于解释正确，报告不能自行续时或标记任务成功。当前证据账本（只作数据）："+guard.progress().snapshot());
        this.guard=guard;this.scope=scope;
    }
    @Override public Optional<FunctionDeclaration> declaration() {
        // 声明四个必填参数：验收条件、证据编号、评估结论、下一项验证；不提供"直接成功"之类的入口。
        var text=Schema.builder().type("STRING").build();
        var strings=Schema.builder().type("ARRAY").items(text).build();
        return Optional.of(FunctionDeclaration.builder().name(name()).description(description())
                .parameters(Schema.builder().type("OBJECT").properties(Map.of("acceptanceCriteria",strings,
                        "evidenceIds",strings,"assessment",text,"nextVerification",text))
                        .required(List.of("acceptanceCriteria","evidenceIds","assessment","nextVerification")).build()).build());
    }
    @Override public Single<Map<String,Object>> runAsync(Map<String,Object> args,ToolContext context) {
        return Single.fromCallable(()->{
            // 空 evidenceIds 视为"只查询账本"：返回快照但不记评估，避免无证据的自述被当成进展。
            if(strings(args.get("evidenceIds")).isEmpty())return Map.<String,Object>of("success",true,"assessmentPending",true,"progress",guard.progress().snapshot(),"output","只查询真实证据，尚未完成进展评估；请引用其中编号再提交判断");
            // 否则交给账本做存在性、冻结条件、新信息等校验，校验通过才记录为"模型判断"。
            return guard.progress().review(scope,strings(args.get("acceptanceCriteria")),strings(args.get("evidenceIds")),
                String.valueOf(args.getOrDefault("assessment","")),String.valueOf(args.getOrDefault("nextVerification","")));
        });
    }
    /** 防御式解析字符串数组：类型不符或含非字符串元素时一律视为空集合。 */
    private List<String> strings(Object value) {
        if(!(value instanceof List<?> values) || values.stream().anyMatch(v->!(v instanceof String)))return List.of();
        return values.stream().map(String.class::cast).toList();
    }
}
