"""Regression tests for the B3-33 capture analyzer."""

from hashlib import sha256
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("audit-firmware-soak.py")
SPEC = importlib.util.spec_from_file_location("audit_firmware_soak", SCRIPT)
audit = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(audit)
TOPIC = "swiftletcare/1/1/device"


def message(kind, **fields):
    return f"{TOPIC}/{kind} " + json.dumps({"device_id": "device", **fields})


class AuditFirmwareSoakTests(unittest.TestCase):
    def test_subscriber_gaps_are_separate_from_device_gaps(self):
        lines = [
            json.dumps({
                "received_at_utc": received,
                "topic": TOPIC + "/heartbeat",
                "payload": message("heartbeat", timestamp=device).split(" ", 1)[1],
            })
            for received, device in (
                ("2026-11-25T00:00:00.100Z", "2026-11-25T03:00:00Z"),
                ("2026-11-25T00:00:07.200Z", "2026-11-25T03:00:02Z"),
                ("2026-11-25T00:00:06.200Z", "bad"),
            )
        ]
        lines += [message("heartbeat", timestamp="2026-11-25T03:00:04Z"), '{"topic": 3}']
        result = audit.summarize_mqtt(lines, TOPIC)
        self.assertEqual(result["largest_heartbeat_gap_seconds"], 2)
        receiver = result["subscriber_heartbeat"]
        self.assertEqual(receiver["largest_gap_seconds"], 7.1)
        self.assertEqual(receiver["missing_or_invalid_receipt_timestamps"], 1)
        self.assertEqual(receiver["gaps_over_seconds"], {5: 1, 10: 0, 30: 0})
        self.assertEqual(receiver["clock_regressions"], 1)
        self.assertEqual(result["rejected_lines"], {"invalid_capture_record": 1})

    def test_heartbeat_clocks_and_strict_gap_thresholds(self):
        seconds = (0, 5, 16, 16, 15, 46)
        lines = [message("heartbeat", timestamp=f"2026-11-25T00:00:{s:02}Z") for s in seconds]
        lines += [message("heartbeat", timestamp="bad"), message("heartbeat")]
        result = audit.summarize_mqtt(lines, TOPIC)
        self.assertEqual(result["heartbeat_span_seconds"], 46)
        self.assertEqual(result["largest_heartbeat_gap_seconds"], 31)
        self.assertEqual(result["heartbeat_gaps_over_seconds"], {5: 2, 10: 2, 30: 1})
        self.assertEqual(result["timestamps_by_topic"]["heartbeat"], {
            "valid": 6, "missing_or_null": 1, "invalid": 1, "duplicates": 1, "regressions": 1,
        })

    def test_boot_transitions_modes_and_ack_matching(self):
        lines = [
            message("state", boot_id="a", mode="AUTO"),
            message("state", boot_id="b", mode="MANUAL"),
            message("state", boot_id="a", mode="AUTO"),
            message("telemetry", event_id="1"),
            message("telemetry", event_id="1"),
            message("telemetry", event_id="2"),
            message("telemetry_ack", event_id="1", status="STORED"),
            message("telemetry_ack", event_id="2", status="REJECTED"),
            message("telemetry_ack", event_id="3", status="STORED"),
            message("events", device_id="different"),
        ]
        result = audit.summarize_mqtt(lines, TOPIC)
        self.assertEqual(result["boot_id_change_count"], 2)
        self.assertEqual(result["boot_id_message_counts"], {"a": 2, "b": 1})
        self.assertTrue(result["manual_observed"])
        self.assertEqual(result["stored_ack_percent"], 50)
        self.assertEqual(result["messages"]["events"], 0)
        self.assertEqual(result["telemetry_copies_per_event_id"], {"min": 1, "max": 2})

    def test_bounded_read_ignores_later_append_and_partial_tail(self):
        initial = b"\xef\xbb\xbfcomplete\npartial"
        stream = io.BytesIO(initial + b" suffix\nnew line\n")
        digest = sha256()
        capture = {}
        self.assertEqual(list(audit.bounded_lines(stream, len(initial), digest, capture)), ["complete\n"])
        self.assertEqual(digest.hexdigest(), sha256(initial).hexdigest())
        self.assertEqual(capture["ignored_unterminated_tail_bytes"], 7)
        with self.assertRaises(OSError):
            list(audit.bounded_lines(io.BytesIO(b"one\n"), 8, sha256(), {}))

    def test_missing_capture_is_not_zero_messages_or_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            serial = Path(directory) / "missing.log"
            mqtt = Path(directory) / "mqtt.log"
            mqtt.write_text(message("heartbeat") + "\n", encoding="utf-8")
            completed = subprocess.run(
                [sys.executable, str(SCRIPT), "--serial", str(serial), "--mqtt", str(mqtt),
                 "--topic-base", TOPIC], check=True, capture_output=True, text=True,
            )
            result = json.loads(completed.stdout)
        self.assertIsNone(result["serial"])
        self.assertEqual(result["mqtt"]["messages"]["heartbeat"], 1)
        self.assertIn("MISSING EVIDENCE", result["evidence_issues"]["serial"])
        self.assertEqual(result["acceptance"], "REQUIRES REVIEW")

    def test_serial_records_resource_windows_and_reset_reason(self):
        result = audit.summarize_serial([
            "[BOOT] P1 FIXED B2 reset_reason=1",
            "[RESOURCE] uptime_ms=100 free_heap=80 buffer=1/120",
            "[RESOURCE] uptime_ms=200 free_heap=79 buffer=0/120",
            "[BOOT] P1 FIXED B2 reset_reason=4",
            "[MQTT] CONNECTED; subscribed cfg + command + telemetry_ack",
            "[BUFFER] Removed STORED: my-id | remaining=0",
            "[RESOURCE] uptime_ms=100 free_heap=81",
        ])
        self.assertEqual(result["reset_reasons"], {"1": 1, "4": 1})
        self.assertEqual(result["mqtt_connected_markers"], 1)
        self.assertEqual(result["removed_stored_ids"], ["my-id"])
        self.assertEqual(result["resource_windows"][0]["counters"]["free_heap"]["min"], 79)
        self.assertEqual(result["resource_windows"][0]["counters"]["buffer_capacity"]["max"], 120)
        self.assertEqual(len(result["resource_windows"]), 2)

    def test_synthetic_filenames_are_excluded(self):
        with self.assertRaisesRegex(ValueError, "synthetic"):
            audit.analyze_capture(Path("soak-synthetic-serial.log"), audit.summarize_serial)


if __name__ == "__main__":
    unittest.main()
