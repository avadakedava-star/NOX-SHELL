"""Derive a limited diagnosis from observed facts and render a handoff report."""

PROBE_LABELS = {
    "system_resolution": "系统名称解析", "tcp": "TCP 连接", "tls": "TLS 证书及连接校验",
    "http": "HTTP HEAD 响应", "endpoint": "目标访问", "ip_gateway": "网卡与默认路由",
    "proxy_settings": "代理配置迹象", "local_context": "本机网络补查",
}
STATUS_LABELS = {
    "pass": "已取得结果", "fail": "检查失败", "timeout": "超时", "skipped": "未执行",
    "unsupported": "环境不支持", "denied": "范围不允许", "error": "采集错误",
}
VERIFICATION_LABELS = {
    "not_run": "未执行复测",
    "still_accessible": "同一采集环境再次收到 2xx 响应",
    "accessible_now": "同一采集环境本次收到 2xx 响应，原始结果以导入报告为准",
    "different_origin": "两次采集来源不同或无法比较",
    "uncertain": "复测仍未取得 2xx 响应，请对照两次证据",
}


def conclude(evidence, scheme):
    dns = next(item for item in evidence if item["probe"] == "system_resolution")
    responses = [item for item in evidence if item["probe"] == "http" and item["status"] == "pass"]
    certificate = next((item for item in evidence if item["probe"] == "tls"
                        and item["actual"].get("reason") == "certificate_verification_failed"), None)
    ip = next((item for item in evidence if item["probe"] == "ip_gateway" and item["status"] == "pass"), None)
    proxy = next((item for item in evidence if item["probe"] == "proxy_settings"
                  and any(value is True for value in item["actual"].values())), None)

    def diagnosis(status, category, summary, items, *actions):
        return {
            "status": status, "category": category, "summary": summary,
            "evidence_ids": [item["evidence_id"] for item in items], "next_actions": list(actions),
        }

    reachable = next((item for item in responses if 200 <= item["actual"]["status_code"] < 300), None)
    if reachable:
        code = reachable["actual"]["status_code"]
        return diagnosis("no_issue_detected", "http_reachable",
                         f"从采集环境直连站点根路径时，收到 HTTP {code}。", [reachable],
                         "若实际浏览器仍打不开，请核对实际路径、浏览器代理、登录状态和报障设备。")
    if responses:
        code = responses[0]["actual"]["status_code"]
        category = "http_server_error" if code >= 500 else "http_other_response"
        return diagnosis("suspected" if code >= 500 else "inconclusive", category,
                         f"采集环境已连到 HTTP 服务，根路径返回 HTTP {code}。", responses,
                         "核对浏览器里的实际页面和提示。3xx 需核对跳转或登录，4xx 需核对路径和权限，5xx 可交给站点管理员核查。")
    if certificate:
        return diagnosis("suspected", "tls_validation", "目标连接已建立，TLS 证书校验失败。", [certificate],
                         "核对访问域名、系统时间、站点证书及受信代理，保留 TLS 校验。")
    if ip:
        actual = ip["actual"]
        if actual["ipv4_links_only"] and actual["ipv4_default_routes"] == 0 and actual["ipv6_default_routes"] == 0:
            return diagnosis("suspected", "ip_gateway", "采集环境只有链路本地 IPv4 地址，未发现 IPv4 或 IPv6 默认路由。", [ip],
                             "核对网线或 Wi-Fi、地址分配及默认路由设置。向管理员提供故障时的网络环境。")
    if dns["status"] in {"fail", "timeout"}:
        related = [dns, proxy] if proxy else [dns]
        return diagnosis("suspected", "name_resolution", "系统没有解析出目标地址。", related,
                         "核对站点地址、当前网络和系统解析设置。仅凭这次结果无法判断是哪一环出了问题。")
    if dns["status"] == "denied":
        return diagnosis("inconclusive", "target_scope", "系统解析的地址超出当前探测范围。", [dns],
                         "确认站点地址，并让管理员在授权环境中检查目标。")
    direct = [item for item in evidence if item["probe"] in {"endpoint", "tcp", "tls", "http"}]
    if proxy and direct:
        return diagnosis("inconclusive", "proxy_comparison", "直连未取得 HTTP 响应，采集环境存在代理配置迹象。", [*direct, proxy],
                         f"在报障设备用原应用检查它实际使用的代理，并与本次 {scheme.upper()} 直连结果对照。")
    return diagnosis("inconclusive", "endpoint_unverified", "采集环境未取得可用于定位的 HTTP 响应。", direct or [dns],
                     "向站点管理员核对服务是否可用，补充报障设备、时间和浏览器提示。")


def render(report):
    diagnosis = report["diagnosis"]
    cited = "、".join(diagnosis["evidence_ids"])
    origin = report["origin"]
    lines = [
        "# NetPilot 网络诊断报告", "",
        f"- 目标：{report['target']}",
        f"- 采集来源：{origin['os']} 采集进程 {origin['fingerprint'] or '未知'}",
        f"- 任务编号：{report['task_id']}",
        f"- 本次采集完成时间（UTC）：{report['completed_at']}",
        f"- 结论：{diagnosis['summary']} 证据 {cited}。",
        f"- 复测：{VERIFICATION_LABELS[report['verification']['status']]}。",
        "- 影响范围：本次采集位置、目标和时间。其他用户的情况未知。",
        "- 处置：仅执行只读检查，未修改配置。", "",
        "## 检查证据", "",
        "| 编号 | 检查 | 结果 | 观测值 | 时间（UTC） |", "| --- | --- | --- | --- | --- |",
    ]
    for item in report["evidence"]:
        actual = ", ".join(f"{key}={value}" for key, value in item["actual"].items())
        label = PROBE_LABELS[item["probe"]]
        lines.append(f"| {item['evidence_id']} | {label} | {STATUS_LABELS[item['status']]} | {actual} | {item['observed_at']} |")
    lines.extend(["", "## 下一步与报障", ""])
    lines.extend(f"- {action}" for action in diagnosis["next_actions"])
    lines.extend([
        "- 提交给管理员时补充原始报障时间、受影响设备、实际页面和现象。分享前确认目标地址适合披露。",
        "- HTTP 探测只访问根路径，走直连且不跟随跳转。采集进程可能位于隔离环境。",
        "",
    ])
    return "\n".join(lines)
