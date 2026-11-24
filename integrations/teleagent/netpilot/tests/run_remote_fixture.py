"""Run NetPilot through real loopback HTTP and SSH with a fixed systemd response.

This fixture requires Paramiko in the test interpreter and an existing local Maven cache.
The SSH fixture accepts only the generated client key and the single status query.
"""

import os
import shutil
import socketserver
import subprocess
import sys
import tempfile
import threading
from pathlib import Path

import paramiko

EXPECTED = b"systemctl --no-pager show --property=LoadState --property=ActiveState -- fixture.service"


class Session(paramiko.ServerInterface):
    def __init__(self, server):
        self.server = server
        self.requested = threading.Event()

    def get_allowed_auths(self, username):
        return "publickey"

    def check_auth_publickey(self, username, key):
        accepted = username == "fixture" and key == self.server.client_key
        return paramiko.AUTH_SUCCESSFUL if accepted else paramiko.AUTH_FAILED

    def check_channel_request(self, kind, channel_id):
        return paramiko.OPEN_SUCCEEDED if kind == "session" else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED

    def check_channel_exec_request(self, channel, command):
        self.server.commands.append(command)
        if command != EXPECTED:
            return False
        self.requested.set()
        return True


class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        with paramiko.Transport(self.request) as transport:
            transport.add_server_key(self.server.host_key)
            session = Session(self.server)
            try:
                transport.start_server(server=session)
                channel = transport.accept(6)
                if channel is None or not session.requested.wait(3):
                    return
                with channel:
                    channel.sendall(b"LoadState=loaded\nActiveState=active\n")
                    channel.send_exit_status(0)
                    channel.shutdown_write()
            except (EOFError, OSError, paramiko.SSHException):
                return


class SshFixture(socketserver.ThreadingTCPServer):
    daemon_threads = True


def main():
    project = next(path for path in Path(__file__).resolve().parents if (path / "server/pom.xml").is_file())
    maven = shutil.which("mvn.cmd") or shutil.which("mvn")
    if not maven:
        raise FileNotFoundError("Maven is required for the remote fixture")
    with tempfile.TemporaryDirectory(prefix="netpilot-ssh-") as directory, SshFixture(("127.0.0.1", 0), Handler) as server:
        folder = Path(directory)
        server.client_key = paramiko.ECDSAKey.generate()
        server.host_key = paramiko.ECDSAKey.generate()
        server.commands = []
        port = server.server_address[1]
        identity = folder / "client.pem"
        server.client_key.write_private_key_file(str(identity))
        known = folder / "known_hosts"
        wrong = folder / "wrong_hosts"
        for path, key in ((known, server.host_key), (wrong, paramiko.ECDSAKey.generate())):
            path.write_text(f"[127.0.0.1]:{port} {key.get_name()} {key.get_base64()}\n", encoding="ascii")
        env = {
            **os.environ, "NETPILOT_FIXTURE_SSH_PORT": str(port),
            "NETPILOT_FIXTURE_KNOWN_HOSTS": str(known), "NETPILOT_FIXTURE_OTHER_HOSTS": str(wrong),
            "NETPILOT_FIXTURE_CLIENT_KEY": str(identity), "NETPILOT_FIXTURE_PYTHON": sys.executable,
            "NETPILOT_FIXTURE_CLI": str(project / "integrations/teleagent/netpilot/scripts/netpilot.py"),
        }
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            subprocess.run([
                maven, "-o", "-s", "../maven-settings.xml", "-pl", "nox-shell-server-app", "-am",
                "-Dtest=NetPilotStatusControllerTest,SshExecCommandRunnerTest,ChatExecutionModeTest",
                "-DfailIfNoTests=false", "-Dsurefire.failIfNoSpecifiedTests=false", "-DskipTests=false", "test",
            ], cwd=project / "server", env=env, check=True)
        finally:
            server.shutdown()
            thread.join(timeout=3)
        if server.commands != [EXPECTED]:
            raise AssertionError("Only the one configured SSH query should reach the fixture")
        print("Loopback HTTP and SSH passed; incorrect host key did not dispatch the command.")


if __name__ == "__main__":
    main()
