import queue
import tempfile
import unittest
from unittest.mock import Mock, patch

_storage = tempfile.TemporaryDirectory(prefix="canary-unit-test-")
with patch.dict("os.environ", {"CANARY_STORAGE_DIR": _storage.name}), patch.dict(
    "sys.modules", {"LXMF": Mock(), "RNS": Mock()}
):
    import canary


def tearDownModule():
    _storage.cleanup()


class AgreementModeTest(unittest.TestCase):
    prompt = "Terms of Use: https://example.test/terms. Reply YES"

    def setUp(self):
        self.messages = queue.Queue()
        self.patches = [
            patch.object(canary, "inbound_messages", self.messages),
            patch.object(canary, "STRICT_INFERENCE_RESPONSE", True),
            patch.object(canary, "fetch_bridge_destination_hash", return_value="fixture"),
            patch.object(canary, "start_lxmf"),
            patch.object(canary, "log"),
            patch.object(canary, "send_lxmf"),
        ]
        for item in self.patches:
            item.start()
            self.addCleanup(item.stop)
        self.success = f"CANARY_OK {canary.RUN_ID}"

    def run_mode(self, required, replies):
        for content in replies:
            self.messages.put({"content": content})
        stages = {}
        with patch.object(canary, "TERMS_REQUIRED", required):
            canary.run_canary(stages)
        return stages, canary.send_lxmf.call_args_list

    def test_required_mode_accepts_terms_and_uses_replayed_inference(self):
        stages, sent = self.run_mode(True, [self.prompt, self.success])
        self.assertEqual(set(stages), {"bridge_identity", "terms_prompt", "terms_acceptance"})
        self.assertEqual(
            [c.args[1] for c in sent],
            [f"{canary.MARKER} run_id={canary.RUN_ID}\n{canary.INFERENCE_PROMPT}", "YES"],
        )

    def test_required_mode_supports_separate_inference_after_acceptance(self):
        stages, sent = self.run_mode(True, [self.prompt, "All set", self.success])
        self.assertIn("inference", stages)
        self.assertEqual([c.args[1] for c in sent][-2:], ["YES", canary.INFERENCE_PROMPT])

    def test_disabled_mode_requires_inference_without_sending_agreement(self):
        stages, sent = self.run_mode(False, [self.success])
        self.assertEqual(set(stages), {"bridge_identity", "inference"})
        self.assertEqual(len(sent), 1)
        self.assertEqual(
            sent[0].args[1], f"{canary.MARKER} run_id={canary.RUN_ID}\n{canary.INFERENCE_PROMPT}"
        )

    def test_mode_mismatch_fails_in_both_directions(self):
        for required, reply in [(True, self.success), (False, self.prompt)]:
            with self.subTest(required=required):
                with self.assertRaises(canary.CanaryFailure) as caught:
                    self.run_mode(required, [reply])
                self.assertEqual(caught.exception.failure_type, "terms_mode_mismatch")

    def test_wrong_nonce_cannot_complete_either_mode(self):
        wrong_reply = "CANARY_OK unrelated-run"
        self.assertFalse(canary.inference_reply_matches(wrong_reply))
        for required in [True, False]:
            with self.subTest(required=required):
                replies = (
                    [self.prompt, wrong_reply, self.success]
                    if required
                    else [wrong_reply, self.success]
                )
                self.run_mode(required, replies)
                self.assertTrue(self.messages.empty())


if __name__ == "__main__":
    unittest.main()
