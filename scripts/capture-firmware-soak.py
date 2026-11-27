"""Capture new B3-33 evidence from Mosquitto or Wokwi RFC2217."""

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
from queue import Empty, Queue
import re
import subprocess
import sys
from threading import Thread
import time


MQTT_TOPICS = ("heartbeat", "telemetry", "telemetry_ack", "state", "events")
RESOURCE_UPTIME = re.compile(rb"\[RESOURCE\].*\buptime_ms=(\d+)")
BOOT_REASON = re.compile(rb"\[BOOT\].*\breset_reason=(\d+)")


class CaptureStatus:
    def __init__(self, stream, source, progress_seconds):
        self.stream = stream
        self.source = source
        self.progress_seconds = progress_seconds
        self.next_progress = time.monotonic() + progress_seconds
        self.last_data = time.monotonic()
        self.bytes = 0
        self.last_marker = {}

    def record(self, event, **fields):
        row = {"event": event, "source": self.source, "pid": os.getpid(), "bytes": self.bytes,
               "utc": datetime.now(timezone.utc).isoformat(), "monotonic_ns": time.monotonic_ns(), **fields}
        if self.stream is not None:
            self.stream.write(json.dumps(row) + "\n")
            self.stream.flush()
        if event not in {"boot", "resource", "heartbeat"}:
            suffix = f" {fields}" if fields else ""
            print(f"{self.source} {event}: PID {row['pid']}, {self.bytes} bytes{suffix}",
                  file=sys.stderr, flush=True)

    def data(self, size):
        self.bytes += size
        self.last_data = time.monotonic()

    def progress(self):
        now = time.monotonic()
        if now >= self.next_progress:
            self.next_progress = now + self.progress_seconds
            self.record("progress", idle_seconds=round(now - self.last_data, 2), **self.last_marker)


def mqtt_suback(line):
    if not line.startswith("Subscribed ("):
        return None
    _, codes = line.split("):", 1)
    return [int(code) for code in codes.split(",")]


def mqtt_record(line, received_at):
    topic, payload_hex = line.rstrip("\r\n").split("\t", 1)
    return mqtt_payload_record(topic, bytes.fromhex(payload_hex), received_at)


def mqtt_payload_record(topic, raw, received_at):
    record = {
        "received_at_utc": received_at.isoformat(timespec="microseconds").replace("+00:00", "Z"),
        "topic": topic,
    }
    try:
        record["payload"] = raw.decode("utf-8")
    except UnicodeDecodeError:
        record["payload_hex"] = raw.hex()
    return record


def write_mqtt_record(output, status, record, received_monotonic):
    record["received_monotonic_ns"] = received_monotonic
    raw = (json.dumps(record) + "\n").encode("utf-8")
    output.write(raw)
    output.flush()
    status.data(len(raw))
    if record["topic"].endswith("/heartbeat"):
        previous = status.last_marker.get("last_heartbeat_monotonic_ns")
        prior_utc = status.last_marker.get("last_heartbeat_utc")
        status.last_marker["last_heartbeat_monotonic_ns"] = received_monotonic
        status.last_marker["last_heartbeat_utc"] = record["received_at_utc"]
        if "first_heartbeat_utc" not in status.last_marker:
            status.last_marker["first_heartbeat_utc"] = record["received_at_utc"]
            status.record("heartbeat", **status.last_marker)
        if previous is not None:
            gap = (received_monotonic - previous) / 1e9
            status.last_marker["largest_heartbeat_gap_seconds"] = max(
                gap, status.last_marker.get("largest_heartbeat_gap_seconds", 0),
            )
            if gap > 30:
                status.record("heartbeat_gap", from_utc=prior_utc, seconds=gap, **status.last_marker)


def capture_paho(args, output, status):
    import paho.mqtt.client as mqtt

    topics = [f"{args.topic_base.rstrip('/')}/{kind}" for kind in MQTT_TOPICS]
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, reconnect_on_failure=False)
    count = 0
    failure = None

    def connect(conn, _data, _flags, reason, _properties):
        nonlocal failure
        if reason.is_failure:
            failure = f"MQTT CONNACK rejected: {reason}"
        else:
            rc, _mid = conn.subscribe([(topic, 1) for topic in topics])
            if rc != mqtt.MQTT_ERR_SUCCESS:
                failure = f"MQTT subscribe failed: {rc}"

    def subscribe(_conn, _data, _mid, reasons, _properties):
        nonlocal failure
        if len(reasons) != len(topics) or any(reason.is_failure for reason in reasons):
            failure = f"broker refused MQTT subscriptions: {reasons}"
        else:
            status.record("subscribed", granted_qos=[reason.value for reason in reasons])

    def message(_conn, _data, item):
        nonlocal count
        if item.retain:
            return
        received_at = datetime.now(timezone.utc)
        received_monotonic = time.monotonic_ns()
        record = mqtt_payload_record(item.topic, item.payload, received_at)
        write_mqtt_record(output, status, record, received_monotonic)
        count += 1

    def disconnect(_conn, _data, _flags, reason, _properties):
        nonlocal failure
        failure = f"MQTT broker session ended: {reason}"

    client.on_connect = connect
    client.on_subscribe = subscribe
    client.on_message = message
    client.on_disconnect = disconnect
    client.connect(args.host, args.port, keepalive=20)
    try:
        while args.count is None or count < args.count:
            rc = client.loop(timeout=0.25)
            if failure or rc != mqtt.MQTT_ERR_SUCCESS:
                raise OSError(failure or f"MQTT network loop failed: {rc}")
            status.progress()
            if time.monotonic() - status.last_data >= args.idle_seconds:
                raise TimeoutError(f"no MQTT payload for {args.idle_seconds:g}s; inspect broker/Wokwi")
    finally:
        client.disconnect()
    return 0


def capture_mqtt(args, output, status):
    command = [args.mosquitto_sub, "-h", args.host, "-p", str(args.port),
               "-q", "1", "-R", "-d", "-F", r"%t\t%x"]
    for suffix in MQTT_TOPICS:
        command.extend(("-t", args.topic_base.rstrip("/") + "/" + suffix))
    if args.count:
        command.extend(("-C", str(args.count)))
    with subprocess.Popen(command, stdout=subprocess.PIPE, encoding="utf-8") as subscriber:
        messages = Queue()

        def read_messages():
            try:
                for line in subscriber.stdout:
                    messages.put((line, datetime.now(timezone.utc), time.monotonic_ns()))
            except (OSError, UnicodeError) as exc:
                messages.put(exc)
            finally:
                messages.put(None)

        reader = Thread(target=read_messages, daemon=True)
        reader.start()
        subscribed = False
        count = 0
        try:
            while True:
                status.progress()
                if time.monotonic() - status.last_data >= args.idle_seconds:
                    raise TimeoutError(f"no MQTT payload for {args.idle_seconds:g}s; inspect broker/Wokwi")
                try:
                    item = messages.get(timeout=0.25)
                except Empty:
                    continue
                if item is None:
                    rc = subscriber.wait()
                    if rc == 0 and args.count and count == args.count:
                        return 0
                    raise OSError(f"mosquitto_sub exited unexpectedly (exit {rc})")
                if isinstance(item, Exception):
                    raise item
                line, received_at, received_monotonic = item
                if "\t" not in line:
                    codes = mqtt_suback(line)
                    if codes is not None:
                        if len(codes) != len(MQTT_TOPICS) or any(code not in (0, 1, 2) for code in codes):
                            raise OSError(f"broker refused MQTT subscriptions: {codes}")
                        subscribed = True
                        status.record("subscribed", subscriber_pid=subscriber.pid, granted_qos=codes)
                    elif subscribed and "sending CONNECT" in line:
                        raise OSError("mosquitto_sub is reconnecting after losing its broker session")
                    continue
                record = mqtt_record(line, received_at)
                write_mqtt_record(output, status, record, received_monotonic)
                count += 1
        finally:
            if subscriber.poll() is None:
                subscriber.terminate()
            reader.join(timeout=2)


def capture_serial(args, output, status):
    import serial

    deadline = time.monotonic() + args.wait_seconds
    endpoint = f"rfc2217://127.0.0.1:{args.port}"
    while True:
        try:
            connection = serial.serial_for_url(endpoint, baudrate=115200, timeout=0.25)
        except (OSError, serial.SerialException):
            if time.monotonic() >= deadline:
                raise
            time.sleep(0.25)
        else:
            break
    with connection:
        print(f"Serial attached to {endpoint}; resume Wokwi now.", file=sys.stderr, flush=True)
        status.record("attached", endpoint=endpoint)
        pending = b""
        while True:
            data = connection.read(4096)
            if data:
                output.write(data)
                output.flush()
                status.data(len(data))
                *lines, pending = (pending + data).split(b"\n")
                for line in lines:
                    marker = RESOURCE_UPTIME.search(line)
                    if marker:
                        status.last_marker["last_resource_uptime_ms"] = int(marker[1])
                        status.record("resource", uptime_ms=int(marker[1]))
                    boot = BOOT_REASON.search(line)
                    if boot:
                        status.record("boot", reset_reason=int(boot[1]))
                if len(pending) > 8192:
                    pending = pending[-8192:]
            status.progress()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="source", required=True)
    mqtt = commands.add_parser("mqtt")
    mqtt.add_argument("--mosquitto-sub", default="mosquitto_sub")
    mqtt.add_argument("--driver", choices=("mosquitto", "paho"), default="mosquitto")
    mqtt.add_argument("--host", default="127.0.0.1")
    mqtt.add_argument("--port", type=int, default=1883)
    mqtt.add_argument("--topic-base", required=True)
    mqtt.add_argument("--count", type=int, help="Stop after this many messages")
    mqtt.add_argument("--idle-seconds", type=float, default=180,
                      help="Fail on this many seconds without a payload (diagnostic, not a Jira limit)")
    serial = commands.add_parser("serial")
    serial.add_argument("--port", type=int, default=4000)
    serial.add_argument("--wait-seconds", type=float, default=60)
    for command in (mqtt, serial):
        command.add_argument("--output", type=Path, required=True)
        command.add_argument("--status", type=Path, help="New JSONL status journal")
        command.add_argument("--progress-seconds", type=float, default=15)
    args = parser.parse_args()
    if args.source == "mqtt" and args.count is not None and args.count < 1:
        parser.error("--count must be positive")
    if args.progress_seconds <= 0 or (args.source == "mqtt" and args.idle_seconds <= 0):
        parser.error("progress and idle intervals must be positive")
    capture = capture_serial
    if args.source == "mqtt":
        capture = capture_paho if args.driver == "paho" else capture_mqtt
    try:
        from contextlib import nullcontext

        with args.output.open("xb") as output, (
            args.status.open("x", encoding="utf-8") if args.status else nullcontext()
        ) as journal:
            status = CaptureStatus(journal, args.source, args.progress_seconds)
            status.record("started", output=str(args.output))
            try:
                rc = capture(args, output, status)
            except KeyboardInterrupt:
                status.record("operator_stop")
                return 130
            except (OSError, ValueError, ImportError) as exc:
                status.record("error", reason=str(exc))
                return 1
            status.record("completed", exit_code=rc)
            return rc
    except (OSError, ValueError, ImportError) as exc:
        print(f"Capture failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
