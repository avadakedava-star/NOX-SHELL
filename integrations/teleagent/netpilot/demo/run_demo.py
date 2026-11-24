"""Replay a controlled website incident and export evidence for the TeleAgent demo."""

import argparse
import json
import subprocess
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

CLI = Path(__file__).resolve().parents[1] / "scripts" / "netpilot.py"


class Site(BaseHTTPRequestHandler):
    def do_HEAD(self):
        code = self.server.demo_status if self.path == "/" else 404
        self.server.responses.append(code)
        self.send_response(code)
        self.end_headers()

    def log_message(self, *args):
        pass


def run(*args):
    process = subprocess.run([sys.executable, "-B", str(CLI), *map(str, args)],
                             capture_output=True, encoding="utf-8", check=True, timeout=35)
    return json.loads(process.stdout)


def observed_http(report, label):
    item = next(item for item in report["evidence"] if item["probe"] == "http" and item["status"] == "pass")
    return {"stage": label, "evidence_id": item["evidence_id"], "status_code": item["actual"]["status_code"]}


def demo(root):
    output = Path(tempfile.mkdtemp(prefix="netpilot-demo-", dir=root.resolve()))
    with ThreadingHTTPServer(("127.0.0.1", 0), Site) as server:
        server.demo_status = 503
        server.responses = []
        serving = threading.Thread(target=server.serve_forever, daemon=True)
        serving.start()
        try:
            target = f"http://127.0.0.1:{server.server_port}"
            first = run("diagnose", "--target", target, "--authorize", "--output-dir", output)
            server.demo_status = 200
            second = run("retest", "--target", target, "--input", first["files"]["json"],
                         "--authorize", "--output-dir", output)
            handoff = run("handoff", "--baseline", first["files"]["json"], "--retest", second["files"]["json"],
                          "--output", output / "报障交接单.md")
            if server.responses != [503, 200] or second["verification"]["status"] != "accessible_now":
                raise RuntimeError("实测响应或复测状态与演示预期不一致。")
        finally:
            server.shutdown()
            serving.join(timeout=3)

    evidence = {
        "source": "Windows 本机回环实验服务，真实 HEAD 响应",
        "target": "同一个本机实验服务的根路径",
        "measurements": [observed_http(first, "首次"), observed_http(second, "复测")],
        "comparison": {"same_target": True, "same_collector": True, "paired_reports": True},
        "scope": "本机隔离实验的两次观测",
    }
    prompt = (
        "请按 NetPilot 的证据导入流程，直接根据下方验收证据写一份简短报障交接单。"
        "请只在聊天中填写结论、证据编号、采集范围和下一步，不运行任何工具、命令或网络查询。"
        "请注明证据由本机实验提供，不把结论扩展到校园网络。\n\n"
        + json.dumps(evidence, ensure_ascii=False, indent=2) + "\n"
    )
    (output / "TeleAgent-演示输入.txt").write_text(prompt, encoding="utf-8")
    (output / "演示实测摘要.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return {"ok": True, "evidence_directory": str(output), "first_status": 503, "retest_status": 200,
            "handoff": handoff["file"]}


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    cli = argparse.ArgumentParser(description="运行 NetPilot 本机故障与复测演示")
    cli.add_argument("--output-dir", type=Path, default=Path.cwd(), help="已存在的目录")
    options = cli.parse_args()
    if not options.output_dir.is_dir():
        cli.error("请选择已存在的输出目录。")
    print(json.dumps(demo(options.output_dir), ensure_ascii=False))
