#!/usr/bin/env python3
import hashlib
import importlib.util
import sys
import types
import unittest
from pathlib import Path


def load_app_module():
    lxmf = types.SimpleNamespace(
        LXMessage=types.SimpleNamespace(DIRECT=1, OPPORTUNISTIC=2, PROPAGATED=3),
        LXMRouter=object,
    )
    rns = types.SimpleNamespace(
        Destination=types.SimpleNamespace(OUT=1, SINGLE=1),
        Identity=types.SimpleNamespace(from_file=lambda path: None, recall=lambda value: None),
        Reticulum=lambda **kwargs: None,
        Transport=types.SimpleNamespace(
            has_path=lambda value: False,
            request_path=lambda value: None,
        ),
    )
    sys.modules.setdefault("LXMF", lxmf)
    sys.modules.setdefault("RNS", rns)

    spec = importlib.util.spec_from_file_location(
        "lxmf_bridge_app",
        Path(__file__).with_name("app.py"),
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class LogFingerprintTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = load_app_module()

    def test_log_fingerprint_redacts_raw_bytes(self):
        source_hash = bytes.fromhex("0a91c03bbeb289ca703e7d3e0f99929a")
        expected_digest = hashlib.sha256(source_hash.hex().encode("utf-8")).hexdigest()[:12]

        fingerprint = self.app.log_fingerprint(source_hash)

        self.assertEqual(f"sha256:{expected_digest}", fingerprint)
        self.assertNotIn(source_hash.hex(), fingerprint)

    def test_log_fingerprint_handles_missing_value(self):
        self.assertEqual("unknown", self.app.log_fingerprint(None))


if __name__ == "__main__":
    unittest.main()
