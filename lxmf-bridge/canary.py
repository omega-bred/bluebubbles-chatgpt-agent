#!/usr/bin/env python3
import json
import os
import queue
import signal
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

import LXMF
import RNS


def env(name, default=""):
    return os.environ.get(name, default)


RUN_ID = env("CANARY_RUN_ID", str(uuid.uuid4()))
MARKER = env("CANARY_MARKER", "BBAGENT_LXMF_CANARY_V1")
INFERENCE_PROMPT = f"Reply exactly with: CANARY_OK {RUN_ID}"
DISPLAY_NAME = env("CANARY_DISPLAY_NAME", "BlueChat Canary")
BRIDGE_BASE_URL = env(
    "LXMF_BRIDGE_BASE_URL",
    "http://lxmf-bridge.bluebubbles-chatgpt-agent.svc.cluster.local:8091",
).rstrip("/")
RNS_BACKBONE_REMOTE = env("RNS_BACKBONE_REMOTE", "rnode-public.rnode.svc.cluster.local")
RNS_BACKBONE_PORT = int(env("RNS_BACKBONE_PORT", "4242"))
BASE_STORAGE_DIR = Path(env("CANARY_STORAGE_DIR", tempfile.mkdtemp(prefix="lxmf-canary-")))
RNS_CONFIG_DIR = Path(env("RNS_CONFIG_DIR", str(BASE_STORAGE_DIR / "reticulum")))
LXMF_STORAGE_DIR = Path(env("LXMF_STORAGE_DIR", str(BASE_STORAGE_DIR / "lxmf")))
IDENTITY_PATH = Path(env("LXMF_IDENTITY_PATH", str(LXMF_STORAGE_DIR / "identity")))
STAMP_COST = int(env("LXMF_STAMP_COST", "8"))
OUTBOUND_METHOD = env("LXMF_OUTBOUND_METHOD", "direct").lower()
SEND_PATH_TIMEOUT_SECONDS = int(env("LXMF_SEND_PATH_TIMEOUT_SECONDS", "45"))
STAGE_TIMEOUT_SECONDS = int(env("CANARY_STAGE_TIMEOUT_SECONDS", "90"))
TOTAL_TIMEOUT_SECONDS = int(env("CANARY_TIMEOUT_SECONDS", "300"))
ANNOUNCE_INTERVAL_SECONDS = int(env("CANARY_ANNOUNCE_INTERVAL_SECONDS", "10"))
STRICT_INFERENCE_RESPONSE = env("CANARY_STRICT_INFERENCE_RESPONSE", "true").lower() == "true"
INFLUX_ENABLED = env("CANARY_INFLUX_ENABLED", "true").lower() == "true"
INFLUX_REQUIRED = env("CANARY_INFLUX_REQUIRED", "true").lower() == "true"
INFLUX_URI = env("INFLUX_DB_URI", "").rstrip("/")
INFLUX_TOKEN = env("INFLUX_DB_TOKEN", "")
INFLUX_BUCKET = env("INFLUX_DB_BUCKET", "bluebubbles-chatgpt-agent")
INFLUX_ORG = env("INFLUX_DB_ORG", "bred")

router = None
local_destination = None
stop_event = threading.Event()
inbound_messages = queue.Queue()


class CanaryFailure(Exception):
    def __init__(self, stage, failure_type, message):
        super().__init__(message)
        self.stage = stage
        self.failure_type = failure_type


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


def content_text(message):
    try:
        return message.content_as_string() or ""
    except Exception:
        return ""


def delivery_callback(message):
    text = content_text(message)
    log(f"Received LXMF canary reply id={message_id(message)} content={text!r}")
    inbound_messages.put(
        {
            "message_id": message_id(message),
            "source_hash": hex_bytes(message.source_hash),
            "content": text,
            "timestamp": time.time(),
        }
    )


def desired_method():
    if OUTBOUND_METHOD == "opportunistic":
        return LXMF.LXMessage.OPPORTUNISTIC
    if OUTBOUND_METHOD == "propagated":
        return LXMF.LXMessage.PROPAGATED
    return LXMF.LXMessage.DIRECT


def wait_for_path(destination_hash, stage):
    if RNS.Transport.has_path(destination_hash):
        return
    RNS.Transport.request_path(destination_hash)
    deadline = time.time() + SEND_PATH_TIMEOUT_SECONDS
    while time.time() < deadline and not stop_event.is_set():
        if RNS.Transport.has_path(destination_hash):
            return
        time.sleep(0.25)
    raise CanaryFailure(stage, "path_timeout", "no path to destination")


def send_lxmf(destination_hash_hex, content, stage, title=""):
    destination_hash = bytes.fromhex(destination_hash_hex)
    wait_for_path(destination_hash, stage)
    destination_identity = RNS.Identity.recall(destination_hash)
    if destination_identity is None:
        raise CanaryFailure(stage, "identity_unknown", "destination identity not known")
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
    outbound_id = message_id(lxm)
    log(f"Sent LXMF canary stage={stage} id={outbound_id} destination={destination_hash_hex}")
    return outbound_id


def fetch_bridge_destination_hash():
    try:
        with urllib.request.urlopen(BRIDGE_BASE_URL + "/identity", timeout=20) as response:
            data = json.loads(response.read().decode("utf-8"))
    except Exception as error:
        raise CanaryFailure("bridge_identity", "bridge_identity_error", str(error)) from error
    destination_hash = str(data.get("destination_hash", "")).strip().lower()
    if not destination_hash:
        raise CanaryFailure(
            "bridge_identity", "bridge_identity_missing", "bridge identity response missing hash"
        )
    return destination_hash


def wait_for_reply(stage, predicate, timeout=STAGE_TIMEOUT_SECONDS):
    deadline = time.time() + timeout
    while time.time() < deadline and not stop_event.is_set():
        try:
            message = inbound_messages.get(timeout=min(1, max(0.1, deadline - time.time())))
        except queue.Empty:
            continue
        content = message.get("content", "")
        if predicate(content):
            return message
    raise CanaryFailure(stage, "reply_timeout", f"timed out waiting for {stage} reply")


def announce_loop():
    while not stop_event.is_set():
        try:
            router.announce(local_destination.hash)
            log(f"Announced LXMF canary destination {local_destination.hash.hex()}")
        except Exception as error:
            log(f"Failed LXMF canary announce: {error}")
        stop_event.wait(ANNOUNCE_INTERVAL_SECONDS)


def start_lxmf():
    global router, local_destination
    write_default_rns_config()
    LXMF_STORAGE_DIR.mkdir(parents=True, exist_ok=True)
    RNS.Reticulum(configdir=str(RNS_CONFIG_DIR))
    router = LXMF.LXMRouter(storagepath=str(LXMF_STORAGE_DIR))
    identity = load_or_create_identity()
    local_destination = router.register_delivery_identity(
        identity, display_name=DISPLAY_NAME, stamp_cost=STAMP_COST
    )
    router.register_delivery_callback(delivery_callback)
    log(f"Ready for LXMF canary replies on {local_destination.hash.hex()} ({DISPLAY_NAME})")
    threading.Thread(target=announce_loop, daemon=True).start()


def run_stage(stage, operation, stage_durations):
    started = time.time()
    try:
        return operation()
    finally:
        stage_durations[stage] = time.time() - started


def run_canary(stage_durations):
    deadline = time.time() + TOTAL_TIMEOUT_SECONDS
    started = time.time()
    bridge_hash = run_stage("bridge_identity", fetch_bridge_destination_hash, stage_durations)
    start_lxmf()
    log(f"Using bridge LXMF destination {bridge_hash}; canary run_id={RUN_ID}")

    def ensure_time_remaining(stage):
        if time.time() >= deadline:
            raise CanaryFailure(stage, "total_timeout", "canary exceeded total timeout")

    def terms_prompt():
        ensure_time_remaining("terms_prompt")
        send_lxmf(bridge_hash, f"{MARKER} run_id={RUN_ID}\n{INFERENCE_PROMPT}", "terms_prompt")
        return wait_for_reply(
            "terms_prompt",
            lambda content: "Terms of Use" in content and "Reply YES" in content,
        )

    def terms_acceptance():
        ensure_time_remaining("terms_acceptance")
        send_lxmf(bridge_hash, "YES", "terms_acceptance")
        return wait_for_reply(
            "terms_acceptance",
            terms_acceptance_reply_matches,
        )

    def inference():
        ensure_time_remaining("inference")
        send_lxmf(bridge_hash, INFERENCE_PROMPT, "inference")
        return wait_for_reply("inference", inference_reply_matches)

    run_stage("terms_prompt", terms_prompt, stage_durations)
    terms_acceptance_reply = run_stage("terms_acceptance", terms_acceptance, stage_durations)
    if inference_reply_matches(terms_acceptance_reply.get("content", "")):
        inference_reply = terms_acceptance_reply
        log("LXMF canary terms acceptance replay produced inference reply; skipping inference stage")
    else:
        inference_reply = run_stage("inference", inference, stage_durations)
    log(f"LXMF canary succeeded run_id={RUN_ID} reply={inference_reply.get('content', '')!r}")
    return time.time() - started


def terms_acceptance_reply_matches(content):
    text = (content or "").lower()
    return "all set" in text or "send your request" in text or inference_reply_matches(content)


def inference_reply_matches(content):
    text = (content or "").strip()
    if not text:
        return False
    if STRICT_INFERENCE_RESPONSE:
        return "CANARY_OK" in text.upper() and RUN_ID.lower() in text.lower()
    return "Terms of Use" not in text and "Reply YES" not in text


def escape_key(value):
    return str(value).replace(" ", "\\ ").replace(",", "\\,").replace("=", "\\=")


def escape_string_field(value):
    return '"' + str(value).replace("\\", "\\\\").replace('"', '\\"') + '"'


def format_field(value):
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return f"{value}i"
    if isinstance(value, float):
        return f"{value:.6f}"
    return escape_string_field(value)


def line_protocol(measurement, tags, fields, timestamp):
    tag_text = "".join(f",{escape_key(key)}={escape_key(value)}" for key, value in tags.items())
    field_text = ",".join(
        f"{escape_key(key)}={format_field(value)}" for key, value in fields.items()
    )
    return f"{escape_key(measurement)}{tag_text} {field_text} {timestamp}"


def emit_metrics(success, duration_seconds, stage, failure_type, stage_durations):
    if not INFLUX_ENABLED:
        return
    if not INFLUX_URI or not INFLUX_TOKEN:
        message = "Influx metrics are enabled but INFLUX_DB_URI or INFLUX_DB_TOKEN is missing"
        if INFLUX_REQUIRED:
            raise CanaryFailure("metrics", "influx_config_missing", message)
        log(message)
        return
    timestamp = int(time.time())
    outcome = "success" if success else "failure"
    lines = [
        line_protocol(
            "bbagent_lxmf_canary_up",
            {"stage": "full_e2e"},
            {"value": 1 if success else 0},
            timestamp,
        ),
        line_protocol(
            "bbagent_lxmf_canary_run_count",
            {"outcome": outcome, "failure_type": failure_type, "stage": stage},
            {"value": 1},
            timestamp,
        ),
        line_protocol(
            "bbagent_lxmf_canary_duration_seconds",
            {"outcome": outcome, "failure_type": failure_type, "stage": stage},
            {"value": max(0.0, duration_seconds)},
            timestamp,
        ),
        line_protocol(
            "bbagent_lxmf_canary_last_check_epoch_seconds",
            {},
            {"value": timestamp},
            timestamp,
        ),
    ]
    if success:
        lines.append(
            line_protocol(
                "bbagent_lxmf_canary_last_success_epoch_seconds",
                {},
                {"value": timestamp},
                timestamp,
            )
        )
    for stage_name, stage_duration in stage_durations.items():
        lines.append(
            line_protocol(
                "bbagent_lxmf_canary_stage_duration_seconds",
                {"stage": stage_name, "outcome": outcome},
                {"value": max(0.0, stage_duration)},
                timestamp,
            )
        )
    write_influx("\n".join(lines).encode("utf-8"))


def write_influx(payload):
    query = urllib.parse.urlencode(
        {"org": INFLUX_ORG, "bucket": INFLUX_BUCKET, "precision": "s"}
    )
    request = urllib.request.Request(
        f"{INFLUX_URI}/api/v2/write?{query}",
        data=payload,
        method="POST",
        headers={"Authorization": f"Token {INFLUX_TOKEN}", "Content-Type": "text/plain"},
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            response.read()
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")
        raise CanaryFailure("metrics", "influx_http_error", f"{error.code}: {body}") from error
    except Exception as error:
        raise CanaryFailure("metrics", "influx_write_error", str(error)) from error


def shutdown(signum, frame):
    stop_event.set()


def emit_metrics_safely(success, duration_seconds, stage, failure_type, stage_durations):
    try:
        emit_metrics(success, duration_seconds, stage, failure_type, stage_durations)
        return True
    except CanaryFailure as error:
        log(f"LXMF canary metrics failed failure_type={error.failure_type}: {error}")
        return False
    except Exception as error:
        log(f"LXMF canary metrics failed failure_type=metrics_exception: {error}")
        return False


def main():
    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)
    try:
        started = time.time()
        stage_durations = {}
        success = False
        stage = "full_e2e"
        failure_type = "none"
        try:
            duration = run_canary(stage_durations)
            success = True
        except CanaryFailure as error:
            duration = time.time() - started
            stage = error.stage
            failure_type = error.failure_type
            log(
                f"LXMF canary failed stage={error.stage} "
                f"failure_type={error.failure_type}: {error}"
            )
        except Exception as error:
            duration = time.time() - started
            stage = "unexpected"
            failure_type = "exception"
            log(f"LXMF canary failed with unexpected error: {error}")
        metrics_ok = emit_metrics_safely(success, duration, stage, failure_type, stage_durations)
        return 0 if success and metrics_ok else 1
    finally:
        stop_event.set()


if __name__ == "__main__":
    raise SystemExit(main())
