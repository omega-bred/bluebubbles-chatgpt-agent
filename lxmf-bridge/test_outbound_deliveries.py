import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock
from uuid import uuid4

from outbound_delivery import DeliveryConflict, OutboundDeliveries, OutboundRequest


class OutboundDeliveriesTest(unittest.TestCase):
    def test_receipt_is_reused_after_bridge_restart(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "requests.sqlite"
            request = OutboundRequest("AB", "hello", request_id=str(uuid4()))
            prepare = Mock(return_value=object())
            enqueue = Mock(return_value="wire-id")
            first = OutboundDeliveries(path).send(request, prepare, enqueue)
            second = OutboundDeliveries(path).send(request, prepare, enqueue)
            self.assertEqual({"status": "queued", "message_id": "wire-id"}, first)
            self.assertEqual(first, second)
            enqueue.assert_called_once()
            changed = OutboundRequest("AB", "different", request_id=request.request_id)
            with self.assertRaises(DeliveryConflict):
                OutboundDeliveries(path).send(changed, prepare, enqueue)

    def test_ambiguous_outbound_is_never_queued_twice(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "requests.sqlite"
            request = OutboundRequest("AB", "hello", request_id=str(uuid4()))
            prepare = Mock(return_value=object())
            enqueue = Mock(side_effect=RuntimeError("lost acknowledgment"))
            with self.assertRaises(RuntimeError):
                OutboundDeliveries(path).send(request, prepare, enqueue)
            self.assertEqual({"status": "pending"}, OutboundDeliveries(path).send(request, prepare, enqueue))
            enqueue.assert_called_once()
