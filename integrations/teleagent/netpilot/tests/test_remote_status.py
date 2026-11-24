import json
import os
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import test_netpilot
from remote_status import connection_from

TOKEN = "fixture-token-" + "a" * 32


class QueryHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.server.calls.append(self.path)
        self.server.auth = self.headers.get("Authorization")
        self.send_response(self.server.code)
        if self.server.code == 302:
            self.send_header("Location", "/forbidden-redirect")
        self.end_headers()
        if self.server.evidence is not None:
            self.wfile.write(json.dumps(self.server.evidence).encode())

    def log_message(self, *args):
        pass


class RemoteStatusTest(unittest.TestCase):
    def call(self, code, evidence=None, expected=0):
        with ThreadingHTTPServer(("127.0.0.1", 0), QueryHandler) as server:
            server.code, server.evidence, server.calls = code, evidence, []
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                env = {
                    **os.environ, "NETPILOT_REMOTE_URL": f"http://127.0.0.1:{server.server_port}",
                    "NETPILOT_REMOTE_TOKEN": TOKEN,
                }
                api = test_netpilot.NetPilotTest()
                output = api.cmd("remote-service", "--device", "lab", "--service", "web",
                                 "--authorize", env=env, expected=expected)
                self.assertEqual(server.calls, ["/api/v1/netpilot/devices/lab/services/web"])
                self.assertEqual(server.auth, f"Bearer {TOKEN}")
                return output
            finally:
                server.shutdown()
                thread.join(timeout=3)

    @staticmethod
    def evidence():
        return {
            "schemaVersion": "1.0", "source": "final-test-ssh", "deviceId": "lab",
            "serviceId": "web", "evidenceId": "59724627-81b6-4b54-8f81-64b3fd0985af",
            "observedAt": "2026-11-24T00:00:00.123Z", "status": "observed",
            "serviceState": "active", "internal": "private-body-value",
        }

    def test_observation_is_scoped_and_projected(self):
        output = self.call(200, self.evidence())
        self.assertEqual(output["evidence"]["origin"], {"type": "remote_device", "device_id": "lab"})
        self.assertEqual(output["evidence"]["service_state"], "active")
        self.assertNotIn("private-body-value", json.dumps(output))
        self.assertNotIn(TOKEN, json.dumps(output))

    def test_rejects_evidence_for_a_different_device(self):
        invalid = {**self.evidence(), "deviceId": "other"}
        self.assertEqual(self.call(200, invalid, expected=3)["error"], "remote_evidence_invalid")

    def test_never_follows_redirect_with_token(self):
        self.assertEqual(self.call(302, expected=3)["http_status"], 302)

    def test_plain_http_requires_numeric_loopback(self):
        for address in ("http://10.0.0.1", "http://localhost", "https://user:password@example.test"):
            with self.subTest(address=address):
                with self.assertRaises(ValueError):
                    connection_from(address)
