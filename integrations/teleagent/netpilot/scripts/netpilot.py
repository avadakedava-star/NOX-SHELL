"""NetPilot's bounded local diagnostic CLI. Python 3.10+; no third-party packages."""

import argparse
import hashlib
import ipaddress
import json
import os
import platform
import re
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit
from uuid import uuid4

from handoff import build_handoff
from probes import allowed_address, environment, result, worker
from reporting import conclude, render

VERSION = "1.0.0"
SCHEMA_VERSION = "1.0"
MAX_REPORT_BYTES = 128 * 1024
HOST_LABEL = re.compile(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\Z")


@dataclass(frozen=True)
class Target:
    scheme: str
    host: str
    port: int
    literal: bool

    @property
    def url(self):
        name = f"[{self.host}]" if ":" in self.host else self.host
        default = 443 if self.scheme == "https" else 80
        return f"{self.scheme}://{name}" + (f":{self.port}" if self.port != default else "")


def target_from(value):
    if not 1 <= len(value) <= 300 or any(c.isspace() or ord(c) < 32 for c in value):
        raise ValueError("目标地址为空、过长或包含空白字符。")
    if any(c in value for c in ("\\", "%", "?", "#", "@")) or "\x7f" in value:
        raise ValueError("请只提供 HTTP(S) 站点地址，不包含凭据、编码、查询参数或片段。")
    try:
        parsed = urlsplit(value)
        port = parsed.port
    except ValueError:
        raise ValueError("站点地址或端口格式不正确。") from None
    if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.path not in {"", "/"}:
        raise ValueError("请提供一个 HTTP(S) 站点地址，例如 https://example.test。")
    if parsed.netloc.endswith(":"):
        raise ValueError("端口号不能为空。")
    if parsed.netloc.startswith("[") and not re.fullmatch(r"\[[0-9a-fA-F:.]+\](?::\d+)?", parsed.netloc):
        raise ValueError("IPv6 站点地址格式不正确。")
    if port == 0:
        raise ValueError("端口号必须在 1–65535 之间。")
    port = port if port is not None else (443 if parsed.scheme == "https" else 80)
    host = parsed.hostname.rstrip(".")
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        host = host.encode("idna").decode("ascii").lower()
        if len(host) > 253 or not all(HOST_LABEL.fullmatch(label) for label in host.split(".")):
            raise ValueError("站点域名格式不正确。")
        return Target(parsed.scheme, host, port, False)
    if not allowed_address(str(address)):
        raise ValueError("目标地址超出允许的探测范围。")
    return Target(parsed.scheme, str(address), port, True)


def utc_now():
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def collector():
    system = platform.system()
    name = platform.node().strip()
    fingerprint = hashlib.sha256(f"netpilot/1/{system}/{name}".encode()).hexdigest()[:16] if name else None
    return {"type": "collector_process", "os": system, "fingerprint": fingerprint}


def address_family(address):
    return f"IPv{ipaddress.ip_address(address).version}"


def diagnose(target, seconds):
    task_id = uuid4().hex
    origin = collector()
    started_at = utc_now()
    deadline = time.monotonic() + seconds
    evidence = []

    def record(probe, source, observation, began):
        evidence.append({
            "evidence_id": f"ev-{len(evidence) + 1:02d}",
            "probe": probe,
            "target": target.url,
            "origin": origin,
            "source": source,
            "observed_at": utc_now(),
            "duration_ms": round((time.monotonic() - began) * 1000),
            **observation,
        })

    def remaining(maximum):
        return max(0, min(maximum, deadline - time.monotonic()))

    began = time.monotonic()
    if target.literal:
        candidates = [target.host]
        observation = result("skipped", reason="target_is_ip_literal")
    else:
        observation = worker("resolve", [target.host, str(target.port)], remaining(5))
        candidates = observation["actual"].pop("candidates", [])
        if candidates:
            observation["actual"]["candidate_families"] = list(dict.fromkeys(map(address_family, candidates)))
    record("system_resolution", "system_resolver", observation, began)

    for number, address in enumerate(candidates, start=1):
        began = time.monotonic()
        budget = remaining(5)
        if budget < 0.1:
            record("endpoint", "direct_socket", result("skipped", reason="task_budget_exhausted"), began)
            break
        info = {"candidate": number, "address_family": address_family(address)}
        observations = worker(
            "endpoint", [target.scheme, target.host, str(target.port), address, str(min(2, budget))], budget
        )
        if "status" in observations:
            observations = {"endpoint": observations}
        for probe, outcome in observations.items():
            outcome["actual"].update(info)
            record(probe, "direct_socket", outcome, began)
        response = observations.get("http", {})
        if 200 <= response.get("actual", {}).get("status_code", 0) < 300:
            break

    if not any(item["probe"] == "http" and item["status"] == "pass" for item in evidence):
        began = time.monotonic()
        budget = remaining(7)
        if budget >= 0.1:
            for probe, outcome in environment(budget).items():
                record(probe, "local_system_settings", outcome, began)
        else:
            record("local_context", "local_system_settings",
                   result("skipped", reason="task_budget_exhausted"), began)

    return {
        "schema_version": SCHEMA_VERSION,
        "netpilot_version": VERSION,
        "task_id": task_id,
        "target": target.url,
        "origin": origin,
        "started_at": started_at,
        "completed_at": utc_now(),
        "mode": "live",
        "evidence": evidence,
        "diagnosis": conclude(evidence, target.scheme),
        "remediation_status": "not_attempted",
        "verification": {"status": "not_run"},
    }


def read_json(path):
    with path.open("rb") as file:
        content = file.read(MAX_REPORT_BYTES + 1)
    if len(content) > MAX_REPORT_BYTES:
        raise ValueError("原始报告超过允许的大小。")
    return json.loads(content.decode("utf-8-sig"))


def load_previous(path):
    previous = read_json(path)
    if not isinstance(previous, dict) or previous.get("schema_version") != SCHEMA_VERSION:
        raise ValueError("请选择 NetPilot 生成的 JSON 报告。")
    if previous.get("mode") != "live" or not isinstance(previous.get("target"), str):
        raise ValueError("复测需要一份真实采集的 NetPilot 报告。")
    if not isinstance(previous.get("origin"), dict) or not isinstance(previous.get("diagnosis"), dict):
        raise ValueError("报告缺少采集来源或诊断结果。")
    if not isinstance(previous.get("task_id"), str) or not re.fullmatch(r"[0-9a-f]{32}", previous["task_id"]):
        raise ValueError("报告任务编号无效。")
    return previous


def compare(previous, current):
    status = "uncertain"
    same_origin = (previous["origin"] == current["origin"] and bool(current["origin"]["fingerprint"]))
    prior = previous["diagnosis"].get("status")
    after = current["diagnosis"]["status"]
    if not same_origin:
        status = "different_origin"
    elif after == "no_issue_detected":
        status = "still_accessible" if prior == "no_issue_detected" else "accessible_now"
    return {"status": status, "previous_task_id": previous["task_id"], "baseline": "imported_report"}


def write_new(path, content):
    with path.open("x", encoding="utf-8") as file:
        file.write(content)


def handoff_payload(args):
    baseline = load_previous(args.baseline)
    if target_from(baseline["target"]).url != baseline["target"]:
        raise ValueError("原始报告中的站点地址无效。")
    retest = load_previous(args.retest) if args.retest else None
    if retest:
        verification = retest.get("verification")
        if (not isinstance(verification, dict) or verification.get("previous_task_id") != baseline["task_id"]
                or retest["target"] != baseline["target"]):
            raise ValueError("复测报告与首次任务或目标不匹配。")
    markdown = build_handoff(baseline, retest, read_json(args.remote) if args.remote else None)
    payload = {"ok": True, "mode": "evidence_handoff", "report_markdown": markdown}
    if args.output:
        write_new(args.output, markdown)
        payload["file"] = str(args.output.resolve())
    return payload


def parser():
    cli = argparse.ArgumentParser(description="NetPilot 宿主侧网站连通性只读诊断")
    commands = cli.add_subparsers(dest="command", required=True)
    commands.add_parser("capabilities", help="显示本次脚本的运行环境，不进行网络探测")
    remote = commands.add_parser("remote-service", help="查询已配置的 final-test 预设服务状态")
    remote.add_argument("--device", required=True)
    remote.add_argument("--service", required=True)
    remote.add_argument("--authorize", action="store_true")
    remote.add_argument("--output", type=Path, help="保存结构化远程证据到新文件")
    handoff = commands.add_parser("handoff", help="整理已有证据，生成报障交接单")
    handoff.add_argument("--baseline", required=True, type=Path)
    handoff.add_argument("--retest", type=Path)
    handoff.add_argument("--remote", type=Path)
    handoff.add_argument("--output", type=Path, help="保存 Markdown 到新文件")
    for name in ("diagnose", "retest"):
        action = commands.add_parser(name)
        action.add_argument("--target", required=True)
        action.add_argument("--authorize", action="store_true", help="确认允许从当前环境探测此单一目标")
        action.add_argument("--output-dir", type=Path, help="写入已存在的输出目录")
        action.add_argument("--seconds", default=24, type=int, choices=range(5, 31), metavar="5..30")
        if name == "retest":
            action.add_argument("--input", required=True, type=Path, help="之前生成的 NetPilot JSON 报告")
    return cli


def output(payload):
    print(json.dumps(payload, ensure_ascii=False))


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    args = parser().parse_args()
    if args.command == "capabilities":
        output({"ok": True, "version": VERSION, "origin": collector(), "python": platform.python_version(),
                "windows_context_available": os.name == "nt", "network_probe_performed": False})
        return 0
    if args.command == "handoff":
        try:
            output(handoff_payload(args))
        except (OSError, ValueError) as error:
            output({"ok": False, "error": str(error) if isinstance(error, ValueError) else "无法读取报告或写入新的交接单。"})
            return 2
        return 0
    if not args.authorize:
        output({"ok": False, "error": "需先确认允许探测指定目标，再传入 --authorize。"})
        return 2
    if args.command == "remote-service":
        payload = worker("service_status", [args.device, args.service], 10)
        if "ok" not in payload:
            payload = {"ok": False, "error": payload.get("actual", {}).get("reason", "remote_worker_failed")}
        if payload["ok"] and args.output:
            try:
                write_new(args.output, json.dumps(payload, ensure_ascii=False, indent=2) + "\n")
            except OSError:
                payload["report_export_error"] = "无法写入新的远程证据文件。"
            else:
                payload["file"] = str(args.output.resolve())
        output(payload)
        return 0 if payload["ok"] else 3
    try:
        target = target_from(args.target)
        previous = load_previous(args.input) if args.command == "retest" else None
        if previous and target.url != previous["target"]:
            raise ValueError("复测目标与原报告不一致。")
        if args.output_dir is not None and not args.output_dir.is_dir():
            raise ValueError("请选择已存在的报告目录。")
    except (OSError, ValueError) as error:
        message = str(error) if isinstance(error, ValueError) else "无法读取原始报告。"
        output({"ok": False, "error": message})
        return 2

    report = diagnose(target, args.seconds)
    if previous:
        report["verification"] = compare(previous, report)
    payload = {"ok": True, **report}
    if args.output_dir is not None:
        stem = args.output_dir.resolve() / f"netpilot-{report['task_id']}"
        json_file, markdown_file = stem.with_suffix(".json"), stem.with_suffix(".md")
        try:
            with markdown_file.open("x", encoding="utf-8") as file:
                file.write(render(report))
            with json_file.open("x", encoding="utf-8") as file:
                json.dump(report, file, ensure_ascii=False, indent=2)
                file.write("\n")
        except OSError:
            payload["report_export_error"] = "报告文件未完整写入。请使用返回的结构化结果或更换输出目录。"
        else:
            payload["files"] = {"json": str(json_file), "markdown": str(markdown_file)}
    output(payload)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
