"""Bounded, read-only observations used by the NetPilot collector."""

import ctypes
import http.client
import ipaddress
import json
import os
import socket
import ssl
import subprocess
import sys
from pathlib import Path

WINDOWS_CONTEXT = r"""
$ErrorActionPreference = 'Stop'
$result = @{}
try {
    $interfaces = @(Get-NetIPConfiguration)
    $active = @($interfaces | Where-Object { $_.NetAdapter.Status -eq 'Up' })
    $ipv4 = @($active | ForEach-Object { $_.IPv4Address.IPAddress } | Where-Object { $_ })
    $result.ip = @{
        active_adapters = $active.Count
        ipv4_links_only = ($ipv4.Count -gt 0 -and @($ipv4 | Where-Object { $_ -notlike '169.254.*' }).Count -eq 0)
        ipv4_default_routes = @(Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue).Count
        ipv6_default_routes = @(Get-NetRoute -DestinationPrefix '::/0' -ErrorAction SilentlyContinue).Count
    }
} catch { $result.ip = $null }
try {
    $settings = Get-ItemProperty -LiteralPath 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
    $result.proxy = @{
        windows_manual = ($settings.ProxyEnable -eq 1)
        windows_pac = -not [string]::IsNullOrWhiteSpace($settings.AutoConfigURL)
    }
} catch { $result.proxy = $null }
$result | ConvertTo-Json -Depth 3 -Compress
"""


def result(status, **actual):
    return {"status": status, "actual": actual}


def worker(operation, arguments, timeout):
    command = [sys.executable, "-B", str(Path(__file__).resolve()), operation, *arguments]
    try:
        process = subprocess.run(command, capture_output=True, timeout=timeout, check=False)
    except subprocess.TimeoutExpired:
        return result("timeout", reason="probe_deadline")
    except OSError:
        return result("error", reason="worker_unavailable")
    if process.returncode:
        return result("error", reason="worker_failed")
    try:
        return json.loads(process.stdout)
    except ValueError:
        return result("error", reason="invalid_worker_output")


def allowed_address(value):
    address = ipaddress.ip_address(value)
    address = getattr(address, "ipv4_mapped", None) or address
    return (
        not (address.is_unspecified or address.is_multicast or address.is_link_local)
        and str(address) not in {"255.255.255.255", "100.100.100.200", "fd00:ec2::254"}
    )


def resolve(host, port):
    try:
        entries = socket.getaddrinfo(host, int(port), type=socket.SOCK_STREAM)
    except socket.gaierror as error:
        return result("fail", reason="system_resolution_failed", error_code=error.errno)
    addresses = list(dict.fromkeys(entry[4][0] for entry in entries))
    permitted = [address for address in addresses if allowed_address(address)]
    if not permitted:
        return result("denied", reason="no_permitted_address")
    return result("pass", address_count=len(addresses), candidates=permitted[:2])


def endpoint(scheme, host, port, address, seconds):
    port = int(port)
    deadline = float(seconds)
    stage = "tcp"
    if not allowed_address(address):
        return {"tcp": result("denied", reason="address_out_of_scope")}
    observations = {}
    try:
        with socket.create_connection((address, port), timeout=deadline) as connection:
            observations["tcp"] = result("pass")
            if scheme == "https":
                stage = "tls"
                context = ssl.create_default_context()
                with context.wrap_socket(connection, server_hostname=host) as secured:
                    observations["tls"] = result("pass", protocol=secured.version())
                    stage = "http"
                    observations["http"] = read_headers(secured, scheme, host, port)
            else:
                stage = "http"
                observations["http"] = read_headers(connection, scheme, host, port)
    except ssl.SSLCertVerificationError as error:
        observations[stage] = result("fail", reason="certificate_verification_failed", code=error.verify_code)
    except TimeoutError:
        observations[stage] = result("timeout", reason="socket_timeout")
    except ConnectionRefusedError:
        observations[stage] = result("fail", reason="connection_refused")
    except (OSError, http.client.HTTPException) as error:
        observations[stage] = result("fail", reason=type(error).__name__)
    return observations


def read_headers(connection, scheme, host, port):
    authority = f"[{host}]" if ":" in host else host
    if (scheme, port) not in {("http", 80), ("https", 443)}:
        authority = f"{authority}:{port}"
    request = f"HEAD / HTTP/1.1\r\nHost: {authority}\r\nConnection: close\r\nUser-Agent: NetPilot/0.1\r\n\r\n"
    connection.sendall(request.encode("ascii"))
    with http.client.HTTPResponse(connection, method="HEAD") as response:
        response.begin()
        return result("pass", status_code=response.status)


def environment(seconds):
    settings = {
        "process_http_proxy": bool(os.environ.get("HTTP_PROXY") or os.environ.get("http_proxy")
                                   or os.environ.get("ALL_PROXY") or os.environ.get("all_proxy")),
        "process_https_proxy": bool(os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy")
                                    or os.environ.get("ALL_PROXY") or os.environ.get("all_proxy")),
    }
    if sys.platform != "win32":
        return {"ip_gateway": result("unsupported", reason="windows_collector_required"),
                "proxy_settings": result("pass", **settings, windows_settings="unsupported")}
    try:
        system = ctypes.create_unicode_buffer(32768)
        if not ctypes.windll.kernel32.GetSystemDirectoryW(system, len(system)):
            raise OSError("Cannot locate system directory")
        powershell = Path(system.value) / "WindowsPowerShell" / "v1.0" / "powershell.exe"
        process = subprocess.run(
            [str(powershell), "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", WINDOWS_CONTEXT],
            capture_output=True, timeout=seconds, check=True,
        )
        data = json.loads(process.stdout)
    except subprocess.TimeoutExpired:
        return {"ip_gateway": result("timeout", reason="powershell_deadline"),
                "proxy_settings": result("pass", **settings, windows_settings="timeout")}
    except (OSError, ValueError, subprocess.CalledProcessError):
        return {"ip_gateway": result("error", reason="windows_snapshot_unavailable"),
                "proxy_settings": result("pass", **settings, windows_settings="unavailable")}
    ip = result("pass", **data["ip"]) if data.get("ip") else result("unsupported", reason="ip_cmdlets_unavailable")
    proxy = result("pass", **settings, **data["proxy"]) if data.get("proxy") is not None else (
        result("pass", **settings, windows_settings="unavailable")
    )
    return {"ip_gateway": ip, "proxy_settings": proxy}


if __name__ == "__main__":
    from remote_status import query

    operations = {"resolve": resolve, "endpoint": endpoint, "service_status": query}
    operation, *arguments = sys.argv[1:]
    print(json.dumps(operations[operation](*arguments), ensure_ascii=True))
