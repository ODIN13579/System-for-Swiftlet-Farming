import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("manage-firmware-soak.py")
spec = importlib.util.spec_from_file_location("manage_firmware_soak", SCRIPT)
soak = importlib.util.module_from_spec(spec)
spec.loader.exec_module(soak)


class ManageSoakTests(unittest.TestCase):
    def test_observed_boot_requires_both_collectors_and_preserves_receipt_time(self):
        with tempfile.TemporaryDirectory() as directory:
            session = Path(directory)
            (session / "manifest.json").write_text("{}", encoding="utf-8")
            args = SimpleNamespace(session=session, event="boot-observed", note="Actual serial receipt")
            serial = [{"event": "attached", "monotonic_ns": 1},
                      {"event": "boot", "monotonic_ns": 3, "utc": "2026-11-27T03:45:51Z", "reset_reason": 1}]
            mqtt = [{"event": "subscribed", "monotonic_ns": 4}]
            with patch.object(soak, "read_session", return_value=([], serial, mqtt)):
                with self.assertRaisesRegex(ValueError, "after both"):
                    soak.mark(args)
                mqtt[0]["monotonic_ns"] = 2
                soak.mark(args)
            record = json.loads((session / "timeline.jsonl").read_text(encoding="utf-8"))
            self.assertEqual(record["boot_observed_monotonic_ns"], 3)
            self.assertEqual(record["boot_observed_utc"], serial[1]["utc"])
            self.assertNotEqual(record["utc"], record["boot_observed_utc"])

    def test_duration_uses_only_checkpoints_inside_operator_window(self):
        second = 10**9
        timeline = [{"event": "start", "monotonic_ns": 10 * second},
                    {"event": "end", "monotonic_ns": 7300 * second}]
        checkpoints = [{"event": "resource", "monotonic_ns": host * second, "uptime_ms": uptime}
                       for host, uptime in [(5, 2880216), (70, 120000), (7270, 7320010), (7400, 9000000)]]
        result = soak.duration_evidence(timeline, checkpoints)
        self.assertEqual(result["wall_seconds"], 7290)
        self.assertEqual(result["resource_uptime_ms"], 7200010)
        self.assertEqual(result["resource_wall_seconds"], 7200)
        self.assertEqual(result["resource_largest_wall_gap_seconds"], 7200)
        self.assertEqual(result["resource_checkpoints"], 2)

    def test_old_uptime_is_not_elapsed_time(self):
        timeline = [{"event": "start", "monotonic_ns": 0},
                    {"event": "end", "monotonic_ns": 90 * 10**9}]
        checkpoints = [{"event": "resource", "monotonic_ns": 60 * 10**9, "uptime_ms": 7800000}]
        result = soak.duration_evidence(timeline, checkpoints)
        self.assertEqual(result["resource_uptime_ms"], 0)
        self.assertEqual(result["resource_wall_seconds"], 0)

    def test_stale_or_failed_collector_is_not_active(self):
        row = {"event": "progress", "pid": 14, "bytes": 5, "monotonic_ns": 0}
        self.assertEqual(soak.health([row], 10**9, 45)["state"], "ACTIVE")
        self.assertEqual(soak.health([row], 46 * 10**9, 45)["state"], "STALE")
        self.assertEqual(soak.health([{**row, "event": "error"}], 10**9, 45)["state"], "error")
        self.assertEqual(soak.health([], 10**9, 45)["state"], "WAITING")


if __name__ == "__main__":
    unittest.main()
