"""REST client gửi CountLog với retry có giới hạn."""

import hashlib
import json
import logging
import time
from typing import Any

import requests


log = logging.getLogger("swiftletcare-ai.api")


def make_idempotency_key(payload: dict[str, Any]) -> str:
    """
    Tạo khóa ổn định cho cùng một thiết bị và khoảng thời gian.

    Lưu ý:
    Backend phải hỗ trợ Idempotency-Key thì mới có thể
    chống ghi trùng ở phía máy chủ.
    """
    identity = {
        key: payload[key]
        for key in (
            "deviceId",
            "aggregationType",
            "periodStart",
            "periodEnd",
        )
    }

    canonical = json.dumps(
        identity,
        sort_keys=True,
        separators=(",", ":"),
    )

    return hashlib.sha256(
        canonical.encode("utf-8")
    ).hexdigest()


def send_count_log(
    payload: dict[str, Any],
    api_url: str,
    service_key: str,
    *,
    max_attempts: int = 3,
    timeout: tuple[float, float] = (3, 20),
    backoff_seconds: float = 1,
    session: requests.Session | None = None,
) -> requests.Response:
    """
    Gửi CountLog lên Backend.

    - Tối đa 3 lần gửi.
    - Thử lại khi timeout, lỗi kết nối hoặc HTTP 5xx.
    - Không tự thử lại với HTTP 400, 401, 403, 409.
    """

    if not service_key:
        raise ValueError("Thiếu AI_SERVICE_KEY.")

    if not 1 <= max_attempts <= 3:
        raise ValueError(
            "max_attempts phải nằm trong khoảng 1..3."
        )

    client = session or requests.Session()

    headers = {
        "X-Service-Key": service_key,
        "Idempotency-Key": make_idempotency_key(payload),
        "Content-Type": "application/json",
    }

    for attempt in range(1, max_attempts + 1):
        try:
            response = client.post(
                api_url,
                json=payload,
                headers=headers,
                timeout=timeout,
            )

        except (
            requests.Timeout,
            requests.ConnectionError,
        ) as exc:

            if attempt == max_attempts:
                log.error(
                    "CountLog thất bại sau %s lần thử: %s",
                    attempt,
                    exc,
                )
                raise

            delay = backoff_seconds * (
                2 ** (attempt - 1)
            )

            log.warning(
                "Lỗi kết nối lần %s/%s; thử lại sau %.1f giây: %s",
                attempt,
                max_attempts,
                delay,
                exc,
            )

            time.sleep(delay)
            continue

        # HTTP 2xx: gửi thành công theo phản hồi HTTP.
        if 200 <= response.status_code < 300:
            log.info(
                "Gửi CountLog thành công: HTTP %s; response=%s",
                response.status_code,
                response.text,
            )
            return response

        # HTTP 5xx: thử lại nếu vẫn còn lượt.
        if (
            500 <= response.status_code <= 599
            and attempt < max_attempts
        ):
            delay = backoff_seconds * (
                2 ** (attempt - 1)
            )

            log.warning(
                "Backend HTTP %s; thử lại sau %.1f giây",
                response.status_code,
                delay,
            )

            time.sleep(delay)
            continue

        # Các lỗi khác: ghi log và báo lỗi.
        log.error(
            "Backend từ chối CountLog: HTTP %s; body=%s",
            response.status_code,
            response.text,
        )

        response.raise_for_status()

    raise RuntimeError(
        "Không gửi được CountLog sau số lần thử cho phép."
    )

class RetryablePredatorError(Exception):
    """Delivery can be retried with the same evidence and event ID."""


class RejectedPredatorError(Exception):
    """The request was rejected and requires operator review."""


class PredatorClient:
    """Client for the deployed B3-12 and B3-14 routes."""

    def __init__(self, base_url, service_key, *, timeout=(3, 5), session=None):
        from urllib.parse import urlsplit

        parsed = urlsplit(base_url)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc or parsed.path not in ("", "/") or parsed.query or parsed.fragment or parsed.username:
            raise ValueError("backend_base_url must be an HTTP(S) origin")
        if len(service_key) < 24:
            raise ValueError("AI_SERVICE_KEY must have at least 24 characters")
        self.base_url = base_url.rstrip("/")
        self.service_key = service_key
        self.timeout = timeout
        self.session = session if session is not None else requests.Session()

    def _post(self, route, **kwargs):
        try:
            response = self.session.post(
                self.base_url + route,
                headers={"X-Service-Key": self.service_key},
                timeout=self.timeout,
                allow_redirects=False,
                **kwargs,
            )
        except requests.RequestException as exc:
            raise RetryablePredatorError(type(exc).__name__) from exc
        status = response.status_code
        if status in (408, 429) or 500 <= status < 600:
            raise RetryablePredatorError(f"HTTP {status} from {route}")
        if not 200 <= status < 300:
            raise RejectedPredatorError(f"HTTP {status} from {route}")
        return response

    def upload_snapshot(self, camera_id, jpeg):
        response = self._post(
            "/api/ai/snapshots", data={"camera_id": str(camera_id)},
            files={"file": ("predator.jpg", jpeg, "image/jpeg")},
        )
        try:
            body = response.json()
            snapshot_id = body.get("snapshot_id") if isinstance(body, dict) else None
        except ValueError as exc:
            raise RetryablePredatorError("Invalid snapshot response") from exc
        if type(snapshot_id) is not int or snapshot_id < 1:
            raise RetryablePredatorError("Invalid snapshot_id in response")
        return snapshot_id

    def send_event(self, *, event_id, camera_id, species, confidence, snapshot_id):
        self._post("/api/ai/events", json={
            "event_id": event_id, "camera_id": camera_id, "species": species,
            "confidence": confidence, "snapshot_id": snapshot_id,
        })

    def heartbeat(self, camera_id, source_ok):
        self._post("/api/ai/heartbeat", json={"camera_id": camera_id, "source_ok": source_ok})

    def close(self):
        self.session.close()
