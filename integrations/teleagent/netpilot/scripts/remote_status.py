"""Client for the optional, preconfigured final-test read-only query."""

import http.client
import ipaddress
import json
import os
import re
import uuid
from datetime import datetime
from urllib.parse import urlsplit

IDENTIFIER = re.compile(r"[a-z0-9][a-z0-9-]{0,47}\Z")
TOKEN = re.compile(r"[A-Za-z0-9_-]{32,128}\Z")
STATES = {
    "active", "reloading", "inactive", "failed", "activating", "deactivating",
    "maintenance", "refreshing", "not_found",
}
OUTCOMES = {
    "unavailable": {"ssh_unavailable"},
    "unknown": {"ssh_timeout", "ssh_result_unknown", "systemd_status_unavailable"},
}
MAX_RESPONSE = 8192


def error(reason):
    return {"ok": False, "error": reason}


def connection_from(address):
    if any(character.isspace() or ord(character) < 32 for character in address):
        raise ValueError("remote_url_invalid")
    origin = urlsplit(address)
    port = origin.port
    host = origin.hostname
    if (origin.scheme not in {"https", "http"} or not host or origin.username is not None
            or origin.path not in {"", "/"} or origin.query or origin.fragment
            or any(character in address for character in "\\?#%")):
        raise ValueError("remote_url_invalid")
    if origin.scheme == "https":
        return http.client.HTTPSConnection(host, port, timeout=5)
    if not ipaddress.ip_address(host).is_loopback:
        raise ValueError("remote_https_required")
    return http.client.HTTPConnection(host, port, timeout=5)


def query(device, service):
    if not IDENTIFIER.fullmatch(device) or not IDENTIFIER.fullmatch(service):
        return error("invalid_resource_id")
    address = os.environ.get("NETPILOT_REMOTE_URL", "")
    token = os.environ.get("NETPILOT_REMOTE_TOKEN", "")
    if not address or not TOKEN.fullmatch(token):
        return error("remote_configuration_required")
    try:
        connection = connection_from(address)
    except ValueError as issue:
        reason = str(issue)
        return error(reason if reason in {"remote_url_invalid", "remote_https_required"} else "remote_url_invalid")
    try:
        path = f"/api/v1/netpilot/devices/{device}/services/{service}"
        connection.request("GET", path, headers={"Authorization": f"Bearer {token}", "Accept": "application/json"})
        with connection.getresponse() as response:
            if response.status != 200:
                return {
                    "ok": False, "error": "remote_http_response",
                    "http_status": response.status,
                }
            raw = response.read(MAX_RESPONSE + 1)
            if len(raw) > MAX_RESPONSE:
                return error("remote_response_too_large")
    except (OSError, http.client.HTTPException):
        return error("remote_connection_unavailable")
    finally:
        connection.close()
    try:
        evidence = validated_evidence(json.loads(raw), device, service)
    except (ValueError, TypeError, KeyError):
        return error("remote_evidence_invalid")
    return {"ok": True, "mode": "remote_read_only", "evidence": evidence}


def validated_evidence(data, device, service):
    if (not isinstance(data, dict) or data.get("schemaVersion") != "1.0"
            or data.get("source") != "final-test-ssh"
            or data.get("deviceId") != device or data.get("serviceId") != service):
        raise ValueError("unrecognized source or target")
    if not isinstance(data.get("evidenceId"), str) or not isinstance(data.get("observedAt"), str):
        raise ValueError("missing evidence identity or time")
    evidence_id = str(uuid.UUID(data["evidenceId"]))
    stamp = data["observedAt"]
    observed_at = datetime.fromisoformat(stamp[:-1] + "+00:00" if stamp.endswith("Z") else stamp)
    if observed_at.tzinfo is None:
        raise ValueError("missing observation timezone")
    status, state, reason = data["status"], data.get("serviceState"), data.get("reason")
    if status == "observed":
        if state not in STATES or reason is not None:
            raise ValueError("invalid service state")
    elif state is not None or reason not in OUTCOMES.get(status, ()):
        raise ValueError("invalid observation outcome")
    return {
        "schema_version": "1.0", "evidence_id": evidence_id, "source": "final-test-ssh",
        "origin": {"type": "remote_device", "device_id": device}, "service_id": service,
        "observed_at": observed_at.isoformat(), "status": status, "service_state": state, "reason": reason,
    }
