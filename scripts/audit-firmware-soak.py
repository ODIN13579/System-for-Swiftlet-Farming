"""Summarize evidence captured from one ESP32/Wokwi soak run."""

import argparse
from collections import Counter
from datetime import datetime
import json
from pathlib import Path
import re


RESOURCE_FIELD = re.compile(r"(\w+)=([0-9]+)(?:/([0-9]+))?")
RESOURCE_COUNTERS = (
    "free_heap", "min_heap", "buffer", "buffer_capacity", "peak", "dropped", "connects",
    "attempts", "tx_fail", "tx_queue_drop", "rx_drop", "event_drop",
    "loop_max_ms", "net_stack_words",
)


def summarize_serial(lines):
    windows = []
    samples = []
    mode_counts = Counter()
    boot_markers = 0
    uptime_resets = 0
    for line in lines:
        if "[BOOT] P1 FIXED B2 reset_reason=" in line:
            boot_markers += 1
            if samples:
                windows.append(samples)
                samples = []
        mode = re.search(r"\bMode: (AUTO|MANUAL)\b", line)
        if mode:
            mode_counts[mode.group(1)] += 1
        if "[RESOURCE]" not in line:
            continue
        values = {}
        for field, value, capacity in RESOURCE_FIELD.findall(line):
            values[field] = int(value)
            if field == "buffer" and capacity:
                values["buffer_capacity"] = int(capacity)
        if "uptime_ms" not in values:
            continue
        if samples and values["uptime_ms"] < samples[-1]["uptime_ms"]:
            uptime_resets += 1
            windows.append(samples)
            samples = []
        samples.append(values)
    if samples:
        windows.append(samples)

    return {
        "boot_markers": boot_markers,
        "uptime_resets_without_boot_marker": uptime_resets,
        "mode_lines": dict(mode_counts),
        "resource_windows": [summarize_window(window) for window in windows],
    }


def summarize_window(window):
    ticks = [item["uptime_ms"] for item in window]
    counters = {}
    for field in RESOURCE_COUNTERS:
        values = [item[field] for item in window if field in item]
        if values:
            counters[field] = {
                "first": values[0], "last": values[-1], "min": min(values), "max": max(values),
            }
    return {
        "samples": len(window),
        "first_uptime_ms": ticks[0],
        "last_uptime_ms": ticks[-1],
        "observed_span_ms": ticks[-1] - ticks[0],
        "largest_checkpoint_gap_ms": max((b - a for a, b in zip(ticks, ticks[1:])), default=None),
        "counters": counters,
    }


def summarize_mqtt(lines, topic_base):
    counts = Counter()
    ids = set()
    ack_ids = set()
    boot_ids = set()
    heartbeat_times = []
    device_id = topic_base.rsplit("/", 1)[-1]
    for line in lines:
        topic, _, raw = line.partition(" ")
        if not topic.startswith(topic_base + "/"):
            continue
        try:
            data = json.loads(raw)
        except ValueError:
            continue
        if not isinstance(data, dict) or data.get("device_id") != device_id:
            continue
        kind = topic[len(topic_base) + 1:]
        counts[kind] += 1
        if kind == "state" and isinstance(data.get("boot_id"), str):
            boot_ids.add(data["boot_id"])
        event_id = data.get("event_id")
        if kind == "telemetry" and isinstance(event_id, str):
            ids.add(event_id)
        if kind == "telemetry_ack" and data.get("status") == "STORED" and isinstance(event_id, str):
            ack_ids.add(event_id)
        timestamp = data.get("timestamp")
        if kind == "heartbeat" and isinstance(timestamp, str):
            try:
                time = datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
            except ValueError:
                continue
            if time.tzinfo is not None:
                heartbeat_times.append(time)
    gaps = [(b - a).total_seconds() for a, b in zip(heartbeat_times, heartbeat_times[1:])]
    return {
        "messages": dict(counts),
        "state_boot_ids": sorted(boot_ids),
        "telemetry_event_ids": len(ids),
        "matched_stored_ack_ids": len(ids & ack_ids),
        "heartbeat_span_seconds": sum(gaps) if heartbeat_times else None,
        "largest_heartbeat_gap_seconds": max(gaps, default=None),
        "heartbeat_clock_regressions": sum(gap < 0 for gap in gaps),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, type=Path)
    parser.add_argument("--mqtt", type=Path, help="Optional mosquitto_sub -v capture")
    parser.add_argument("--topic-base", help="Exact device topic without a suffix")
    args = parser.parse_args()
    if args.mqtt and not args.topic_base:
        parser.error("--mqtt requires --topic-base")
    with args.serial.open(encoding="utf-8-sig", errors="replace") as serial_file:
        summary = {"serial": summarize_serial(serial_file)}
    if args.mqtt:
        with args.mqtt.open(encoding="utf-8-sig", errors="replace") as mqtt_file:
            summary["mqtt"] = summarize_mqtt(mqtt_file, args.topic_base.rstrip("/"))
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
