"""Call the deployed final-test API directly."""

import argparse
import json
import os
import sys
from http.client import HTTPConnection, HTTPException
from pathlib import Path


SERVER_HOST = os.environ.get("FINAL_TEST_HOST", "127.0.0.1:8091")


def request(path, payload=None, timeout=8):
    api_path = f"/api/v1/{path}"
    connection = HTTPConnection(SERVER_HOST, timeout=timeout)
    headers = {"Accept": "application/json"}
    method = "GET"
    body = None
    if payload is not None:
        method = "POST"
        headers["Content-Type"] = "application/json; charset=utf-8"
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")

    try:
        connection.request(method, api_path, body=body, headers=headers)
        response = connection.getresponse()
        if response.status != 200:
            raise ValueError(f"final-test 返回 HTTP {response.status}")
        result = json.load(response)
    except (OSError, HTTPException) as error:
        raise ValueError(f"直连 final-test 失败（http://{SERVER_HOST}{api_path}）：{error}") from error
    finally:
        connection.close()

    if not isinstance(result, dict):
        raise ValueError("final-test 返回了无法识别的响应")
    if result.get("code") != "0000":
        raise ValueError(f"final-test 返回错误 {result.get('code')}：{result.get('info')}")
    return result.get("data")


def agents():
    result = request("query_ai_agent_config_list")
    if not isinstance(result, list) or any(
        not isinstance(agent, dict) or not agent.get("agentId") for agent in result
    ):
        raise ValueError("final-test 返回了无法识别的 Agent 列表")
    return result


def chat(message_file):
    message = message_file.read_text(encoding="utf-8-sig").strip()
    if not message:
        raise ValueError("请提供要交给 final-test 的文字")

    agent = next((item for item in agents() if item["agentId"] == "100000"), None)
    if agent is None:
        raise ValueError("final-test 未返回当前接入配置所用的 Agent（100000）")

    result = request(
        "chat",
        {
            "agentId": agent["agentId"],
            "userId": "teleagent-local",
            "message": message,
            "executionMode": "standard",
            "permissionMode": "approval",
        },
        timeout=45,
    )
    content = result.get("content") if isinstance(result, dict) else None
    if not isinstance(content, str) or not content.strip():
        raise ValueError("final-test 没有返回文字回复")
    return content


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("status")
    chat_parser = commands.add_parser("chat")
    chat_parser.add_argument("--message-file", required=True, type=Path)
    options = parser.parse_args()

    sys.stdout.reconfigure(encoding="utf-8")
    try:
        if options.command == "status":
            result = {"ok": True, "agents": agents()}
        else:
            result = {"ok": True, "content": chat(options.message_file)}
    except (OSError, ValueError) as error:
        print(json.dumps({"ok": False, "error": str(error)}, ensure_ascii=False))
        return 1
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
