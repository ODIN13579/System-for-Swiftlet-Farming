"""BUILD 3 detection gate, private snapshot evidence and durable delivery."""

import logging
import math
import sqlite3
import time
import uuid
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path
from threading import Event, Lock, Thread

from src.api_client import PredatorClient, RejectedPredatorError, RetryablePredatorError


log = logging.getLogger("swiftletcare-ai.predator")
MAX_SNAPSHOT_BYTES = 300 * 1024


class DetectionGate:
    def __init__(self, confirmation_frames=3, cooldown_seconds=60):
        if confirmation_frames < 1 or not math.isfinite(cooldown_seconds) or cooldown_seconds < 0:
            raise ValueError("Invalid predator confirmation or cooldown")
        self.confirmation_frames = confirmation_frames
        self.cooldown_seconds = cooldown_seconds
        self.last_event = -math.inf
        self.streaks = {}
        self.sent = set()

    def ready(self, track_id, species, frame_index, now):
        if track_id in self.sent:
            return False
        previous_species, previous_frame, streak = self.streaks.get(track_id, (None, -2, 0))
        if previous_frame == frame_index:
            return False
        streak = streak + 1 if previous_species == species and previous_frame == frame_index - 1 else 1
        self.streaks[track_id] = (species, frame_index, streak)
        return streak >= self.confirmation_frames and now - self.last_event >= self.cooldown_seconds

    def recorded(self, track_id, now):
        self.last_event = now
        self.sent.add(track_id)
        self.streaks.pop(track_id, None)

    def prune(self, frame_index):
        self.streaks = {track: value for track, value in self.streaks.items() if value[1] == frame_index}


def encode_snapshot(frame, box, species, confidence, camera_id, event_id, detected_at,
                    max_bytes=MAX_SNAPSHOT_BYTES):
    import cv2

    if not 1 <= max_bytes <= MAX_SNAPSHOT_BYTES:
        raise ValueError("snapshot_max_bytes must be within the Backend limit")
    height, width = frame.shape[:2]
    scale = min(1, 1920 / max(width, height))
    image = cv2.resize(frame, (max(1, int(width * scale)), max(1, int(height * scale))))
    h, w = image.shape[:2]
    x1, y1, x2, y2 = (
        max(0, min(limit - 1, round(v * scale)))
        for v, limit in zip(box, (w, h, w, h))
    )
    cv2.rectangle(image, (x1, y1), (x2, y2), (0, 60, 255), 2)
    font = cv2.FONT_HERSHEY_SIMPLEX
    labels = (f"{species} {confidence:.2f}  camera={camera_id}  {detected_at}",
              f"event={event_id}")
    # Keep a readable evidence header even when the source image is small.
    sizes = [cv2.getTextSize(label, font, 0.45, 1)[0] for label in labels]
    canvas = cv2.copyMakeBorder(image, 38, 0, 0, max(0, max(s[0] for s in sizes) + 12 - w),
                                cv2.BORDER_CONSTANT, value=(0, 0, 0))
    for idx, label in enumerate(labels):
        cv2.putText(canvas, label, (6, 15 + idx * 16), font, 0.45, (255, 255, 255), 1)
    for _ in range(7):
        for quality in (88, 72, 55, 40):
            ok, encoded = cv2.imencode(".jpg", canvas, [cv2.IMWRITE_JPEG_QUALITY, quality])
            if ok and len(encoded) <= max_bytes:
                return encoded.tobytes()
        h, w = canvas.shape[:2]
        if min(h, w) < 80:
            break
        canvas = cv2.resize(canvas, (int(w * 0.8), int(h * 0.8)), interpolation=cv2.INTER_AREA)
    raise ValueError("Cannot encode snapshot within the configured size")


class PredatorOutbox:
    """The JPEG and stable event ID are committed together before HTTP delivery."""

    def __init__(self, path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with closing(self._connect()) as db, db:
            db.execute("""
                create table if not exists pending (
                    event_id text primary key, camera_id integer not null, species text not null,
                    confidence real not null, jpeg blob not null, snapshot_id integer,
                    attempts integer not null default 0, retry_at real not null default 0,
                    rejected text
                )
            """)

    def _connect(self):
        db = sqlite3.connect(self.path)
        db.row_factory = sqlite3.Row
        return db

    def enqueue(self, event_id, camera_id, species, confidence, jpeg):
        with closing(self._connect()) as db, db:
            db.execute(
                "insert into pending(event_id,camera_id,species,confidence,jpeg) values (?,?,?,?,?)",
                (event_id, camera_id, species, confidence, jpeg),
            )

    def deliver_next(self, client, *, now=None):
        due_at = time.time() if now is None else now
        with closing(self._connect()) as db:
            item = db.execute(
                "select * from pending where rejected is null and retry_at<=? order by rowid limit 1",
                (due_at,),
            ).fetchone()
        if item is None:
            return False

        event_id = item["event_id"]
        try:
            snapshot_id = item["snapshot_id"]
            if snapshot_id is None:
                snapshot_id = client.upload_snapshot(item["camera_id"], item["jpeg"])
                with closing(self._connect()) as db, db:
                    db.execute("update pending set snapshot_id=? where event_id=?", (snapshot_id, event_id))
            client.send_event(event_id=event_id, camera_id=item["camera_id"], species=item["species"],
                              confidence=item["confidence"], snapshot_id=snapshot_id)
        except RetryablePredatorError as exc:
            delay = 5 * (2 ** min(item["attempts"], 6))
            with closing(self._connect()) as db, db:
                db.execute("update pending set attempts=attempts+1,retry_at=? where event_id=?",
                           ((time.time() if now is None else now) + delay, event_id))
            log.warning("Predator %s queued for retry in %ss: %s", event_id, delay, exc)
        except RejectedPredatorError as exc:
            with closing(self._connect()) as db, db:
                db.execute("update pending set rejected=? where event_id=?", (str(exc), event_id))
            log.error("Predator %s retained for operator review: %s", event_id, exc)
        else:
            with closing(self._connect()) as db, db:
                db.execute("delete from pending where event_id=?", (event_id,))
            log.info("Predator delivered event_id=%s snapshot_id=%s", event_id, snapshot_id)
        return True


class PredatorMonitor:
    """One camera's detector gate and independent delivery and health workers."""

    def __init__(self, config, root, camera_id, service_key):
        self.camera_id = camera_id
        self.thresholds = config["predator_thresholds"]
        if (not self.thresholds or any(species not in {"snake", "rat", "owl"}
                                      or not isinstance(value, (float, int)) or not 0 < value <= 1
                                      for species, value in self.thresholds.items())):
            raise ValueError("predator_thresholds must contain supported species and positive confidence values")
        self.max_bytes = int(config.get("snapshot_max_bytes", MAX_SNAPSHOT_BYTES))
        if not 1 <= self.max_bytes <= MAX_SNAPSHOT_BYTES:
            raise ValueError("Invalid snapshot_max_bytes")
        self.gate = DetectionGate(int(config.get("predator_confirm_frames", 3)),
                                  float(config.get("predator_cooldown_seconds", 60)))
        self.interval = float(config.get("heartbeat_interval_seconds", 10))
        if not 0 < self.interval < 30:
            raise ValueError("Heartbeat interval must be below the Backend offline deadline")
        self.outbox = PredatorOutbox(root / config.get(
            "predator_outbox", f"logs/predator-outbox-{camera_id}.sqlite3"))
        self.stop = Event()
        self.updated = Event()
        self.delivery_updated = Event()
        self.lock = Lock()
        self.last_frame = None
        self.origin = config.get("backend_base_url", "http://localhost:8080")
        self.key = service_key
        self.threads = []

    def accept(self, frame, frame_index, track_id, label, confidence, box, *, media_time=None):
        threshold = self.thresholds.get(label)
        if threshold is None or confidence < threshold:
            return
        now = time.monotonic() if media_time is None else media_time
        if not self.gate.ready(track_id, label, frame_index, now):
            return
        event_id = str(uuid.uuid4())
        detected_at = datetime.now(timezone.utc).isoformat(timespec="seconds")
        snapshot = encode_snapshot(frame, box, label, confidence, self.camera_id, event_id,
                                   detected_at, self.max_bytes)
        self.outbox.enqueue(event_id, self.camera_id, label, confidence, snapshot)
        self.delivery_updated.set()
        self.gate.recorded(track_id, now)
        log.info("Predator queued event_id=%s species=%s camera=%s", event_id, label, self.camera_id)

    def source_frame(self):
        with self.lock:
            if self.last_frame is None:
                self.updated.set()
            self.last_frame = time.monotonic()

    def _heartbeat(self, client):
        def send(ok):
            try:
                client.heartbeat(self.camera_id, ok)
                log.info("Heartbeat camera=%s source_ok=%s", self.camera_id, ok)
            except (RetryablePredatorError, RejectedPredatorError) as exc:
                log.warning("Heartbeat camera=%s source_ok=%s: %s", self.camera_id, ok, exc)

        while not self.stop.is_set():
            started = time.monotonic()
            with self.lock:
                available = self.last_frame is not None and started - self.last_frame <= self.interval
            send(available)
            self.updated.wait(max(0, started + self.interval - time.monotonic()))
            self.updated.clear()
        send(False)

    def _deliver(self, client):
        while True:
            try:
                delivered = self.outbox.deliver_next(client)
            except sqlite3.Error:
                log.exception("Could not access the predator outbox")
                delivered = False
            if self.stop.is_set():
                return
            if not delivered:
                self.delivery_updated.wait(0.5)
                self.delivery_updated.clear()

    def start(self):
        if not self.key:
            return
        clients = [PredatorClient(self.origin, self.key, timeout=(2, 3))
                   for _ in range(2)]
        self.threads = [Thread(target=target, args=(client,), name=name)
                        for target, client, name in zip(
                            (self._deliver, self._heartbeat), clients,
                            ("predator-outbox", "camera-heartbeat"))]
        self.clients = clients
        for thread in self.threads:
            thread.start()

    def close(self):
        self.stop.set()
        self.updated.set()
        self.delivery_updated.set()
        for thread in self.threads:
            thread.join()
        for client in getattr(self, "clients", []):
            client.close()
