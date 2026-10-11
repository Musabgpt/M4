"""Authenticated app-private Unix socket; credentials never enter model context."""
import json
import os
from pathlib import Path
import socket


class AndroidBridge:
    def __init__(self, config_path=None):
        self.config_path = Path(config_path or os.environ.get(
            "M4_BRIDGE_CONFIG", "/root/.m4/run/bridge.json"))

    def request(self, action, arguments=None):
        config = json.loads(self.config_path.read_text(encoding="utf-8"))
        # Session identity is supplied by the host, never accepted from model args.
        request = json.dumps({"token": config["token"], "action": action,
                              "owner_session_id": os.environ.get("M4_OWNER_SESSION", "terminal"),
                              "turn_id": os.environ.get("M4_TURN_ID", "terminal"),
                              "arguments": arguments or {}}, ensure_ascii=False).encode() + b"\n"
        if len(request) > 1000000:
            raise ValueError("Bridge request too large")
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as connection:
            connection.settimeout(180)
            connection.connect(config["socket"])
            connection.sendall(request)
            data = bytearray()
            while b"\n" not in data:
                chunk = connection.recv(4096)
                if not chunk:
                    raise OSError("Android bridge closed without a result")
                data.extend(chunk)
                if len(data) > 1000000:
                    raise ValueError("Bridge response too large")
        result = json.loads(bytes(data).split(b"\n", 1)[0])
        if not result.get("ok"):
            raise ValueError(result.get("error", "Bridge action failed"))
        return result["result"]

    def capabilities(self):
        try:
            return self.request("capabilities")
        except (OSError, ValueError, KeyError):
            return {}
