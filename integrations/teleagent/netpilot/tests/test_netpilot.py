import json
import os
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
CLI = SCRIPTS / "netpilot.py"
sys.path.insert(0, str(SCRIPTS))
from netpilot import compare, target_from
from probes import environment
from reporting import conclude


class Handler(BaseHTTPRequestHandler):
    def do_HEAD(self):
        self.server.paths.append(self.path)
        if self.server.delay:
            time.sleep(self.server.delay)
            return
        self.send_response(self.server.code)
        if self.server.code == 302:
            self.send_header("Location", "/must-not-follow")
        self.end_headers()

    def log_message(self, *args):
        pass


@contextmanager
def website(code=200, delay=0):
    with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
        server.code, server.delay, server.paths = code, delay, []
        serving = threading.Thread(target=server.serve_forever, daemon=True)
        serving.start()
        try:
            yield server, f"http://localhost:{server.server_port}"
        finally:
            server.shutdown()
            serving.join(timeout=3)


class NetPilotTest(unittest.TestCase):
    def cmd(self, *args, expected=0, env=None):
        output = subprocess.run([sys.executable, "-B", str(CLI), *map(str, args)],
                                capture_output=True, encoding="utf-8", env=env, timeout=32)
        self.assertEqual(output.returncode, expected, output.stderr)
        return json.loads(output.stdout)

    def test_capabilities_has_no_probe(self):
        output = self.cmd("capabilities")
        self.assertFalse(output["network_probe_performed"])
        self.assertEqual(output["origin"]["type"], "collector_process")

    def test_single_authorized_target_and_metadata_exclusions(self):
        with website() as (server, url):
            self.cmd("diagnose", "--target", url, expected=2)
            self.assertEqual(server.paths, [])
        for invalid in ["https://user:secret@example.test", "https://example.test/api",
                        "http://[fe80::1]", "http://169.254.169.254", "http://224.0.0.1",
                        "http://100.100.100.200", "http://0.0.0.0", "http://example.test:0",
                        "http://example.test/?token=x", "https://example.test/#", "https://example.test:"]:
            with self.subTest(invalid=invalid):
                with self.assertRaises(ValueError):
                    target_from(invalid)
        self.assertEqual(target_from("HTTPS://EXAMPLE.TEST:443/").url, "https://example.test")
        self.assertEqual(target_from("http://[::1]:8080").url, "http://[::1]:8080")

    def test_live_http_fault_and_recovery_with_reports(self):
        with website(503) as (server, url), tempfile.TemporaryDirectory() as directory:
            initial = self.cmd("diagnose", "--target", url, "--authorize", "--output-dir", directory)
            self.assertEqual(initial["diagnosis"]["category"], "http_server_error")
            self.assertEqual(initial["verification"]["status"], "not_run")
            self.assertEqual(initial["remediation_status"], "not_attempted")
            baseline_json = Path(initial["files"]["json"])
            markdown = Path(initial["files"]["markdown"]).read_text(encoding="utf-8")
            self.assertIn("HTTP 503", markdown)
            self.assertEqual(json.loads(baseline_json.read_text(encoding="utf-8"))["task_id"], initial["task_id"])
            self.assertTrue(all(item["origin"] == initial["origin"] for item in initial["evidence"]))
            self.assertTrue(set(initial["diagnosis"]["evidence_ids"]) <=
                            {item["evidence_id"] for item in initial["evidence"]})
            server.code = 200
            restored = self.cmd("retest", "--target", url, "--input", baseline_json, "--authorize",
                                "--output-dir", directory)
            self.assertEqual(restored["diagnosis"]["status"], "no_issue_detected")
            self.assertEqual(restored["verification"]["status"], "accessible_now")
            self.assertEqual(restored["verification"]["previous_task_id"], initial["task_id"])
            self.assertEqual(server.paths, ["/", "/"])
            remote = Path(directory) / "remote.json"
            remote.write_text(json.dumps({"ok": True, "evidence": {
                "schema_version": "1.0", "source": "final-test-ssh",
                "origin": {"type": "remote_device", "device_id": "fixture"},
                "service_id": "site", "observed_at": "2026-11-24T00:00:00+00:00",
                "status": "observed", "service_state": "active",
                "evidence_id": "2f3978ba-655c-4929-a412-55324c87d85a",
            }}), encoding="utf-8")
            merged = self.cmd("handoff", "--baseline", baseline_json, "--retest", restored["files"]["json"],
                              "--remote", remote)
            for text in ("HTTP 503", "HTTP 200", "首次/", "复测/", "单独的服务器观测", "fixture/site"):
                self.assertIn(text, merged["report_markdown"])
            broken_link = Path(directory) / "unrelated.json"
            restored["verification"]["previous_task_id"] = "0" * 32
            broken_link.write_text(json.dumps(restored), encoding="utf-8")
            self.cmd("handoff", "--baseline", baseline_json, "--retest", broken_link, expected=2)

    def test_redirect_stays_on_approved_origin(self):
        with website(302) as (server, url):
            report = self.cmd("diagnose", "--target", url, "--authorize")
        self.assertEqual(report["diagnosis"]["status"], "inconclusive")
        self.assertEqual(server.paths, ["/"])
        self.assertNotIn("must-not-follow", json.dumps(report))

    def test_refused_connection_collects_context_and_hides_proxy_credentials(self):
        with socket.socket() as socket_before_listen:
            socket_before_listen.bind(("127.0.0.1", 0))
            target = f"http://127.0.0.1:{socket_before_listen.getsockname()[1]}"
            report = self.cmd("diagnose", "--target", target, "--authorize",
                              env={**os.environ, "HTTPS_PROXY": "http://tester:hidden@example.test"})
        self.assertEqual(report["diagnosis"]["status"], "inconclusive")
        self.assertTrue(any(item["probe"] == "proxy_settings" for item in report["evidence"]))
        self.assertNotIn("tester", json.dumps(report))
        self.assertNotIn("hidden", json.dumps(report))

    def test_slow_server_has_bounded_probe(self):
        with website(delay=3) as (_, url):
            started = time.monotonic()
            report = self.cmd("diagnose", "--target", url, "--authorize", "--seconds", 7)
            self.assertLess(time.monotonic() - started, 10)
        self.assertTrue(any(item["status"] == "timeout" for item in report["evidence"]))
        self.assertEqual(report["diagnosis"]["status"], "inconclusive")

    def test_rules_preserve_evidence_and_uncertainty(self):
        def observation(probe, status, evidence_id, **actual):
            return {"probe": probe, "status": status, "evidence_id": evidence_id, "actual": actual}

        dns = observation("system_resolution", "fail", "dns")
        missing_route = observation("ip_gateway", "pass", "ip", ipv4_links_only=True,
                                    ipv4_default_routes=0, ipv6_default_routes=0)
        ipv6_route = observation("ip_gateway", "pass", "ipv6", ipv4_links_only=True,
                                 ipv4_default_routes=0, ipv6_default_routes=1)
        cert = observation("tls", "fail", "cert", reason="certificate_verification_failed")
        self.assertEqual(conclude([dns, ipv6_route], "https")["category"], "name_resolution")
        self.assertEqual(conclude([dns, missing_route], "https")["evidence_ids"], ["ip"])
        self.assertEqual(conclude([dns, cert], "https")["category"], "tls_validation")

    def test_comparison_requires_same_collector_and_same_target(self):
        prior = {"task_id": "0" * 32, "origin": {"fingerprint": "old"},
                 "diagnosis": {"status": "suspected"}}
        current = {"origin": {"fingerprint": "new"}, "diagnosis": {"status": "no_issue_detected"}}
        self.assertEqual(compare(prior, current)["status"], "different_origin")
        with tempfile.TemporaryDirectory() as path, website() as (server, url):
            baseline = Path(path) / "original.json"
            baseline.write_text(json.dumps({**prior, "schema_version": "1.0", "mode": "live",
                                            "target": "http://127.0.0.1:1"}), encoding="utf-8")
            self.cmd("retest", "--target", url, "--input", baseline, "--authorize", expected=2)
            self.assertEqual(server.paths, [])

    def test_proxy_fallback_when_windows_collection_times_out(self):
        with patch("probes.sys.platform", "win32"), patch("probes.ctypes.windll", create=True) as win:
            win.kernel32.GetSystemDirectoryW.return_value = 1
            with patch("probes.subprocess.run", side_effect=subprocess.TimeoutExpired([], 1)):
                output = environment(1)
        self.assertEqual(output["ip_gateway"]["status"], "timeout")
        self.assertEqual(output["proxy_settings"]["actual"]["windows_settings"], "timeout")


if __name__ == "__main__":
    unittest.main()
