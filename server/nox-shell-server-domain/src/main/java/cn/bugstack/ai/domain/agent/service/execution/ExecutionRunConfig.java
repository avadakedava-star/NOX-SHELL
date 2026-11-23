package cn.bugstack.ai.domain.agent.service.execution;

import com.google.adk.agents.RunConfig;

/** ADK 1.2.0 的 maxLlmCalls=0 表示不设轮数上限；停止由真实执行入口的失败和超时规则负责。 */
public final class ExecutionRunConfig {
    private ExecutionRunConfig() { }
    /** 统一构造 ADK 运行配置：关闭框架自带的 LLM 轮数上限，改由执行守卫按失败次数/超时/停滞来终止。 */
    public static RunConfig.Builder builder() { return RunConfig.builder().maxLlmCalls(0); }
}
