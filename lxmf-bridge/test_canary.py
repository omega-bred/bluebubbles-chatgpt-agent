import queue
import tempfile
import threading
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

    def run_mode(self, required, replies, strict=True):
        for content in replies:
            self.messages.put({"content": content, "source_hash": "fixture"})
        stages = {}
        with (
            patch.object(canary, "TERMS_REQUIRED", required),
            patch.object(canary, "STRICT_INFERENCE_RESPONSE", strict),
        ):
            self.result = canary.run_canary(stages)
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

    def test_wrong_nonce_is_a_mismatch_in_strict_mode(self):
        wrong_reply = "CANARY_OK unrelated-run"
        self.assertFalse(canary.inference_reply_matches(wrong_reply))
        for required in [True, False]:
            with self.subTest(required=required):
                replies = [self.prompt, wrong_reply] if required else [wrong_reply]
                with self.assertRaises(canary.CanaryFailure) as caught:
                    self.run_mode(required, replies)
                self.assertEqual(caught.exception.failure_type, "response_mismatch")

    def test_mismatched_inference_is_healthy_in_relaxed_mode(self):
        for reply in ["CANARY_OK wrong-nonce", "Sorry, could you repeat that?"]:
            with self.subTest(reply=reply):
                self.run_mode(False, [reply], strict=False)
                self.assertEqual(self.result[1], "mismatched")
                self.assertEqual(canary.send_lxmf.call_count, 1)
                canary.send_lxmf.reset_mock()

    def test_matching_inference_is_reported_in_relaxed_mode(self):
        self.run_mode(False, [self.success], strict=False)
        self.assertEqual(self.result[1], "matched")

    def test_mismatched_terms_replay_is_healthy_without_an_extra_prompt(self):
        stages, sent = self.run_mode(
            True, [self.prompt, "CANARY_OK wrong-nonce"], strict=False
        )
        self.assertEqual(self.result[1], "mismatched")
        self.assertEqual(len(sent), 2)
        self.assertNotIn("inference", stages)

    def test_mismatched_followup_inference_is_healthy(self):
        self.run_mode(True, [self.prompt, "All set", "CANARY_OK wrong-nonce"], strict=False)
        self.assertEqual(self.result[1], "mismatched")

    def test_relaxed_mode_still_detects_agreement_mode_drift(self):
        for required, reply in [(True, self.success), (False, self.prompt)]:
            with self.subTest(required=required):
                with self.assertRaises(canary.CanaryFailure) as caught:
                    self.run_mode(required, [reply], strict=False)
                self.assertEqual(caught.exception.failure_type, "terms_mode_mismatch")

    def test_wrong_source_and_empty_replies_do_not_complete_the_probe(self):
        for content, source in [(self.success, "unrelated"), ("  ", "fixture")]:
            with self.subTest(content=content, source=source):
                self.messages.put({"content": content, "source_hash": source})
                with self.assertRaises(canary.CanaryFailure) as caught:
                    canary.wait_for_reply(
                        "inference",
                        canary.inference_reply_matches,
                        "fixture",
                        timeout=0.01,
                        allow_mismatch=True,
                    )
                self.assertEqual(caught.exception.failure_type, "reply_timeout")

    def test_wrong_source_is_ignored_until_the_bridge_replies(self):
        self.messages.put({"content": self.success, "source_hash": "unrelated"})
        self.run_mode(False, [self.success], strict=False)
        self.assertEqual(self.result[1], "matched")
        self.assertTrue(self.messages.empty())


class CanaryReportingTest(unittest.TestCase):
    def run_main(self, result=None, error=None, metrics_ok=True):
        with (
            patch.object(canary, "run_canary", return_value=result, side_effect=error),
            patch.object(canary, "emit_metrics_safely", return_value=metrics_ok) as metrics,
            patch.object(canary, "stop_event", threading.Event()),
            patch.object(canary.signal, "signal"),
            patch.object(canary, "log"),
        ):
            return canary.main(), metrics.call_args.args

    def test_mismatched_reply_has_a_successful_exit_and_distinct_metrics(self):
        exit_code, metrics = self.run_main(result=(1.5, "mismatched"))
        self.assertEqual(exit_code, 0)
        self.assertEqual(metrics, (True, 1.5, "full_e2e", "none", {}, "mismatched"))

    def test_strict_mismatch_and_timeout_have_distinct_failure_metrics(self):
        for failure_type, response_match in [
            ("response_mismatch", "mismatched"),
            ("reply_timeout", "not_checked"),
        ]:
            with self.subTest(failure_type=failure_type):
                error = canary.CanaryFailure("inference", failure_type, "test failure")
                exit_code, metrics = self.run_main(error=error)
                self.assertEqual(exit_code, 1)
                self.assertFalse(metrics[0])
                self.assertEqual(metrics[2:4], ("inference", failure_type))
                self.assertEqual(metrics[5], response_match)

    def test_metrics_failure_keeps_the_job_failed(self):
        exit_code, _ = self.run_main(result=(1.5, "matched"), metrics_ok=False)
        self.assertEqual(exit_code, 1)

    def test_mismatch_keeps_health_up_and_records_response_quality(self):
        with (
            patch.object(canary, "INFLUX_ENABLED", True),
            patch.object(canary, "INFLUX_URI", "https://influx.example.test"),
            patch.object(canary, "INFLUX_TOKEN", "fixture"),
            patch.object(canary, "write_influx") as write,
        ):
            canary.emit_metrics(True, 1.5, "full_e2e", "none", {}, "mismatched")
        payload = write.call_args.args[0].decode("utf-8")
        self.assertIn("bbagent_lxmf_canary_up,stage=full_e2e value=1i ", payload)
        self.assertIn(
            "bbagent_lxmf_canary_run_count,outcome=success,failure_type=none,"
            "stage=full_e2e,response_match=mismatched value=1i ",
            payload,
        )
        self.assertIn("bbagent_lxmf_canary_last_success_epoch_seconds value=", payload)


if __name__ == "__main__":
    unittest.main()
