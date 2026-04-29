#!/usr/bin/env python3
import json
import os
import signal
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import LXMF
import RNS


def env(name, default=""):
    return os.environ.get(name, default)


BRIDGE_HOST = env("LXMF_BRIDGE_LISTEN_HOST", env("LXMF_BRIDGE_HOST", "0.0.0.0"))
BRIDGE_PORT = int(env("LXMF_BRIDGE_LISTEN_PORT", "8091"))
BRIDGE_SECRET = env("LXMF_BRIDGE_WEBHOOK_SECRET", "")
AGENT_BASE_URL = env(
    "AGENT_BASE_URL",
    "http://bluebubbles-chatgpt-agent.bluebubbles-chatgpt-agent.svc.cluster.local",
).rstrip("/")
RNS_CONFIG_DIR = Path(env("RNS_CONFIG_DIR", "/var/lib/lxmf-bridge/reticulum"))
LXMF_STORAGE_DIR = Path(env("LXMF_STORAGE_DIR", "/var/lib/lxmf-bridge/lxmf"))
IDENTITY_PATH = Path(env("LXMF_IDENTITY_PATH", str(LXMF_STORAGE_DIR / "identity")))
DISPLAY_NAME = env("LXMF_DISPLAY_NAME", "BlueBubbles ChatGPT Agent")
STAMP_COST = int(env("LXMF_STAMP_COST", "8"))
ANNOUNCE_INTERVAL_SECONDS = int(env("LXMF_ANNOUNCE_INTERVAL_SECONDS", "900"))
SEND_PATH_TIMEOUT_SECONDS = int(env("LXMF_SEND_PATH_TIMEOUT_SECONDS", "30"))
OUTBOUND_METHOD = env("LXMF_OUTBOUND_METHOD", "direct").lower()
RNS_BACKBONE_REMOTE = env("RNS_BACKBONE_REMOTE", "rnode-public.rnode.svc.cluster.local")
RNS_BACKBONE_PORT = int(env("RNS_BACKBONE_PORT", "4242"))

router = None
local_destination = None
stop_event = threading.Event()


def log(message):
    print(message, flush=True)


def write_default_rns_config():
    config_path = RNS_CONFIG_DIR / "config"
    if config_path.exists():
        return
    RNS_CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    config_path.write_text(
        f"""[reticulum]
enable_transport = no
panic_on_interface_error = yes
respond_to_probes = yes

[interfaces]

[[Cluster RNode Backbone]]
  type = BackboneInterface
  enabled = yes
  mode = boundary
  remote = {RNS_BACKBONE_REMOTE}
  target_port = {RNS_BACKBONE_PORT}
""",
        encoding="utf-8",
    )


def load_or_create_identity():
    IDENTITY_PATH.parent.mkdir(parents=True, exist_ok=True)
    if IDENTITY_PATH.exists():
        return RNS.Identity.from_file(str(IDENTITY_PATH))
    identity = RNS.Identity()
    identity.to_file(str(IDENTITY_PATH))
    return identity


def hex_bytes(value):
    if value is None:
        return None
    if isinstance(value, bytes):
        return value.hex()
    return str(value)


def message_id(message):
    value = getattr(message, "message_id", None) or getattr(message, "hash", None)
    return hex_bytes(value)


def fields_for_json(fields):
    if fields is None:
        return {}
    result = {}
    for key, value in fields.items():
        if isinstance(value, bytes):
            result[str(key)] = value.hex()
        else:
            result[str(key)] = value
    return result


def post_to_agent(message):
    timestamp = message.timestamp if message.timestamp is not None else time.time()
    payload = {
        "message_id": message_id(message),
        "source_hash": hex_bytes(message.source_hash),
        "destination_hash": hex_bytes(message.destination_hash),
        "title": message.title_as_string(),
        "content": message.content_as_string() or "",
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(timestamp)),
        "fields": fields_for_json(message.fields),
        "signature_validated": bool(message.signature_validated),
        "stamp_valid": bool(message.stamp_valid),
    }
    data = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        AGENT_BASE_URL + "/api/v1/lxmf/receive.messages",
        data=data,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "X-LXMF-Bridge-Secret": BRIDGE_SECRET,
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            response.read()
        log(f"Forwarded inbound LXMF message {payload['message_id']} to agent")
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")
        log(f"Agent rejected LXMF webhook status={error.code} body={body}")
    except Exception as error:
        log(f"Failed to forward LXMF message to agent: {error}")


def delivery_callback(message):
    log(
        "Inbound LXMF message "
        f"id={message_id(message)} source={hex_bytes(message.source_hash)}"
    )
    threading.Thread(target=post_to_agent, args=(message,), daemon=True).start()


def desired_method():
    if OUTBOUND_METHOD == "opportunistic":
        return LXMF.LXMessage.OPPORTUNISTIC
    if OUTBOUND_METHOD == "propagated":
        return LXMF.LXMessage.PROPAGATED
    return LXMF.LXMessage.DIRECT


def wait_for_path(destination_hash):
    if RNS.Transport.has_path(destination_hash):
        return True
    RNS.Transport.request_path(destination_hash)
    deadline = time.time() + SEND_PATH_TIMEOUT_SECONDS
    while time.time() < deadline and not stop_event.is_set():
        if RNS.Transport.has_path(destination_hash):
            return True
        time.sleep(0.25)
    return RNS.Transport.has_path(destination_hash)


def send_lxmf(destination_hash_hex, content, title=""):
    destination_hash = bytes.fromhex(destination_hash_hex)
    if not wait_for_path(destination_hash):
        raise RuntimeError("no path to destination")
    destination_identity = RNS.Identity.recall(destination_hash)
    if destination_identity is None:
        raise RuntimeError("destination identity not known")
    destination = RNS.Destination(
        destination_identity, RNS.Destination.OUT, RNS.Destination.SINGLE, "lxmf", "delivery"
    )
    lxm = LXMF.LXMessage(
        destination,
        local_destination,
        content,
        title or "",
        desired_method=desired_method(),
        include_ticket=True,
    )
    router.handle_outbound(lxm)
    return message_id(lxm)


def authorize(headers):
    if not BRIDGE_SECRET:
        return False
    header_secret = headers.get("X-LXMF-Bridge-Secret")
    auth = headers.get("Authorization", "")
    bearer_secret = auth.removeprefix("Bearer ").strip() if auth.startswith("Bearer ") else None
    return header_secret == BRIDGE_SECRET or bearer_secret == BRIDGE_SECRET


def read_chunked_body(stream):
    chunks = []
    while True:
        size_line = stream.readline()
        if not size_line:
            raise ValueError("unexpected EOF while reading chunked body")
        size_text = size_line.split(b";", 1)[0].strip()
        if not size_text:
            continue
        size = int(size_text, 16)
        if size == 0:
            while True:
                trailer = stream.readline()
                if trailer in (b"\r\n", b"\n", b""):
                    return b"".join(chunks)
        chunks.append(stream.read(size))
        stream.read(2)


class Handler(BaseHTTPRequestHandler):
    def read_body(self):
        transfer_encoding = self.headers.get("Transfer-Encoding", "").lower()
        if "chunked" in transfer_encoding:
            return read_chunked_body(self.rfile)
        length = int(self.headers.get("Content-Length") or "0")
        if length <= 0:
            return b""
        return self.rfile.read(length)

    def read_json_body(self):
        raw_body = self.read_body()
        if not raw_body.strip():
            raise ValueError("empty request body")
        return json.loads(raw_body.decode("utf-8"))

    def do_GET(self):
        if self.path == "/health":
            self.write_json(200, {"status": "ok"})
            return
        if self.path == "/identity":
            self.write_json(
                200,
                {
                    "display_name": DISPLAY_NAME,
                    "destination_hash": local_destination.hash.hex(),
                },
            )
            return
        self.write_json(404, {"error": "not_found"})

    def do_POST(self):
        if self.path != "/api/v1/messages/send":
            self.write_json(404, {"error": "not_found"})
            return
        if not authorize(self.headers):
            self.write_json(401, {"error": "unauthorized"})
            return
        try:
            body = self.read_json_body()
            destination_hash = body["destination_hash"].strip().lower()
            content = body["content"]
            title = body.get("title", "")
            outbound_id = send_lxmf(destination_hash, content, title)
            self.write_json(200, {"status": "queued", "message_id": outbound_id})
        except (json.JSONDecodeError, KeyError, ValueError) as error:
            log(f"Bad outbound LXMF send request: {error}")
            self.write_json(400, {"error": str(error)})
        except Exception as error:
            log(f"Failed outbound LXMF send: {error}")
            self.write_json(500, {"error": str(error)})

    def write_json(self, status, payload):
        data = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):
        log(fmt % args)


def announce_loop():
    while not stop_event.is_set():
        try:
            router.announce(local_destination.hash)
            log(f"Announced LXMF destination {local_destination.hash.hex()}")
        except Exception as error:
            log(f"Failed LXMF announce: {error}")
        stop_event.wait(ANNOUNCE_INTERVAL_SECONDS)


def start_bridge():
    global router, local_destination
    if not BRIDGE_SECRET:
        raise RuntimeError("LXMF_BRIDGE_WEBHOOK_SECRET must be set")
    write_default_rns_config()
    LXMF_STORAGE_DIR.mkdir(parents=True, exist_ok=True)
    RNS.Reticulum(configdir=str(RNS_CONFIG_DIR))
    router = LXMF.LXMRouter(storagepath=str(LXMF_STORAGE_DIR))
    identity = load_or_create_identity()
    local_destination = router.register_delivery_identity(
        identity, display_name=DISPLAY_NAME, stamp_cost=STAMP_COST
    )
    router.register_delivery_callback(delivery_callback)
    log(f"Ready to receive LXMF on {local_destination.hash.hex()} ({DISPLAY_NAME})")
    threading.Thread(target=announce_loop, daemon=True).start()
    server = ThreadingHTTPServer((BRIDGE_HOST, BRIDGE_PORT), Handler)
    server.timeout = 1
    return server


def shutdown(signum, frame):
    stop_event.set()


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)
    httpd = start_bridge()
    while not stop_event.is_set():
        httpd.handle_request()
    httpd.server_close()
