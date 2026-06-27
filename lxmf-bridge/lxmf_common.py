#!/usr/bin/env python3
import os
from pathlib import Path

import LXMF
import RNS


def env(name, default=""):
    return os.environ.get(name, default)


def log(message):
    print(message, flush=True)


def write_default_rns_config(config_dir, remote, port):
    config_dir = Path(config_dir)
    config_path = config_dir / "config"
    if config_path.exists():
        return
    config_dir.mkdir(parents=True, exist_ok=True)
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
  remote = {remote}
  target_port = {port}
""",
        encoding="utf-8",
    )


def load_or_create_identity(identity_path):
    identity_path = Path(identity_path)
    identity_path.parent.mkdir(parents=True, exist_ok=True)
    if identity_path.exists():
        return RNS.Identity.from_file(str(identity_path))
    identity = RNS.Identity()
    identity.to_file(str(identity_path))
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


def desired_lxmf_method(outbound_method):
    if outbound_method == "opportunistic":
        return LXMF.LXMessage.OPPORTUNISTIC
    if outbound_method == "propagated":
        return LXMF.LXMessage.PROPAGATED
    return LXMF.LXMessage.DIRECT
