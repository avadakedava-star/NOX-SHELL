"""Combine imported local observations and optional final-test evidence without probing."""

import re
from datetime import datetime
from uuid import UUID

from remote_status import IDENTIFIER, OUTCOMES, STATES
from reporting import PROBE_LABELS, STATUS_LABELS, VERIFICATION_LABELS


def evidence_rows(report, label):
    items = report.get("evidence")
    if not isinstance(items, list) or not 1 <= len(items) <= 16:
        raise ValueError("本地报告的证据条数无效。")
    rows, seen = [], set()
    for item in items:
        if not isinstance(item, dict):
            raise ValueError("报告包含无法识别的证据。")
        evidence_id, probe, status = item.get("evidence_id"), item.get("probe"), item.get("status")
        if (not isinstance(evidence_id, str) or not re.fullmatch(r"ev-[0-9]{2}", evidence_id)
                or evidence_id in seen or not isinstance(probe, str) or probe not in PROBE_LABELS
                or not isinstance(status, str) or status not in STATUS_LABELS
                or item.get("target") != report["target"] or item.get("origin") != report["origin"]):
            raise ValueError("证据编号、类型或采集来源与报告不一致。")
        seen.add(evidence_id)
        detail = STATUS_LABELS[status]
        code = None
        if probe == "http" and status == "pass":
            actual = item.get("actual")
            code = actual.get("status_code") if isinstance(actual, dict) else None
            if type(code) is not int or not 100 <= code <= 599:
                raise ValueError("HTTP 响应状态码无效。")
            detail = f"HTTP {code}"
        rows.append((f"{label}/{evidence_id}", PROBE_LABELS[probe], detail, code))
    return rows


def remote_observation(payload):
    data = payload.get("evidence") if isinstance(payload, dict) and payload.get("ok") is True else None
    if not isinstance(data, dict) or data.get("source") != "final-test-ssh" or data.get("schema_version") != "1.0":
        raise ValueError("请选择 final-test 远程查询成功返回的证据文件。")
    origin = data.get("origin")
    device = origin.get("device_id") if isinstance(origin, dict) else None
    service = data.get("service_id")
    if (not isinstance(device, str) or not IDENTIFIER.fullmatch(device)
            or origin.get("type") != "remote_device"
            or not isinstance(service, str) or not IDENTIFIER.fullmatch(service)):
        raise ValueError("远程设备或服务标识无效。")
    uuid_value, time_value = data.get("evidence_id"), data.get("observed_at")
    if not isinstance(uuid_value, str) or not isinstance(time_value, str):
        raise ValueError("远程证据缺少编号或时间。")
    evidence_id = str(UUID(uuid_value))
    observed_at = datetime.fromisoformat(time_value).isoformat()
    status, state, reason = data.get("status"), data.get("service_state"), data.get("reason")
    if status == "observed" and isinstance(state, str) and state in STATES and reason is None:
        detail = f"服务状态 {state}"
    elif isinstance(status, str) and state is None and isinstance(reason, str) and reason in OUTCOMES.get(status, ()):
        detail = f"服务状态尚未确认（{reason}）"
    else:
        raise ValueError("远程证据的状态无效。")
    return f"{device}/{service}，{detail}。证据 {evidence_id}，采集时间 {observed_at}。"


def build_handoff(baseline, retest=None, remote=None):
    first = evidence_rows(baseline, "首次")
    second = evidence_rows(retest, "复测") if retest else []

    def summary(rows):
        codes = [f"{item[2]}（{item[0]}）" for item in rows if item[3] is not None]
        return "、".join(codes) if codes else "未取得 HTTP 响应，故障位置仍待确认"

    lines = [
        "# NetPilot 报障交接单", "",
        f"- 检查目标：{baseline['target']}",
        f"- 首次观测：{summary(first)}。",
        "- 采集范围：报告中的采集环境、目标根路径和采集时间。",
        "- 说明：下面使用导入的证据，支持范围以原报告为准。",
    ]
    if second:
        fingerprint = baseline["origin"].get("fingerprint")
        previous_ok = any(row[3] is not None and 200 <= row[3] < 300 for row in first)
        current_ok = any(row[3] is not None and 200 <= row[3] < 300 for row in second)
        verification = "uncertain"
        if (not isinstance(fingerprint, str) or not re.fullmatch(r"[0-9a-f]{16}", fingerprint)
                or baseline["origin"] != retest["origin"]):
            verification = "different_origin"
        elif current_ok:
            verification = "still_accessible" if previous_ok else "accessible_now"
        lines += [
            f"- 复测观测：{summary(second)}。",
            f"- 复测结论：{VERIFICATION_LABELS[verification]}。",
        ]
    else:
        lines.append("- 复测状态：尚未复测。")
    if remote is not None:
        lines += [
            "", "## 单独的服务器观测", "",
            remote_observation(remote),
            "该证据来自服务器侧。请核对配置的设备、服务与报障站点是否对应。",
        ]
    lines += ["", "## 本机检查证据", "", "| 编号 | 检查 | 观测 |", "| --- | --- | --- |"]
    lines.extend(f"| {evidence_id} | {probe} | {detail} |" for evidence_id, probe, detail, _ in first + second)
    lines += [
        "", "## 交接事项", "",
        "请补充报障时间、受影响设备和浏览器提示。若确认故障仍存在，请管理员核查站点或上游服务。",
        "",
    ]
    return "\n".join(lines)
