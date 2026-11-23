package cn.bugstack.ai.domain.agent.service.execution;

/** 用户为单次聊天请求选择的权限，子 Agent 继承同一个请求守卫。 */
public enum AgentPermissionMode {
    APPROVAL, DIRECT;

    public static AgentPermissionMode parse(String value) {
        if (value == null || value.isBlank() || "approval".equals(value)) return APPROVAL;
        if ("direct".equals(value)) return DIRECT;
        throw new IllegalArgumentException("未知执行权限，请选择 approval 或 direct");
    }
}
