"""Prepare, monitor and record operator milestones for one official B3-33 run."""

import argparse
from datetime import datetime, timezone
from hashlib import file_digest
import json
from pathlib import Path
import platform
import subprocess
import time
import tomllib
import uuid


TOPIC = "swiftletcare/1/1/ESP32-HOUSE01-ZONE01"
MINIMUM_SECONDS = 7200


def records(path):
    if not path.exists():
        return []
    with path.open("rb") as stream:
        return [json.loads(line) for line in stream if line.endswith(b"\n")]


def identity(project):
    config = project / "wokwi.toml"
    with config.open("rb") as stream:
        wokwi = tomllib.load(stream)["wokwi"]
    expected = {"version": 1, "firmware": ".pio/build/esp32dev/firmware.bin",
                "elf": ".pio/build/esp32dev/firmware.elf", "rfc2217ServerPort": 4000, "gdbServerPort": 3333}
    if any(wokwi.get(key) != value for key, value in expected.items()):
        raise ValueError("Wokwi firmware paths or RFC2217/GDB ports differ from the official configuration")
    result = {}
    for key in ("firmware", "elf"):
        artifact = project / wokwi[key]
        with artifact.open("rb") as stream:
            result[key] = {"path": str(artifact), "sha256": file_digest(stream, "sha256").hexdigest()}
    return {"project": str(project), "config": wokwi, "artifacts": result}


def prepare(args):
    git = lambda *params: subprocess.check_output(["git", *params], text=True).strip()
    current = git("rev-parse", "HEAD")
    if args.commit != current:
        raise ValueError("build commit differs from repository HEAD; verify the staging source")
    modified = git("status", "--porcelain", "--untracked-files=all", "--", "firmware")
    if any(line.strip() != "M firmware/wokwi.toml" for line in modified.splitlines()):
        raise ValueError("firmware source has local changes; review staging provenance first")
    hardware = identity(args.project.resolve())
    session = args.root / f"official-{datetime.now():%Y%m%d-%H%M%S}-{uuid.uuid4().hex[:8]}"
    session.mkdir()
    data = {"session": session.name, "git_commit": current, "branch": git("branch", "--show-current"),
            "firmware_version": current, "topic_base": TOPIC, "device_id": TOPIC.rsplit("/", 1)[1],
            "mqtt_broker": "127.0.0.1:1883", "wokwi_mqtt": "host.wokwi.internal:1883",
            "backend_test_url": "http://127.0.0.1:18083", "postgres_test": "127.0.0.1:55434/build2_test",
            "host": {"os": platform.platform(), "cpu": platform.processor(), "python": platform.python_version()},
            **hardware}
    with (session / "manifest.json").open("x", encoding="utf-8") as stream:
        json.dump(data, stream, indent=2)
    (session / "timeline.jsonl").touch(exist_ok=False)
    print(session)


def last_event(items, event):
    return next((row for row in reversed(items) if row["event"] == event), None)


def duration_evidence(timeline, serial):
    start = last_event(timeline, "start")
    end = last_event(timeline, "end")
    if start is None:
        return {"wall_seconds": None, "resource_uptime_ms": None, "resource_wall_seconds": None}
    lo = start["monotonic_ns"]
    hi = end["monotonic_ns"] if end else time.monotonic_ns()
    checkpoints = [row for row in serial if row["event"] == "resource" and lo <= row["monotonic_ns"] <= hi]
    return {
        "wall_seconds": (hi - lo) / 1e9,
        "resource_uptime_ms": checkpoints[-1]["uptime_ms"] - checkpoints[0]["uptime_ms"] if checkpoints else None,
        "resource_wall_seconds": ((checkpoints[-1]["monotonic_ns"] - checkpoints[0]["monotonic_ns"]) / 1e9
                                  if checkpoints else None),
        "resource_checkpoints": len(checkpoints),
        "resource_largest_wall_gap_seconds": max(
            ((b["monotonic_ns"] - a["monotonic_ns"]) / 1e9 for a, b in zip(checkpoints, checkpoints[1:])),
            default=None),
    }


def read_session(session):
    return (records(session / "timeline.jsonl"), records(session / "serial-status.jsonl"),
            records(session / "mqtt-status.jsonl"))


def mark(args):
    path = args.session / "timeline.jsonl"
    timeline, serial, mqtt = read_session(args.session)
    manifest_path = args.session / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    event = args.event
    if last_event(timeline, "end") or (event not in {"pause", "resume-after-pause", "note"}
                                       and last_event(timeline, event)):
        raise ValueError("milestone already recorded or session ended")
    if event in {"resume", "start"} and any(
        health(rows, time.monotonic_ns(), 45)["state"] != "ACTIVE" for rows in (serial, mqtt)
    ):
        raise ValueError("both collector journals must be active before this milestone")
    if event == "resume":
        if not last_event(serial, "attached") or not last_event(mqtt, "subscribed"):
            raise ValueError("Serial attach and MQTT SUBACK must be recorded before CPU resume")
        if last_event(serial, "boot"):
            raise ValueError("CPU already booted before the resume milestone")
        if identity(Path(manifest["project"]))["artifacts"] != manifest["artifacts"]:
            raise ValueError("firmware hash changed before CPU resume")
    if event == "start":
        if not ((last_event(timeline, "resume") or last_event(timeline, "boot-observed")) and last_event(serial, "boot")
                and last_event(serial, "resource") and last_event(mqtt, "heartbeat")):
            raise ValueError("resume, boot/reset reason, RESOURCE and Heartbeat must be recorded")
        if len([row for row in serial if row["event"] == "boot"]) != 1:
            raise ValueError("expected exactly one initial boot before counting")
    if not args.note.strip():
        raise ValueError("record an operator note")
    stamp = {"event": event, "utc": datetime.now(timezone.utc).isoformat(),
             "monotonic_ns": time.monotonic_ns(), "note": args.note}
    if event == "boot-observed":
        boot = last_event(serial, "boot")
        attached = last_event(serial, "attached")
        subscribed = last_event(mqtt, "subscribed")
        if not (boot and attached and subscribed and
                max(attached["monotonic_ns"], subscribed["monotonic_ns"]) < boot["monotonic_ns"]
                and sum(row["event"] == "boot" for row in serial) == 1):
            raise ValueError("need one observed BOOT after both Serial attach and MQTT SUBACK")
        stamp["boot_observed_utc"] = boot["utc"]
        stamp["boot_observed_monotonic_ns"] = boot["monotonic_ns"]
        stamp["reset_reason"] = boot["reset_reason"]
    with path.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(stamp) + "\n")
    print(json.dumps(stamp))


def health(items, now, max_age):
    latest = items[-1] if items else None
    if latest is None:
        return {"state": "WAITING"}
    age = (now - latest["monotonic_ns"]) / 1e9
    state = latest["event"] if latest["event"] in {"error", "operator_stop", "completed"} else "ACTIVE"
    if state == "ACTIVE" and (age > max_age or age < 0):
        state = "STALE"
    return {"state": state, "status_age_seconds": round(age, 1), "pid": latest["pid"],
            "bytes": latest["bytes"], "last_status": latest}


def report(session, publish_summary=False):
    timeline, serial, mqtt = read_session(session)
    now = time.monotonic_ns()
    duration = duration_evidence(timeline, serial)
    since = lambda rows, event: next((round((now-row["monotonic_ns"])/1e9, 1) for row in reversed(rows)
                                     if row["event"] == event), None)
    serial_status, mqtt_status = health(serial, now, 45), health(mqtt, now, 45)
    result = {"duration": duration, "serial": serial_status, "mqtt": mqtt_status,
              "last_resource_age_seconds": since(serial, "resource"),
              "last_mqtt_heartbeat_utc": next((r["last_heartbeat_utc"] for r in reversed(mqtt)
                                               if "last_heartbeat_utc" in r), None)}
    heartbeat = result["last_mqtt_heartbeat_utc"]
    heartbeat_age = ((datetime.now(timezone.utc) - datetime.fromisoformat(heartbeat)).total_seconds()
                     if heartbeat else None)
    result["last_mqtt_heartbeat_age_seconds"] = round(heartbeat_age, 1) if heartbeat_age is not None else None
    result["diagnostics"] = []
    for name, value, interval in (("RESOURCE", result["last_resource_age_seconds"], 90),
                                  ("MQTT Heartbeat", heartbeat_age, 30)):
        if value is None or value > interval or value < 0:
            result["diagnostics"].append(f"inspect {name}: missing or stale observation")
    if not publish_summary:
        print(json.dumps(result, indent=2))
        return
    manifest_path = session / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    audit_path = session / "audit-result.json"
    audit = json.loads(audit_path.read_text(encoding="utf-8"))
    if not last_event(timeline, "end"):
        raise ValueError("record the end milestone before finalizing")
    problems = []
    if any(last_event(timeline, event) is None for event in ("start", "end")):
        problems.append("missing start/end milestone")
    if not last_event(timeline, "resume"):
        problems.append("exact GDB resume time unavailable; review separately recorded BOOT")
    if ((duration["wall_seconds"] or 0) < MINIMUM_SECONDS
            or (duration["resource_uptime_ms"] or 0) < MINIMUM_SECONDS * 1000
            or (duration["resource_wall_seconds"] or 0) < MINIMUM_SECONDS):
        problems.append("less than 120 minutes in one wall clock and RESOURCE checkpoint window")
    if last_event(timeline, "pause") or last_event(timeline, "resume-after-pause"):
        problems.append("operator recorded a Wokwi pause")
    if any(last_event(rows, "error") for rows in (serial, mqtt)):
        problems.append("collector error in journal")
    if any(not last_event(rows, "operator_stop") for rows in (serial, mqtt)):
        problems.append("missing clean operator stop in collector journal")
    if identity(Path(manifest["project"]))["artifacts"] != manifest["artifacts"]:
        problems.append("staging binary/ELF hash changed")
    serial_audit, mqtt_audit = audit.get("serial") or {}, audit.get("mqtt") or {}
    if serial_audit.get("boot_markers") != 1 or serial_audit.get("uptime_resets_without_boot_marker"):
        problems.append("review boot markers and reset/uptime regression")
    if mqtt_audit.get("boot_id_change_count") or mqtt_audit.get("manual_observed"):
        problems.append("review MQTT boot IDs and mode transitions")
    if not mqtt_audit.get("matched_telemetry_ack_and_serial_removal_ids"):
        problems.append("no event_id confirmed in telemetry, STORED ACK and Serial removal")
    receiver = mqtt_audit.get("subscriber_heartbeat", {})
    if receiver.get("clock_regressions") or receiver.get("monotonic_regressions"):
        problems.append("subscriber clock regression")
    if audit.get("evidence_issues"):
        # An event-free run does not require an events message.
        problems.extend(f"{key}: {value}" for key, value in audit["evidence_issues"].items() if key != "events")
    if (duration.get("resource_largest_wall_gap_seconds") or 0) > 90:
        problems.append("inspect RESOURCE receipt gaps over 90 seconds (diagnostic)")
    if (receiver.get("monotonic_largest_gap_seconds") or receiver.get("largest_gap_seconds") or 0) > 30:
        problems.append("inspect MQTT Heartbeat receipt gaps over 30 seconds (diagnostic)")
    manifest["timeline"] = timeline
    manifest["start_utc"] = (last_event(timeline, "start") or {}).get("utc")
    manifest["end_utc"] = (last_event(timeline, "end") or {}).get("utc")
    manifest["wokwi_resume_utc"] = (last_event(timeline, "resume") or {}).get("utc")
    manifest["boot_observed_utc"] = (last_event(timeline, "boot-observed") or {}).get("boot_observed_utc")
    manifest["collector_status"] = {"serial": serial_status, "mqtt": mqtt_status}
    manifest["duration_evidence"] = duration
    manifest["review_findings"] = problems
    with (session / "soak-summary.md").open("x", encoding="utf-8") as stream:
        stream.write("# B3-33 — Kiểm tra bằng chứng lượt chính thức\n\n")
        stream.write("Trạng thái công cụ: PARTIAL / REQUIRES REVIEW\n\n")
        stream.write(f"- Firmware: {manifest['firmware_version']}\n")
        stream.write(f"- Thời gian thực trong khung đo (giây): {duration['wall_seconds']}\n")
        stream.write(f"- Khoảng RESOURCE theo uptime (ms): {duration['resource_uptime_ms']}\n")
        stream.write(f"- Khoảng RESOURCE theo đồng hồ máy thu (giây): {duration['resource_wall_seconds']}\n")
        stream.write(f"- Khoảng RESOURCE lớn nhất theo máy thu (giây): {duration.get('resource_largest_wall_gap_seconds')}\n")
        stream.write(f"- Heartbeat / Telemetry / ACK / State / Events: {mqtt_audit.get('messages')}\n")
        stream.write(f"- Khoảng Heartbeat lớn nhất theo UTC máy thu (giây): {receiver.get('largest_gap_seconds')}\n")
        stream.write(f"- Số ID Telemetry có STORED và Serial removal: {mqtt_audit.get('matched_telemetry_ack_and_serial_removal_ids')}\n")
        stream.write(f"- Boot/reset reason: {serial_audit.get('boot_records')}\n")
        stream.write(f"- Vấn đề cần đối chiếu: {', '.join(problems) or 'review gaps, resource trend and Jira criteria'}\n")
        stream.write("\n[Manifest](manifest.json) · [Audit](audit-result.json) · [Serial](serial-soak.log) · [MQTT](mqtt-soak.jsonl)\n")
    temp = session / "manifest-finalizing.json"
    with temp.open("x", encoding="utf-8") as stream:
        json.dump(manifest, stream, indent=2)
    temp.replace(manifest_path)
    print(json.dumps({"review_findings": problems, **result}, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prep = commands.add_parser("prepare")
    prep.add_argument("--project", type=Path, required=True)
    prep.add_argument("--commit", required=True)
    prep.add_argument("--root", type=Path, required=True)
    milestone = commands.add_parser("mark")
    milestone.add_argument("event", choices=("resume", "boot-observed", "start", "pause", "resume-after-pause", "end", "note"))
    milestone.add_argument("note")
    for command in (milestone, commands.add_parser("monitor"), commands.add_parser("finalize")):
        command.add_argument("--session", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            prepare(args)
        elif args.command == "mark":
            mark(args)
        else:
            report(args.session, args.command == "finalize")
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as exc:
        parser.exit(1, f"B3-33: {exc}\n")


if __name__ == "__main__":
    main()
