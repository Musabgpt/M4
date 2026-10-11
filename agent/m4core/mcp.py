"""Version-negotiated MCP JSON-RPC: stdio and Streamable HTTP (not legacy SSE)."""
import json
import queue
import re
import subprocess
import threading
import os
import signal
from urllib.parse import urlsplit
import urllib.request
from .registry import Registry, tool

VERSIONS = {"2025-06-18", "2025-03-26", "2024-11-05"}
class MCPConnection:
    def __init__(self, config, root, environment):
        self.config, self.root, self.sequence, self.process = config, root, 0, None
        self.session, self.version = None, "2025-06-18"
        if config["transport"] == "stdio":
            self.process = subprocess.Popen(config["command"], cwd=root, env=environment, stdin=subprocess.PIPE,
                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, bufsize=1, start_new_session=True)
            self.messages = queue.Queue(maxsize=50)
            def reader():
                try:
                    for line in iter(self.process.stdout.readline, ""):
                        if len(line) > 1000000: break
                        value = json.loads(line); self.messages.put(value, timeout=1)
                except (ValueError, queue.Full): pass
                finally:
                    try: self.messages.put({"error": {"message": "MCP stdio closed"}}, timeout=1)
                    except queue.Full: pass
            threading.Thread(target=reader, daemon=True).start()
        result = self.rpc("initialize", {"protocolVersion": self.version, "capabilities": {}, "clientInfo": {"name": "M4", "version": "2.0"}})
        if result.get("protocolVersion") not in VERSIONS: self.close(); raise ValueError("Unsupported MCP protocol version")
        self.version = result["protocolVersion"]
        self.rpc("notifications/initialized", notification=True)

    def rpc(self, method, params=None, notification=False):
        self.sequence += 1
        message = {"jsonrpc": "2.0", "method": method}
        if params is not None: message["params"] = params
        if not notification: message["id"] = self.sequence
        if self.process:
            self.process.stdin.write(json.dumps(message) + "\n"); self.process.stdin.flush()
            if notification: return {}
            for _ in range(50):
                try: response = self.messages.get(timeout=20)
                except queue.Empty: raise ValueError("MCP response timed out")
                if "method" in response:
                    if "id" in response:
                        self.process.stdin.write(json.dumps({"jsonrpc": "2.0", "id": response["id"], "error": {"code": -32601, "message": "Client does not accept server-initiated execution or sampling"}}) + "\n"); self.process.stdin.flush()
                    continue
                if response.get("id") != message["id"] and not response.get("error"): continue
                break
            else: raise ValueError("MCP notification quota exceeded")
        else:
            headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream", "MCP-Protocol-Version": self.version}
            if self.session: headers["Mcp-Session-Id"] = self.session
            req = urllib.request.Request(self.config["url"], json.dumps(message).encode(), headers, method="POST")
            with urllib.request.urlopen(req, timeout=20) as stream:
                self.session = stream.headers.get("Mcp-Session-Id") or self.session
                if stream.status == 202 and notification: return {}
                data = stream.read(1000001)
                if len(data) > 1000000: raise ValueError("MCP response too large")
                if "text/event-stream" in stream.headers.get("Content-Type", ""):
                    values = [json.loads(line[5:].strip()) for line in data.decode().splitlines() if line.startswith("data:")]
                    response = next((v for v in values if v.get("id") == message.get("id")), None)
                    if response is None: raise ValueError("MCP SSE has no matching response")
                else: response = json.loads(data) if data else {}
        if response.get("error"): raise ValueError("MCP error: " + str(response["error"].get("message", ""))[:400])
        if notification: return {}
        if response.get("id") != message["id"] or response.get("jsonrpc") != "2.0": raise ValueError("Mismatched MCP response")
        return response.get("result", {})

    def close(self):
        if self.process:
            try:
                if self.process.poll() is None: os.killpg(self.process.pid, signal.SIGTERM)
                self.process.wait(timeout=2)
            except subprocess.TimeoutExpired: os.killpg(self.process.pid, signal.SIGKILL); self.process.wait(timeout=2)
            finally:
                self.process.stdin.close(); self.process.stdout.close(); self.process = None

class MCPManager:
    def __init__(self, runtime): self.runtime, self.servers, self.names = runtime, {}, {}
    def connect(self, args):
        self.runtime._permission(execution=True)
        name = args["name"]
        if not re.fullmatch(r"[a-z][a-z0-9_]{1,40}", name): raise ValueError("Invalid MCP namespace")
        if name in self.servers: raise ValueError("MCP already connected")
        config = dict(args)
        if args["transport"] == "stdio":
            command = args.get("command", [])
            if not command or command[0] not in self.runtime.shells.commands or "/" in command[0]: raise ValueError("MCP executable not in approved scope")
        else:
            url = urlsplit(args.get("url", ""))
            if url.username or url.password or not url.hostname or not (url.scheme == "https" or url.scheme == "http" and url.hostname in {"127.0.0.1", "localhost"}): raise ValueError("MCP URL requires HTTPS or approved loopback")
        from .runtime import child_environment
        connection = MCPConnection(config, self.runtime.root, child_environment())
        entries = []; cursor = None
        try:
            for _ in range(4):
                page = connection.rpc("tools/list", {"cursor": cursor} if cursor else {})
                entries.extend(page.get("tools", [])); cursor = page.get("nextCursor")
                if len(entries) > 100: raise ValueError("MCP tool quota exceeded")
                if not cursor: break
            else: raise ValueError("MCP pagination quota exceeded")
            registered = []
            for entry in entries:
                original = entry["name"]
                if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_.-]{0,60}", original): raise ValueError("Invalid MCP tool name")
                full = "mcp__" + name + "__" + re.sub(r"[^A-Za-z0-9_]", "_", original)
                spec = entry.get("inputSchema")
                if not isinstance(spec, dict) or spec.get("type") != "object": raise ValueError("MCP input must be a JSON object schema")
                descriptor = tool(full, "Untrusted MCP " + str(entry.get("description", ""))[:1000]); descriptor["function"]["parameters"] = spec
                self.runtime.registry.register(descriptor, lambda a, n=name, t=original: self.invoke(n, t, a),
                    {"risk_level": "external", "capabilities": ["mcp:" + name], "requires_user_consent": True, "version": connection.version, "timeout": 20})
                registered.append(full)
            self.servers[name], self.names[name] = connection, registered
            path = self.runtime.state / "mcp-connections.json"
            configurations = json.loads(path.read_text()) if path.exists() else {}
            configurations[name] = config; path.write_text(json.dumps(configurations))
            return {"status": "connected", "name": name, "protocol": connection.version, "tools": registered}
        except BaseException:
            connection.close()
            for full in locals().get("registered", []): self.runtime.registry.unregister(full)
            raise
    def invoke(self, name, tool_name, args):
        self.runtime._permission(execution=True)
        if self.runtime.capabilities.get("permission_broker") and not self.runtime.bridge.request("approve", {"tool": "MCP " + name + "/" + tool_name, "arguments": args}).get("approved"):
            raise ValueError("User denied MCP tool")
        result = self.servers[name].rpc("tools/call", {"name": tool_name, "arguments": args})
        return {"untrusted": True, "ok": not result.get("isError", False), "server": name, "result": result}
    def disconnect(self, args):
        self.runtime._permission(mutation=True)
        name = args["name"]
        if name not in self.servers: raise ValueError("MCP not connected")
        self.servers.pop(name).close()
        for tool_name in self.names.pop(name): self.runtime.registry.unregister(tool_name)
        path = self.runtime.state / "mcp-connections.json"; data = json.loads(path.read_text()) if path.exists() else {}; data.pop(name, None); path.write_text(json.dumps(data))
        return {"status": "disconnected", "name": name}
    def close(self):
        for connection in self.servers.values(): connection.close()
        self.servers.clear()
