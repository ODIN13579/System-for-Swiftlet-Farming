"""B3-22 reproducible QA measurements. Run only with dedicated test services."""

import argparse
import json
import math
import os
import platform
import queue
import statistics
import threading
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

from urllib.error import HTTPError, URLError
from urllib.request import Request, build_opener, HTTPRedirectHandler, urlopen


def utc():
    return datetime.now(timezone.utc).isoformat()


def metrics(values, expected, limit=None):
    ordered = sorted(values)
    return {
        "expected": expected,
        "samples": len(ordered),
        "missing": expected - len(ordered),
        "p50_ms": statistics.median(ordered) if ordered else None,
        "p95_ms": ordered[math.ceil(0.95 * len(ordered)) - 1] if ordered else None,
        "max_ms": ordered[-1] if ordered else None,
        "limit_ms": limit,
        "status": ("NOT TESTED" if not expected else
                   "FAIL" if len(ordered) != expected or (limit is not None and ordered[-1] > limit)
                   else "PASS" if limit is not None else "MEASURED"),
    }


def mqtt_load(args, record):
    import paho.mqtt.client as mqtt

    prefix = uuid.uuid4().hex
    devices = [f"B3P3-QA-{number:02}" for number in range(1, 51)]
    topics = {f"{prefix}-{cycle}-{number}": f"swiftletcare/{args.house}/{args.zone}/{code}"
              for cycle in range(args.cycles) for number, code in enumerate(devices)}
    publishers = {}
    sent, observed, received = {}, {}, {}
    duplicate_acks = 0
    errors = []
    ready, done = threading.Event(), threading.Event()
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
    lock = threading.Lock()

    def on_subscribe(_client, _userdata, _mid, codes, _properties):
        if codes and all(not code.is_failure for code in codes):
            ready.set()

    def on_message(_client, _userdata, message):
        nonlocal duplicate_acks
        arrived = time.monotonic_ns()
        try:
            data = json.loads(message.payload)
            event_id = data.get("event_id")
        except (ValueError, AttributeError):
            return
        if not isinstance(event_id, str):
            return
        base = topics.get(event_id)
        if not base or message.retain or data.get("device_id") != base.rsplit("/", 1)[1]:
            return
        if message.topic == base + "/telemetry":
            samples, kind = observed, "broker_observed"
        elif message.topic == base + "/telemetry_ack" and data.get("status") == "STORED":
            samples, kind = received, "stored"
        else:
            return
        with lock:
            if kind == "stored" and event_id in received:
                duplicate_acks += 1
                record("duplicate_stored", event_id=event_id, mqtt_dup=message.dup)
                return
            if event_id in sent and event_id not in samples:
                samples[event_id] = (arrived - sent[event_id]) / 1_000_000
                record(kind, event_id=event_id, elapsed_ms=samples[event_id])
                if len(received) == len(observed) == len(topics):
                    done.set()

    def on_connect(conn, _userdata, _flags, code, _props):
        if not code.is_failure:
            base = f"swiftletcare/{args.house}/{args.zone}/+/"
            conn.subscribe([(base + "telemetry", 0), (base + "telemetry_ack", 1)])

    client.on_connect, client.on_subscribe, client.on_message = on_connect, on_subscribe, on_message
    client.connect("127.0.0.1", args.port)
    client.loop_start()
    try:
        if not ready.wait(5):
            record("error", reason="no SUBACK")
            return {"publish_errors": 1, "timeouts": len(topics), "status": "FAIL",
                    "stored_ack": metrics([], len(topics))}
        if args.individual_clients:
            for code in devices:
                connected = threading.Event()
                publisher = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=f"{prefix[:12]}-{code}")

                def on_publisher_connect(_conn, _userdata, _flags, reason, _properties, signal=connected):
                    if not reason.is_failure:
                        signal.set()

                publisher.on_connect = on_publisher_connect
                publisher.connect("127.0.0.1", args.port)
                publishers[code] = publisher
                publisher.loop_start()
                if not connected.wait(5):
                    raise ValueError(f"no CONNACK for {code}")
                record("device_connected", device_id=code)
        for event_id, base in topics.items():
            data = {"event_id": event_id, "device_id": base.rsplit("/", 1)[1], "timestamp": utc(),
                    "temperature": 27, "humidity": 80, "co2": 450, "light": 40, "sensor_status": "OK"}
            with lock:
                sent[event_id] = time.monotonic_ns()
                record("publish", event_id=event_id, topic=base + "/telemetry")
            publisher = publishers.get(data["device_id"], client)
            info = publisher.publish(base + "/telemetry", json.dumps(data), qos=0)
            if info.rc != mqtt.MQTT_ERR_SUCCESS:
                errors.append(event_id)
                record("error", event_id=event_id, rc=int(info.rc))
        deadline_met = done.wait(args.timeout)
        if deadline_met:
            time.sleep(1)  # Observe late duplicates without extending the first-ACK deadline.
    finally:
        for publisher in publishers.values():
            publisher.disconnect()
            publisher.loop_stop()
        client.disconnect()
        client.loop_stop()
    result = metrics(received.values(), len(topics))
    broker = metrics(observed.values(), len(topics))
    return {"devices": len(devices), "publisher_connections": len(publishers) or 1,
            "cycles": args.cycles, "publish_errors": len(errors), "duplicate_acks": duplicate_acks,
            "deadline_met": deadline_met,
            "timeouts": len(set(topics) - received.keys() - set(errors)),
            "status": ("PASS" if deadline_met and result["missing"] == broker["missing"] == 0
                       and not errors and not duplicate_acks else "FAIL"),
            "broker_observed": broker,
            "stored_ack": result}


def authorization(args, record):
    class NoRedirect(HTTPRedirectHandler):
        def redirect_request(self, *unused, **kwargs):
            return None

    opener = build_opener(NoRedirect())
    path = f"http://127.0.0.1:{args.port}/api/v1/alerts?houseId={args.house}"
    results = {}
    for label, allowed in (("owner", {200}), ("expired", {401, 403}), ("foreign", {403})):
        token = os.environ.get("P3_" + label.upper() + "_JWT")
        if not token:
            results[label] = "NOT TESTED"
            continue
        request = Request(path, headers={"Authorization": "Bearer " + token})
        try:
            with opener.open(request, timeout=args.timeout) as response:
                code = response.status
        except HTTPError as exc:
            code = exc.code
        except (URLError, TimeoutError):
            code = None
        record("authorization", case=label, http_status=code)
        results[label] = "PASS" if code in allowed else "FAIL"
    if results["owner"] != "PASS":
        for label in ("expired", "foreign"):
            if results[label] == "PASS":
                results[label] = "NOT TESTED"
    return results


def snapshots(args, record):
    key = os.environ.get("P3_AI_SERVICE_KEY")
    if not key:
        raise ValueError("P3_AI_SERVICE_KEY is required")
    image = args.jpeg.read_bytes()
    if not image.startswith(b"\xff\xd8") or len(image) > 300 * 1024:
        raise ValueError("choose a JPEG fixture within the 300 KiB contract")
    boundary = uuid.uuid4().hex
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"camera_id\"\r\n\r\n"
            f"{args.camera}\r\n--{boundary}\r\n"
            "Content-Disposition: form-data; name=\"file\"; filename=\"fixture.jpg\"\r\n"
            "Content-Type: image/jpeg\r\n\r\n").encode() + image + f"\r\n--{boundary}--\r\n".encode()
    latency = []
    statuses = []
    for attempt in range(args.samples):
        req = Request(f"http://127.0.0.1:{args.port}/api/ai/snapshots", data=body,
                      headers={"X-Service-Key": key, "Content-Type": f"multipart/form-data; boundary={boundary}"})
        started = time.monotonic_ns()
        snapshot_id = None
        try:
            with urlopen(req, timeout=args.timeout) as response:
                status = response.status
                snapshot_id = json.load(response).get("snapshot_id")
        except HTTPError as exc:
            status = exc.code
        except (URLError, TimeoutError):
            status = None
        elapsed = (time.monotonic_ns() - started) / 1_000_000
        statuses.append(status)
        if status == 201 and snapshot_id is not None:
            latency.append(elapsed)
        record("snapshot_upload", attempt=attempt + 1, bytes=len(image), http_status=status,
               snapshot_id=snapshot_id, elapsed_ms=elapsed)
    return {"jpeg_bytes": len(image), "request_body_bytes": len(body),
            "timeouts": statuses.count(None), "http_statuses": statuses,
            "latency": metrics(latency, args.samples)}


def summarize_alerts(observations, expected):
    groups = {}
    for name, start, end, limit in (
        ("detection_first", "first_telemetry_ms", "backend_created_ms", None),
        ("detection_third", "third_telemetry_ms", "backend_created_ms", None),
        ("backend_to_ws", "backend_created_ms", "ws_received_ms", None),
        ("backend_to_dom", "backend_created_ms", "dom_rendered_ms", 5000),
    ):
        values = []
        invalid = 0
        for row in observations:
            a, b = row.get(start), row.get(end)
            if a is None or b is None:
                continue
            if not isinstance(a, (int, float)) or not isinstance(b, (int, float)) or not math.isfinite(b-a) or b < a:
                invalid += 1
                continue
            values.append(b-a)
        groups[name] = {**metrics(values, expected, limit), "invalid_clock_samples": invalid}
        if invalid or len(observations) > expected:
            groups[name]["status"] = "FAIL"
    return groups


def alert_latency(args, record):
    observations = {}
    for line in args.input.read_text(encoding="utf-8-sig").splitlines():
        row = json.loads(line)
        if "kind" in row:
            if row["kind"] != "alert_sample":
                continue
            row = row["sample"]
        key = str(row["id"])
        if key in observations:
            raise ValueError(f"duplicate alert sample: {key}")
        observations[key] = row
    if args.dom_input:
        for line in args.dom_input.read_text(encoding="utf-8-sig").splitlines():
            dom = json.loads(line)
            row = observations.get(str(dom["id"]))
            if row is not None:
                stamp = dom["dom_rendered_ms"]
                if not isinstance(stamp, (int, float)) or not math.isfinite(stamp):
                    raise ValueError("dom_rendered_ms must be a finite epoch timestamp")
                if row.get("dom_rendered_ms") is None or stamp < row["dom_rendered_ms"]:
                    row["dom_rendered_ms"] = stamp
    for row in observations.values():
        record("alert_sample", sample=row)
    return summarize_alerts(list(observations.values()), args.expected)


def live_alert_latency(args, record):
    import paho.mqtt.client as mqtt
    from websocket import create_connection, WebSocketException, WebSocketTimeoutException

    token = os.environ.get("P3_OWNER_JWT")
    if not token:
        raise ValueError("P3_OWNER_JWT is required")
    zone_codes = [fixture.split(":", 1) for fixture in args.device]
    if any(len(pair) != 2 or not pair[0].isdecimal() or not pair[1].startswith("B3P3-ALERT-")
           or any(ch in pair[1] for ch in "/+#") for pair in zone_codes):
        raise ValueError("--device must be ZONE:B3P3-ALERT-*")
    if len({pair[0] for pair in zone_codes}) != len(zone_codes):
        raise ValueError("use a dedicated fresh zone for each alert")
    ws = create_connection(f"ws://127.0.0.1:{args.port}/ws/houses/{args.house}",
                           header={"Authorization": "Bearer " + token},
                           origin="http://localhost:5173", timeout=args.timeout)
    publisher = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
    observations = []
    delivery = queue.Queue()
    stop = threading.Event()
    ws.settimeout(0.5)

    def receive():
        while not stop.is_set():
            try:
                message = ws.recv()
            except WebSocketTimeoutException:
                continue
            except (OSError, WebSocketException) as exc:
                delivery.put(exc)
                return
            if not message:
                return
            delivery.put((time.time_ns() / 1_000_000, message))

    listener = threading.Thread(target=receive, daemon=True)
    listener.start()
    try:
        publisher.connect("127.0.0.1", args.mqtt_port)
        publisher.loop_start()
        for zone, code in zone_codes:
            stamps = []
            published_ids = []
            for index in range(3):
                event_id = uuid.uuid4().hex
                topic = f"swiftletcare/{args.house}/{zone}/{code}/telemetry"
                data = {"event_id": event_id, "device_id": code, "timestamp": utc(),
                        "temperature": 27, "humidity": 35, "co2": 450, "light": 40, "sensor_status": "OK"}
                stamps.append(time.time_ns() / 1_000_000)
                published_ids.append(event_id)
                info = publisher.publish(topic, json.dumps(data), qos=1)
                info.wait_for_publish(timeout=args.timeout)
                if not info.is_published():
                    raise ValueError("publisher acknowledgement timed out")
                if index < 2:
                    time.sleep(args.interval)
            deadline = time.monotonic() + args.timeout
            while (remaining := deadline - time.monotonic()) > 0:
                try:
                    item = delivery.get(timeout=remaining)
                except queue.Empty:
                    break
                if isinstance(item, Exception):
                    raise ValueError(f"WebSocket observer stopped: {item}")
                received, message = item
                event = json.loads(message)
                payload = event.get("data", {})
                if (event.get("type") != "alert.created" or payload.get("type") != "ENV_THRESHOLD"
                        or str(payload.get("zoneId")) != zone):
                    continue
                created = datetime.fromisoformat(payload["createdAt"].replace("Z", "+00:00"))
                sample = {"id": payload["id"], "zone": zone, "event_ids": published_ids,
                          "first_telemetry_ms": stamps[0], "third_telemetry_ms": stamps[2],
                          "backend_created_ms": created.timestamp() * 1000, "ws_received_ms": received}
                observations.append(sample)
                record("alert_sample", sample=sample)
                break
            if not observations or observations[-1]["zone"] != zone:
                record("timeout", zone=zone, event_ids=published_ids)
    finally:
        publisher.disconnect()
        publisher.loop_stop()
        stop.set()
        ws.close()
        listener.join(timeout=2)
    result = summarize_alerts(observations, len(zone_codes))
    # This mode has no browser observer.
    result["backend_to_dom"] = {**metrics([], len(zone_codes), 5000), "status": "NOT TESTED"}
    return result


def has_failure(result):
    if isinstance(result, dict):
        return any(has_failure(value) for value in result.values())
    return result == "FAIL"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="New evidence directory")
    subparsers = parser.add_subparsers(dest="mode", required=True)
    load = subparsers.add_parser("mqtt-load")
    load.add_argument("--port", type=int, default=51885)
    load.add_argument("--house", type=int, required=True)
    load.add_argument("--zone", type=int, required=True)
    load.add_argument("--cycles", type=int, default=3)
    load.add_argument("--timeout", type=float, default=20)
    load.add_argument("--individual-clients", action="store_true", help="Open 50 separate publishers")
    load.add_argument("--test-broker-confirmed", action="store_true", required=True)
    auth = subparsers.add_parser("authorization")
    auth.add_argument("--port", type=int, required=True)
    auth.add_argument("--house", type=int, required=True)
    auth.add_argument("--timeout", type=float, default=5)
    images = subparsers.add_parser("snapshots")
    images.add_argument("--port", type=int, required=True)
    images.add_argument("--camera", type=int, required=True)
    images.add_argument("--jpeg", type=Path, required=True)
    images.add_argument("--samples", type=int, default=10)
    images.add_argument("--timeout", type=float, default=5)
    images.add_argument("--test-backend-confirmed", action="store_true", required=True)
    alert = subparsers.add_parser("alert-latency")
    alert.add_argument("--input", type=Path, required=True)
    alert.add_argument("--expected", type=int, required=True)
    alert.add_argument("--dom-input", type=Path, help="Browser JSONL with id and dom_rendered_ms")
    live = subparsers.add_parser("alert-live")
    live.add_argument("--port", type=int, required=True)
    live.add_argument("--house", type=int, required=True)
    live.add_argument("--mqtt-port", type=int, default=51885)
    live.add_argument("--device", action="append", required=True, help="Fresh test ZONE:B3P3-ALERT-code")
    live.add_argument("--timeout", type=float, default=10)
    live.add_argument("--interval", type=float, default=1)
    live.add_argument("--test-broker-confirmed", action="store_true", required=True)
    args = parser.parse_args()
    if args.mode == "mqtt-load" and (args.port in {1883, 51883, 51884, 51887} or args.cycles < 1 or args.timeout <= 0):
        parser.error("use a dedicated test broker and positive cycles/timeout")
    if args.mode == "alert-latency" and args.expected < 1:
        parser.error("--expected must be positive")
    if args.mode == "snapshots" and (args.samples < 1 or args.timeout <= 0):
        parser.error("use positive samples/timeout")
    if args.mode == "alert-live" and (args.mqtt_port in {1883, 51883, 51884, 51887}
                                      or args.interval <= 0 or args.timeout <= 0):
        parser.error("use a dedicated test broker and positive interval/timeout")
    args.output.mkdir()
    metadata = {"started_utc": utc(), "os": platform.platform(), "cpu": platform.processor(),
                "logical_cpus": os.cpu_count(), "python": platform.python_version(),
                "parameters": {k: str(v) if isinstance(v, Path) else v for k, v in vars(args).items()}}
    with (args.output / "events.jsonl").open("x", encoding="utf-8") as raw:
        def record(kind, **fields):
            raw.write(json.dumps({"kind": kind, "utc": utc(), **fields}) + "\n")
            raw.flush()
        measure = {"mqtt-load": mqtt_load, "authorization": authorization,
                   "snapshots": snapshots, "alert-latency": alert_latency,
                   "alert-live": live_alert_latency}[args.mode]
        try:
            result = measure(args, record)
        except (OSError, ValueError, ImportError) as exc:
            record("error", reason=str(exc))
            result = {"status": "FAIL", "error": str(exc)}
    result = {"environment": metadata, "result": result}
    with (args.output / "result.json").open("x", encoding="utf-8") as output:
        json.dump(result, output, indent=2)
    print(json.dumps(result, indent=2))
    return int(has_failure(result["result"]))


if __name__ == "__main__":
    raise SystemExit(main())
