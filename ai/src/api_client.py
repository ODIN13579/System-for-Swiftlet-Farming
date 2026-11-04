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