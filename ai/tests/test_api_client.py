import pytest
import requests

from src.api_client import (
    make_idempotency_key,
    send_count_log,
)


PAYLOAD = {
    "deviceId": 3,
    "aggregationType": "HOURLY",
    "periodStart": "2026-10-24T11:00:00Z",
    "periodEnd": "2026-10-24T12:00:00Z",
    "entryCount": 8,
    "exitCount": 4,
    "detectCount": 213,
    "processedAt": "2026-10-24T12:32:04Z",
}


class FakeResponse:
    """Giả lập phản hồi HTTP để kiểm thử offline."""

    def __init__(self, status_code, text=""):
        self.status_code = status_code
        self.text = text

    def raise_for_status(self):
        if self.status_code >= 400:
            raise requests.HTTPError(
                f"HTTP {self.status_code}"
            )


class FakeSession:
    """Giả lập HTTP client, không gửi request thật."""

    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []

    def post(self, *args, **kwargs):
        self.calls.append((args, kwargs))

        item = self.responses.pop(0)

        if isinstance(item, Exception):
            raise item

        return item


def test_idempotency_key_is_stable():
    key1 = make_idempotency_key(PAYLOAD)
    key2 = make_idempotency_key(dict(PAYLOAD))

    assert key1 == key2


def test_retries_5xx_and_succeeds(monkeypatch):
    monkeypatch.setattr(
        "src.api_client.time.sleep",
        lambda _: None,
    )

    session = FakeSession([
        FakeResponse(503, "busy"),
        FakeResponse(200, "ok"),
    ])

    response = send_count_log(
        PAYLOAD,
        "http://localhost/test",
        "test-secret",
        session=session,
        backoff_seconds=0,
    )

    assert response.status_code == 200
    assert len(session.calls) == 2

    key1 = session.calls[0][1]["headers"]["Idempotency-Key"]
    key2 = session.calls[1][1]["headers"]["Idempotency-Key"]

    assert key1 == key2


def test_does_not_retry_401(monkeypatch):
    monkeypatch.setattr(
        "src.api_client.time.sleep",
        lambda _: None,
    )

    session = FakeSession([
        FakeResponse(401, "unauthorized"),
    ])

    with pytest.raises(requests.HTTPError):
        send_count_log(
            PAYLOAD,
            "http://localhost/test",
            "test-secret",
            session=session,
            backoff_seconds=0,
        )

    assert len(session.calls) == 1


def test_retries_timeout_max_three(monkeypatch):
    monkeypatch.setattr(
        "src.api_client.time.sleep",
        lambda _: None,
    )

    session = FakeSession([
        requests.Timeout("timeout 1"),
        requests.Timeout("timeout 2"),
        requests.Timeout("timeout 3"),
    ])

    with pytest.raises(requests.Timeout):
        send_count_log(
            PAYLOAD,
            "http://localhost/test",
            "test-secret",
            session=session,
            backoff_seconds=0,
        )

    assert len(session.calls) == 3


def test_missing_service_key():
    with pytest.raises(ValueError):
        send_count_log(
            PAYLOAD,
            "http://localhost/test",
            "",
        )