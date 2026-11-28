import importlib.util
import io
from pathlib import Path
import tempfile
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import patch


script = Path(__file__).with_name("measure-build3.py")
spec = importlib.util.spec_from_file_location("measure_build3", script)
bench = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bench)


class MeasureBuild3Tests(unittest.TestCase):
    def test_load_commands_reject_demo_broker(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "evidence"
            commands = [
                ["mqtt-load", "--port", "51887", "--house", "1", "--zone", "1"],
                ["alert-live", "--mqtt-port", "51887", "--port", "18085", "--house", "1",
                 "--device", "1:B3P3-ALERT-1"],
            ]
            for command in commands:
                with self.subTest(mode=command[0]), patch.object(
                    sys, "argv", [str(script), "--output", str(output), *command, "--test-broker-confirmed"],
                ), patch("sys.stderr", new_callable=io.StringIO):
                    with self.assertRaises(SystemExit) as raised:
                        bench.main()
                    self.assertEqual(raised.exception.code, 2)
                    self.assertFalse(output.exists())

    def test_percentiles_missing_and_threshold(self):
        row = bench.metrics([1, 3, 5, 7], 4, 7)
        self.assertEqual((row["p50_ms"], row["p95_ms"], row["max_ms"], row["status"]), (4, 7, 7, "PASS"))
        self.assertEqual(bench.metrics([1], 2, 15)["status"], "FAIL")
        self.assertEqual(bench.metrics([5001], 1, 5000)["status"], "FAIL")

    def test_alert_phases_do_not_compare_detection_to_dom_limit(self):
        row = {"first_telemetry_ms": 100, "third_telemetry_ms": 6100, "backend_created_ms": 6200,
               "ws_received_ms": 6210, "dom_rendered_ms": 6220}
        result = bench.summarize_alerts([row], 1)
        self.assertEqual(result["detection_first"]["max_ms"], 6100)
        self.assertEqual(result["detection_third"]["max_ms"], 100)
        self.assertEqual(result["backend_to_ws"]["max_ms"], 10)
        self.assertEqual(result["backend_to_dom"]["status"], "PASS")

    def test_invalid_clock_and_absent_dom_are_not_pass(self):
        result = bench.summarize_alerts([{"backend_created_ms": 110, "ws_received_ms": 100}], 1)
        self.assertEqual(result["backend_to_ws"]["invalid_clock_samples"], 1)
        self.assertEqual(result["backend_to_ws"]["status"], "FAIL")
        self.assertEqual(result["backend_to_dom"]["missing"], 1)

    def test_snapshot_records_response_and_request_body_bytes(self):
        class Response(io.BytesIO):
            status = 201

        with tempfile.TemporaryDirectory() as temp:
            picture = Path(temp) / "fixture.jpg"
            picture.write_bytes(b"\xff\xd8\x00\xff\xd9")
            args = SimpleNamespace(jpeg=picture, camera=4, port=18085, samples=1, timeout=5)
            events = []
            with patch.dict(bench.os.environ, {"P3_AI_SERVICE_KEY": "qa-key"}):
                with patch.object(bench, "urlopen", return_value=Response(b'{"snapshot_id":17}')) as send:
                    result = bench.snapshots(args, lambda kind, **fields: events.append((kind, fields)))
            self.assertEqual(result["http_statuses"], [201])
            self.assertEqual(result["latency"]["samples"], 1)
            self.assertEqual(events[0][1]["snapshot_id"], 17)
            self.assertIn(picture.read_bytes(), send.call_args.args[0].data)

    def test_correlates_live_observer_and_dom_by_alert_id(self):
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / "ws.jsonl"
            browser = Path(temp) / "browser.jsonl"
            source.write_text('{"kind":"alert_sample","sample":{"id":7,"backend_created_ms":100}}\n',
                              encoding="utf-8")
            browser.write_text('{"id":8,"dom_rendered_ms":110}\n'
                               '{"id":7,"dom_rendered_ms":130}\n'
                               '{"id":7,"dom_rendered_ms":120}\n', encoding="utf-8")
            result = bench.alert_latency(SimpleNamespace(input=source, dom_input=browser, expected=1),
                                         lambda *args, **values: None)
            self.assertEqual(result["backend_to_dom"]["max_ms"], 20)
            self.assertEqual(result["backend_to_dom"]["status"], "PASS")


if __name__ == "__main__":
    unittest.main()
