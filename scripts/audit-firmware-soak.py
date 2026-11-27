"""Summarize evidence captured from one ESP32/Wokwi soak run."""

import argparse
from collections import Counter
from datetime import datetime, timezone
from hashlib import sha256
import json
import os
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
    reset_reasons = Counter()
    boot_records = []
    uptime_resets = 0
    mqtt_connected_markers = 0
    removed_stored_ids = set()
    for number, line in enumerate(lines, 1):
        if "[MQTT] CONNECTED;" in line:
            mqtt_connected_markers += 1
        removed = re.search(r"\[BUFFER\] Removed STORED: (\S+) \|", line)
        if removed:
            removed_stored_ids.add(removed.group(1))
        if "[BOOT] P1 FIXED B2 reset_reason=" in line:
            boot_markers += 1
            reason = re.search(r"\breset_reason=(\d+)\b", line)
            if reason:
                reset_reasons[reason.group(1)] += 1
                boot_records.append({"line": number, "reset_reason": int(reason.group(1))})
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
        "reset_reasons": dict(reset_reasons),
        "boot_records": boot_records,
        "mqtt_connected_markers": mqtt_connected_markers,
        "removed_stored_ids": sorted(removed_stored_ids),
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
        "free_heap_delta_bytes": (counters["free_heap"]["last"] - counters["free_heap"]["first"]
                                  if "free_heap" in counters else None),
        "free_heap_checkpoints": [
            {"uptime_ms": item["uptime_ms"], "free_heap": item["free_heap"],
             "min_heap": item.get("min_heap")} for item in window if "free_heap" in item
        ],
    }



def summarize_mqtt(lines, topic_base, serial_removed_ids=()):
    kinds = ("heartbeat", "telemetry", "telemetry_ack", "state", "events")
    counts = dict.fromkeys(kinds, 0)
    ids = Counter()
    ack_ids = set()
    ack_statuses = Counter()
    boot_ids = Counter()
    modes = Counter()
    diagnostics = Counter()
    rejected = Counter()
    clocks = {kind: Counter() for kind in kinds}
    seen_times = {kind: set() for kind in kinds}
    previous = {}
    boot_transitions = []
    interruptions = []
    heartbeat_first = None
    heartbeat_last = None
    largest_gap = None
    receiver_first = None
    receiver_previous = None
    receiver_count = 0
    receiver_largest_gap = None
    receiver_interruptions = []
    receiver_monotonic_times = []
    last_boot = None
    device_id = topic_base.rsplit("/", 1)[-1]
    prefix = topic_base + "/"
    for number, line in enumerate(lines, start=1):
        received_at = None
        received_monotonic = None
        if line.startswith("{"):
            try:
                record = json.loads(line)
                topic = record["topic"]
                raw = record.get("payload")
                received_at = parse_timestamp(record.get("received_at_utc"))
                received_monotonic = record.get("received_monotonic_ns")
                if not isinstance(topic, str):
                    raise ValueError("non-string topic")
            except (ValueError, KeyError, TypeError):
                rejected["invalid_capture_record"] += 1
                continue
        else:
            topic, _, raw = line.partition(" ")
        if not topic.startswith(prefix):
            rejected["outside_topic_or_format"] += 1
            continue
        kind = topic[len(prefix):]
        if kind not in counts:
            rejected["other_device_topic"] += 1
            continue
        try:
            data = json.loads(raw)
        except (ValueError, TypeError):
            rejected["invalid_json"] += 1
            continue
        if not isinstance(data, dict) or data.get("device_id") != device_id:
            rejected["invalid_object_or_device_id"] += 1
            continue
        counts[kind] += 1
        if kind == "heartbeat" and type(received_monotonic) is int:
            receiver_monotonic_times.append(received_monotonic)
        if kind == "heartbeat" and received_at is not None:
            receiver_count += 1
            if receiver_first is None:
                receiver_first = received_at
            if receiver_previous is not None:
                gap = (received_at - receiver_previous).total_seconds()
                if gap >= 0 and (receiver_largest_gap is None or gap > receiver_largest_gap):
                    receiver_largest_gap = gap
                if gap > 5 or gap < 0:
                    receiver_interruptions.append({
                        "line": number, "from": receiver_previous.isoformat(),
                        "to": received_at.isoformat(), "seconds": gap,
                    })
            receiver_previous = received_at
        diagnostics.update(data.keys())
        boot_id = data.get("boot_id")
        if isinstance(boot_id, str) and boot_id:
            boot_ids[boot_id] += 1
            if last_boot is not None and last_boot != boot_id:
                boot_transitions.append({"line": number, "from": last_boot, "to": boot_id})
            last_boot = boot_id
        mode = data.get("mode")
        if isinstance(mode, str):
            modes[mode] += 1
        event_id = data.get("event_id")
        if kind == "telemetry" and isinstance(event_id, str):
            ids[event_id] += 1
        if kind == "telemetry_ack":
            ack_statuses[str(data.get("status"))] += 1
            if data.get("status") == "STORED" and isinstance(event_id, str):
                ack_ids.add(event_id)
        timestamp = data.get("timestamp")
        time = parse_timestamp(timestamp)
        if time is None:
            clocks[kind]["missing_or_null" if timestamp is None else "invalid"] += 1
            continue
        clocks[kind]["valid"] += 1
        if time in seen_times[kind]:
            clocks[kind]["duplicates"] += 1
        seen_times[kind].add(time)
        prior = previous.get(kind)
        previous[kind] = time
        if prior is not None and time < prior:
            clocks[kind]["regressions"] += 1
        if kind != "heartbeat":
            continue
        if heartbeat_first is None:
            heartbeat_first = time
        heartbeat_last = time
        if prior is None:
            continue
        gap = (time - prior).total_seconds()
        if gap >= 0 and (largest_gap is None or gap > largest_gap):
            largest_gap = gap
        if gap > 5 or gap < 0:
            interruptions.append({
                "line": number, "from": prior.isoformat(), "to": time.isoformat(), "seconds": gap,
            })
    telemetry_times = seen_times["telemetry"]
    stored_matches = len(ack_ids.intersection(ids))
    monotonic_gaps = [(b - a) / 1e9 for a, b in zip(receiver_monotonic_times, receiver_monotonic_times[1:])]
    return {
        "messages": counts,
        "rejected_lines": dict(rejected),
        "payload_field_counts": dict(sorted(diagnostics.items())),
        "timestamp_source": "MQTT payload (device clock)",
        "subscriber_heartbeat": {
            "valid_receipt_timestamps": receiver_count,
            "missing_or_invalid_receipt_timestamps": counts["heartbeat"] - receiver_count,
            "first_utc": receiver_first.isoformat() if receiver_first else None,
            "last_utc": receiver_previous.isoformat() if receiver_previous else None,
            "span_seconds": (receiver_previous - receiver_first).total_seconds() if receiver_first else None,
            "largest_gap_seconds": receiver_largest_gap,
            "gaps_over_seconds": {
                threshold: sum(item["seconds"] > threshold for item in receiver_interruptions)
                for threshold in (5, 10, 30)
            },
            "clock_regressions": sum(item["seconds"] < 0 for item in receiver_interruptions),
            "interruptions_or_regressions": receiver_interruptions,
            "monotonic_samples": len(receiver_monotonic_times),
            "monotonic_largest_gap_seconds": max(monotonic_gaps, default=None),
            "monotonic_regressions": sum(gap < 0 for gap in monotonic_gaps),
        },
        "timestamps_by_topic": {
            kind: {field: clocks[kind][field] for field in
                   ("valid", "missing_or_null", "invalid", "duplicates", "regressions")}
            for kind in kinds
        },
        "boot_id_message_counts": dict(boot_ids),
        "boot_id_transitions": boot_transitions,
        "boot_id_change_count": len(boot_transitions),
        "modes": dict(modes),
        "manual_observed": "MANUAL" in modes,
        "telemetry_event_ids": len(ids),
        "telemetry_timestamp_range": {
            "first_utc": min(telemetry_times).isoformat() if telemetry_times else None,
            "last_utc": max(telemetry_times).isoformat() if telemetry_times else None,
            "unique_timestamps": len(telemetry_times),
        },
        "telemetry_copies_per_event_id": {
            "min": min(ids.values(), default=None),
            "max": max(ids.values(), default=None),
        },
        "ack_statuses": dict(ack_statuses),
        "matched_stored_ack_ids": stored_matches,
        "matched_telemetry_ack_and_serial_removal_ids": len(ack_ids.intersection(ids, serial_removed_ids)),
        "stored_ack_percent": 100 * stored_matches / len(ids) if ids else None,
        "heartbeat_first": heartbeat_first.isoformat() if heartbeat_first else None,
        "heartbeat_last": heartbeat_last.isoformat() if heartbeat_last else None,
        "heartbeat_span_seconds": (
            (heartbeat_last - heartbeat_first).total_seconds() if heartbeat_first else None
        ),
        "largest_heartbeat_gap_seconds": largest_gap,
        "heartbeat_gaps_over_seconds": {
            threshold: sum(item["seconds"] > threshold for item in interruptions)
            for threshold in (5, 10, 30)
        },
        "heartbeat_interruptions_or_regressions": interruptions,
    }


def parse_timestamp(value):
    if not isinstance(value, str):
        return None
    if value.endswith("Z"):
        value = value[:-1] + "+00:00"
    try:
        time = datetime.fromisoformat(value)
        return time.astimezone(timezone.utc) if time.tzinfo is not None else None
    except (ValueError, OverflowError):
        return None


def bounded_lines(stream, size, digest, capture):
    """Read the initial byte range, ignoring a final line still being appended."""
    remaining = size
    while remaining:
        raw = stream.readline(remaining)
        if not raw:
            raise OSError("capture was truncated during read")
        remaining -= len(raw)
        digest.update(raw)
        if not raw.endswith(b"\n"):
            capture["ignored_unterminated_tail_bytes"] = len(raw)
            break
        encoding = "utf-8-sig" if size - remaining == len(raw) else "utf-8"
        yield raw.decode(encoding, errors="replace")


def analyze_capture(path, summarize):
    if path.name.lower().startswith("soak-synthetic-"):
        raise ValueError("synthetic fixture excluded from evidence")
    with path.open("rb") as stream:
        size = os.fstat(stream.fileno()).st_size
        capture = {"path": str(path), "initial_bytes": size, "ignored_unterminated_tail_bytes": 0}
        digest = sha256()
        result = summarize(bounded_lines(stream, size, digest, capture))
        capture["sha256_initial_bytes"] = digest.hexdigest()
        capture["size_after_read"] = os.fstat(stream.fileno()).st_size
        if capture["size_after_read"] < size:
            raise OSError("capture shrank during read")
    return {"capture": capture, **result}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", type=Path)
    parser.add_argument("--mqtt", type=Path, help="mosquitto_sub -v capture")
    parser.add_argument("--topic-base", help="Exact device topic without a suffix")
    parser.add_argument("--output", type=Path, help="Create a new JSON file")
    parser.add_argument("--serial-status", type=Path)
    parser.add_argument("--mqtt-status", type=Path)
    args = parser.parse_args()
    if args.mqtt and not args.topic_base:
        parser.error("--mqtt requires --topic-base")
    summary = {}
    issues = {}
    for name, path, summarize in (
        ("serial", args.serial, summarize_serial),
        ("mqtt", args.mqtt, lambda lines: summarize_mqtt(
            lines, args.topic_base.rstrip("/"), (summary["serial"] or {}).get("removed_stored_ids", []))),
    ):
        try:
            if path is None:
                raise ValueError("capture not supplied")
            summary[name] = analyze_capture(path, summarize)
        except (OSError, ValueError) as exc:
            summary[name] = None
            issues[name] = f"MISSING EVIDENCE: {exc}"

    serial = summary["serial"] or {}
    windows = serial.get("resource_windows", [])
    for field in ("free_heap", "connects", "attempts"):
        if not any(field in window["counters"] for window in windows):
            issues[field] = "MISSING EVIDENCE: no Serial resource samples for this field"
    if not serial.get("reset_reasons"):
        issues["reset_reason"] = "MISSING EVIDENCE: no numeric reset reason in Serial boot markers"
    mqtt = summary["mqtt"] or {}
    for kind in ("heartbeat", "telemetry", "telemetry_ack", "state", "events"):
        if mqtt and not mqtt["messages"][kind]:
            issues[kind] = "MISSING EVIDENCE: no matching MQTT messages"
    receiver = mqtt.get("subscriber_heartbeat", {})
    if not receiver.get("valid_receipt_timestamps") or receiver["missing_or_invalid_receipt_timestamps"]:
        issues["receiver_clock"] = "MISSING EVIDENCE: complete subscriber receipt time unavailable"
    for source, path in (("serial", args.serial_status), ("mqtt", args.mqtt_status)):
        if path is None:
            continue
        try:
            with path.open(encoding="utf-8") as stream:
                rows = [json.loads(line) for line in stream]
            summary[f"{source}_collector"] = {
                "total_records": len(rows), "last_status": rows[-1] if rows else None,
                "events": [row for row in rows if row.get("event") != "progress"],
            }
            if not rows:
                issues[f"{source}_collector"] = "MISSING EVIDENCE: status journal empty"
            elif any(row.get("event") == "error" for row in rows):
                issues[f"{source}_collector"] = "collector reported an error; review journal"
        except (OSError, ValueError) as exc:
            issues[f"{source}_collector"] = f"MISSING EVIDENCE: {exc}"
    summary["evidence_issues"] = issues
    summary["acceptance"] = "REQUIRES REVIEW"
    output = json.dumps(summary, indent=2)
    if args.output:
        with args.output.open("x", encoding="utf-8") as stream:
            stream.write(output + "\n")
    else:
        print(output)


if __name__ == "__main__":
    main()
