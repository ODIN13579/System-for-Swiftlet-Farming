"""Unit tests for the B3-33 capture paths."""

from datetime import datetime, timezone
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("capture-firmware-soak.py")
SPEC = importlib.util.spec_from_file_location("capture_firmware_soak", SCRIPT)
capture = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(capture)


class CaptureFirmwareSoakTests(unittest.TestCase):
    def test_mqtt_preserves_device_payload_and_fractional_subscriber_clock(self):
        payload = '{\n "timestamp": "2026-11-25T13:48:53Z"\n}'
        now = datetime(2026, 11, 25, 13, 48, 54, 123456, tzinfo=timezone.utc)
        record = capture.mqtt_record(f"device/heartbeat\t{payload.encode().hex()}\r\n", now)
        self.assertEqual(record["received_at_utc"], "2026-11-25T13:48:54.123456Z")
        self.assertEqual(record["payload"], payload)
        self.assertEqual(json.loads(record["payload"])["timestamp"], "2026-11-25T13:48:53Z")
        self.assertEqual(capture.mqtt_record("t\tff", now)["payload_hex"], "ff")
        self.assertEqual(capture.mqtt_record("t\t", now)["payload"], "")
        with self.assertRaises(ValueError):
            capture.mqtt_record("bad", now)

    def test_capture_refuses_existing_destination(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "existing"
            output.write_bytes(b"keep")
            result = subprocess.run([sys.executable, str(SCRIPT), "serial", "--output", str(output)],
                                    capture_output=True)
            self.assertEqual(result.returncode, 1)
            self.assertEqual(output.read_bytes(), b"keep")

    def test_serial_writes_received_bytes_and_surfaces_disconnect(self):
        class Connection:
            def __enter__(self):
                self.chunks = iter([b"first\r\n", b"", b"second\n"])
                return self

            def __exit__(self, *args):
                pass

            def read(self, size):
                chunk = next(self.chunks, None)
                if chunk is None:
                    raise OSError("disconnected")
                return chunk

        def connect(endpoint, **kwargs):
            self.assertEqual(endpoint, "rfc2217://127.0.0.1:4000")
            self.assertEqual(kwargs["baudrate"], 115200)
            return Connection()

        driver = SimpleNamespace(serial_for_url=connect, SerialException=OSError)
        result = io.BytesIO()
        journal = io.StringIO()
        with patch.dict(sys.modules, {"serial": driver}), patch("sys.stderr", new=io.StringIO()):
            with self.assertRaisesRegex(OSError, "disconnected"):
                status = capture.CaptureStatus(journal, "serial", 15)
                capture.capture_serial(SimpleNamespace(port=4000, wait_seconds=1), result, status)
        self.assertEqual(result.getvalue(), b"first\r\nsecond\n")
        self.assertEqual(status.bytes, len(result.getvalue()))
        self.assertEqual(json.loads(journal.getvalue())["event"], "attached")

    def test_mqtt_reports_silence(self):
        class Subscriber:
            pid = 7

            def __enter__(self):
                from threading import Event
                self.stopped = Event()
                self.stdout = self.lines()
                return self

            def lines(self):
                yield "Subscribed (mid: 1): 1, 1, 1, 1, 1\n"
                self.stopped.wait(2)

            def __exit__(self, *unused):
                pass

            def poll(self):
                return None

            def terminate(self):
                self.stopped.set()

        args = SimpleNamespace(mosquitto_sub="mqtt", host="localhost", port=1883,
                               topic_base="device", count=None, idle_seconds=0.10)
        journal = io.StringIO()
        with patch.object(capture.subprocess, "Popen", return_value=Subscriber()):
            with self.assertRaisesRegex(TimeoutError, "no MQTT payload"):
                capture.capture_mqtt(args, io.BytesIO(), capture.CaptureStatus(journal, "mqtt", 15))
        self.assertEqual(json.loads(journal.getvalue())["event"], "subscribed")

    def test_mqtt_suback_and_rejected_codes_are_preserved(self):
        self.assertEqual(capture.mqtt_suback("Subscribed (mid: 1): 1, 128\n"), [1, 128])
        self.assertIsNone(capture.mqtt_suback("Client received CONNACK (0)"))

    def test_receive_clock_and_payload_are_kept_separate(self):
        stamp = datetime(2026, 11, 26, 15, 9, 17, tzinfo=timezone.utc)
        payload = '{"timestamp":"2026-11-26T15:09:15Z"}'
        record = capture.mqtt_record(f"device/heartbeat\t{payload.encode().hex()}", stamp)
        raw, journal = io.BytesIO(), io.StringIO()
        status = capture.CaptureStatus(journal, "mqtt", 15)
        capture.write_mqtt_record(raw, status, record, 123)
        item = json.loads(raw.getvalue())
        self.assertEqual(item["received_monotonic_ns"], 123)
        self.assertEqual(item["payload"], payload)
        self.assertEqual(json.loads(journal.getvalue())["event"], "heartbeat")


if __name__ == "__main__":
    unittest.main()
