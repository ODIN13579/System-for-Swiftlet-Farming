from contextlib import closing
from sqlite3 import connect
from threading import Event, Thread

import cv2
import numpy as np
import pytest
import requests

from src.api_client import PredatorClient
from src.predator import DetectionGate, PredatorMonitor, PredatorOutbox, encode_snapshot


def test_gate_requires_consecutive_frames_same_species_and_camera_cooldown():
    gate = DetectionGate()
    assert not gate.ready(1, "owl", 1, 0)
    assert not gate.ready(1, "owl", 1, 0)
    assert not gate.ready(1, "owl", 3, 0)
    assert not gate.ready(1, "snake", 4, 0)
    assert not gate.ready(1, "snake", 5, 0)
    assert gate.ready(1, "snake", 6, 0)
    gate.recorded(1, 0)
    for frame in (7, 8, 9):
        assert not gate.ready(2, "rat", frame, 59)
    assert gate.ready(2, "rat", 10, 60)
    assert not gate.ready(1, "snake", 10, 600)


def test_evidence_can_be_decoded_and_respects_byte_limit():
    frame = np.random.default_rng(39).integers(0, 256, (1200, 1600, 3), dtype=np.uint8)
    evidence = encode_snapshot(frame, (0, 0, 250, 250), "owl", 0.91, 12,
                               "event-id", "2026-11-25T00:00:00Z", 60_000)
    assert 0 < len(evidence) <= 60_000
    decoded = cv2.imdecode(np.frombuffer(evidence, dtype=np.uint8), cv2.IMREAD_COLOR)
    assert decoded is not None
    assert decoded.shape[0] * decoded.shape[1] < 4_000_000


class Reply:
    def __init__(self, status, body=None):
        self.status_code = status
        self.body = body

    def json(self):
        return self.body


class Session:
    def __init__(self, *replies):
        self.replies = iter(replies)
        self.calls = []

    def post(self, url, **kwargs):
        self.calls.append((url, kwargs))
        result = next(self.replies)
        if isinstance(result, Exception):
            raise result
        return result


def test_client_matches_backend_routes_and_payloads():
    session = Session(Reply(201, {"snapshot_id": 7}), Reply(201), Reply(204))
    client = PredatorClient("http://example.test:8080", "x" * 24, session=session)
    assert client.upload_snapshot(12, b"jpeg") == 7
    client.send_event(event_id="uuid", camera_id=12, species="owl", confidence=0.8, snapshot_id=7)
    client.heartbeat(12, False)
    assert [url.split(":8080")[1] for url, _ in session.calls] == [
        "/api/ai/snapshots", "/api/ai/events", "/api/ai/heartbeat"]
    assert session.calls[0][1]["data"] == {"camera_id": "12"}
    assert session.calls[0][1]["files"]["file"] == ("predator.jpg", b"jpeg", "image/jpeg")
    assert session.calls[1][1]["json"] == {
        "event_id": "uuid", "camera_id": 12, "species": "owl", "confidence": 0.8, "snapshot_id": 7}
    assert session.calls[2][1]["json"] == {"camera_id": 12, "source_ok": False}
    assert all(call["headers"] == {"X-Service-Key": "x" * 24}
               and call["allow_redirects"] is False for _, call in session.calls)


def test_outbox_retries_after_restart_without_reuploading_or_changing_event_id(tmp_path):
    path = tmp_path / "outbox.sqlite3"
    outbox = PredatorOutbox(path)
    outbox.enqueue("event-one", 12, "snake", 0.9, b"image")
    first_session = Session(requests.ConnectionError("offline"), Reply(201, {"snapshot_id": 77}),
                            requests.Timeout("event acknowledgement lost"))
    client = PredatorClient("http://example.test", "x" * 24, session=first_session)
    assert outbox.deliver_next(client, now=0)
    assert not outbox.deliver_next(client, now=4)
    assert outbox.deliver_next(client, now=5)
    retry_session = Session(Reply(200))
    client = PredatorClient("http://example.test", "x" * 24, session=retry_session)
    restarted = PredatorOutbox(path)
    assert restarted.deliver_next(client, now=15)
    assert len(retry_session.calls) == 1
    assert retry_session.calls[0][1]["json"] == first_session.calls[-1][1]["json"]
    assert not restarted.deliver_next(client, now=99)


def test_permanent_rejection_keeps_faulted_evidence_without_retry(tmp_path):
    path = tmp_path / "outbox.sqlite3"
    outbox = PredatorOutbox(path)
    outbox.enqueue("bad-key", 12, "owl", 0.9, b"image")
    client = PredatorClient("http://example.test", "x" * 24, session=Session(Reply(401)))
    assert outbox.deliver_next(client, now=1)
    assert not outbox.deliver_next(client, now=999)
    with closing(connect(path)) as db:
        assert db.execute("select jpeg,rejected from pending").fetchone() == (
            b"image", "HTTP 401 from /api/ai/snapshots")


def test_heartbeat_reports_a_stale_source_without_waiting_for_inference(tmp_path):
    monitor = PredatorMonitor({"predator_thresholds": {"owl": 0.3},
                               "heartbeat_interval_seconds": 0.03}, tmp_path, 12, "")
    live = Event()
    lost = Event()

    class Client:
        def heartbeat(self, camera_id, source_ok):
            assert camera_id == 12
            if source_ok:
                live.set()
            elif live.is_set():
                lost.set()

    monitor.source_frame()
    worker = Thread(target=monitor._heartbeat, args=(Client(),))
    worker.start()
    try:
        assert live.wait(1)
        assert lost.wait(1)
    finally:
        monitor.stop.set()
        monitor.updated.set()
        worker.join(1)
