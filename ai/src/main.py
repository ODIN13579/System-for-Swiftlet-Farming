import argparse
import gc
import json
import logging
import os
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from dotenv import load_dotenv

ROOT = Path(__file__).resolve().parents[1]
load_dotenv(ROOT / ".env")

# Giới hạn luồng để giảm áp lực RAM.
os.environ["OMP_NUM_THREADS"] = "2"
os.environ["MKL_NUM_THREADS"] = "2"
os.environ["OPENBLAS_NUM_THREADS"] = "2"

import cv2
import torch
from ultralytics import YOLO

from src.counting import EntryExitCounter
from src.api_client import send_count_log


torch.set_num_threads(2)
cv2.setNumThreads(1)



LOG_DIR = ROOT / "logs"
LOG_DIR.mkdir(parents=True, exist_ok=True)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s",
    handlers=[
        logging.StreamHandler(),
        logging.FileHandler(
            LOG_DIR / "ai-service.log",
            encoding="utf-8",
        ),
    ],
)

log = logging.getLogger("swiftletcare-ai")


def utc_now():
    """Lấy thời gian UTC hiện tại."""
    return datetime.now(timezone.utc)


def resolve_period(cfg, video_path):
    """
    Lấy khoảng thời gian thống kê.

    Nếu cấu hình có period_start và period_end,
    sử dụng khoảng thời gian đó.

    Nếu không, sử dụng giờ UTC đã hoàn thành gần nhất.
    """
    if cfg.get("period_start") and cfg.get("period_end"):
        start = datetime.fromisoformat(
            cfg["period_start"].replace("Z", "+00:00")
        )

        end = datetime.fromisoformat(
            cfg["period_end"].replace("Z", "+00:00")
        )

        return (
            start.astimezone(timezone.utc),
            end.astimezone(timezone.utc),
        )

    now = utc_now()

    end = now.replace(
        minute=0,
        second=0,
        microsecond=0,
    )

    start = end - timedelta(hours=1)

    return start, end


def append_jsonl(path, payload):
    """Ghi từng sự kiện JSON thành một dòng."""
    path.parent.mkdir(
        parents=True,
        exist_ok=True,
    )

    with path.open("a", encoding="utf-8") as file:
        file.write(
            json.dumps(
                payload,
                ensure_ascii=False,
            ) + "\n"
        )


def main():
    parser = argparse.ArgumentParser(
        description=(
            "SwiftletCare AI: "
            "YOLO + ByteTrack + Entry/Exit"
        )
    )

    parser.add_argument(
        "--video",
        required=True,
        help="Đường dẫn video đầu vào",
    )

    parser.add_argument(
        "--config",
        default="configs/config.json",
        help="Đường dẫn file cấu hình JSON",
    )

    parser.add_argument(
        "--output",
        default="outputs/annotated.mp4",
        help="Đường dẫn video đầu ra",
    )

    parser.add_argument(
        "--no-send",
        action="store_true",
        help="Chạy AI nhưng không gửi CountLog",
    )

    parser.add_argument(
        "--imgsz",
        type=int,
        default=320,
        help="Kích thước ảnh đầu vào YOLO",
    )

    args = parser.parse_args()

    # Đọc cấu hình.
    config_path = Path(args.config)

    if not config_path.is_absolute():
        config_path = ROOT / config_path

    if not config_path.is_file():
        raise FileNotFoundError(
            f"Không tìm thấy cấu hình: {config_path}"
        )

    cfg = json.loads(
        config_path.read_text(encoding="utf-8")
    )

    # Kiểm tra video.
    video_path = Path(args.video)

    if not video_path.is_absolute():
        video_path = ROOT / video_path

    if not video_path.is_file():
        raise FileNotFoundError(
            f"Không tìm thấy video: {video_path}"
        )

    # Kiểm tra mô hình YOLO.
    model_path = Path(cfg["model_path"])

    if not model_path.is_absolute():
        model_path = ROOT / model_path

    if not model_path.is_file():
        raise FileNotFoundError(
            f"Không tìm thấy mô hình: {model_path}"
        )

    log.info("Đang tải mô hình YOLOv8...")
    model = YOLO(str(model_path))

    cap = cv2.VideoCapture(str(video_path))

    if not cap.isOpened():
        raise RuntimeError(
            f"Không mở được video: {video_path}"
        )

    fps = cap.get(cv2.CAP_PROP_FPS) or 25.0

    width = int(
        cap.get(cv2.CAP_PROP_FRAME_WIDTH)
    )

    height = int(
        cap.get(cv2.CAP_PROP_FRAME_HEIGHT)
    )

    # Tạo đường dẫn video đầu ra.
    output_path = Path(args.output)

    if not output_path.is_absolute():
        output_path = ROOT / output_path

    output_path.parent.mkdir(
        parents=True,
        exist_ok=True,
    )

    writer = cv2.VideoWriter(
        str(output_path),
        cv2.VideoWriter_fourcc(*"mp4v"),
        fps,
        (width, height),
    )

    if not writer.isOpened():
        cap.release()

        raise RuntimeError(
            f"Không tạo được video đầu ra: {output_path}"
        )

    # Khởi tạo bộ đếm.
    counter = EntryExitCounter(
        line=cfg["counting_line"],
        inside_direction=cfg.get(
            "inside_direction",
            1,
        ),
        hysteresis_px=cfg.get(
            "hysteresis_px",
            8,
        ),
        cooldown_frames=cfg.get(
            "cooldown_frames",
            20,
        ),
    )

    frame_index = 0
    unique_track_ids = set()

    event_log = LOG_DIR / "count-events.jsonl"

    log.info("Bắt đầu xử lý video...")
    log.info("Video: %s", video_path)
    log.info("Độ phân giải: %sx%s", width, height)
    log.info("FPS gốc của video: %.2f", fps)
    log.info("Kích thước YOLO: %s", args.imgsz)
    log.info("Thiết bị xử lý: CPU")

    try:
        while True:
            ok, frame = cap.read()

            if not ok:
                break

            frame_index += 1

            # Phát hiện và theo dõi đối tượng.
            with torch.inference_mode():
                result = model.track(
                    frame,
                    persist=True,
                    tracker="bytetrack.yaml",
                    conf=cfg.get(
                        "confidence",
                        0.25,
                    ),
                    classes=cfg.get("class_ids"),
                    imgsz=args.imgsz,
                    device="cpu",
                    verbose=False,
                )[0]

            if (
                result.boxes is not None
                and result.boxes.id is not None
            ):
                boxes = result.boxes.xyxy.cpu().tolist()
                ids = result.boxes.id.cpu().tolist()
                confs = result.boxes.conf.cpu().tolist()

                for box, raw_track_id, confidence in zip(
                    boxes,
                    ids,
                    confs,
                ):
                    x1, y1, x2, y2 = map(int, box)

                    track_id = int(raw_track_id)

                    unique_track_ids.add(track_id)

                    # Tâm bounding box.
                    center_x = (x1 + x2) / 2
                    center_y = (y1 + y2) / 2

                    # Kiểm tra đối tượng có đi qua đường đếm.
                    event_type = counter.update(
                        track_id,
                        center_x,
                        center_y,
                        frame_index=frame_index,
                    )

                    # Vẽ bounding box.
                    cv2.rectangle(
                        frame,
                        (x1, y1),
                        (x2, y2),
                        (0, 200, 0),
                        2,
                    )

                    cv2.putText(
                        frame,
                        f"ID {track_id} {confidence:.2f}",
                        (x1, max(20, y1 - 8)),
                        cv2.FONT_HERSHEY_SIMPLEX,
                        0.55,
                        (0, 200, 0),
                        2,
                    )

                    # Ghi sự kiện nếu có crossing hợp lệ.
                    if event_type:
                        event = {
                            "eventId": str(uuid.uuid4()),
                            "eventType": event_type,
                            "className": cfg.get(
                                "class_name",
                                "bird",
                            ),
                            "confidence": float(confidence),
                            "timestamp": utc_now().isoformat(),
                            "deviceId": int(cfg["device_id"]),
                            "cameraId": cfg.get("camera_id"),
                            "zoneId": cfg.get("zone_id"),
                            "trackId": track_id,
                            "frameIndex": frame_index,
                        }

                        append_jsonl(
                            event_log,
                            event,
                        )

                        log.info(
                            "Sự kiện đếm: %s",
                            json.dumps(
                                event,
                                ensure_ascii=False,
                            ),
                        )

            # Vẽ đường đếm lên video.
            line = cfg["counting_line"]

            p1 = (
                int(line[0]),
                int(line[1]),
            )

            p2 = (
                int(line[2]),
                int(line[3]),
            )

            cv2.line(
                frame,
                p1,
                p2,
                (0, 190, 255),
                3,
            )

            cv2.circle(
                frame,
                p1,
                6,
                (255, 0, 0),
                -1,
            )

            cv2.circle(
                frame,
                p2,
                6,
                (255, 0, 0),
                -1,
            )

            counts = counter.get_counts()

            cv2.putText(
                frame,
                f"ENTRY: {counts['entry']}",
                (20, 40),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.9,
                (0, 200, 0),
                2,
            )

            cv2.putText(
                frame,
                f"EXIT: {counts['exit']}",
                (20, 80),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.9,
                (0, 0, 255),
                2,
            )

            writer.write(frame)

            # Giải phóng bộ nhớ định kỳ.
            del result

            if frame_index % 100 == 0:
                gc.collect()

                log.info(
                    "Đã xử lý %d frame | ENTRY=%d | EXIT=%d",
                    frame_index,
                    counts["entry"],
                    counts["exit"],
                )

    finally:
        cap.release()
        writer.release()
        gc.collect()

    # Tổng hợp số liệu.
    counts = counter.get_counts()

    processed_at = utc_now()

    period_start, period_end = resolve_period(
        cfg,
        video_path,
    )

    payload = {
        "deviceId": int(cfg["device_id"]),
        "periodStart": (
            period_start.isoformat(
                timespec="microseconds"
            ).replace("+00:00", "Z")
        ),
        "periodEnd": (
            period_end.isoformat(
                timespec="microseconds"
            ).replace("+00:00", "Z")
        ),
        "entryCount": int(counts["entry"]),
        "exitCount": int(counts["exit"]),
        "detectCount": int(len(unique_track_ids)),
        "aggregationType": cfg.get(
            "aggregation_type",
            "HOURLY",
        ),
        "processedAt": (
            processed_at.isoformat(
                timespec="microseconds"
            ).replace("+00:00", "Z")
        ),
    }

    # Lưu payload cục bộ trước khi gửi.
    aggregate_path = (
        LOG_DIR / "count-log-payload.json"
    )

    aggregate_path.write_text(
        json.dumps(
            payload,
            indent=2,
            ensure_ascii=False,
        ) + "\n",
        encoding="utf-8",
    )

    log.info(
        "Kết quả: ENTRY=%d EXIT=%d tracking IDs=%d",
        counts["entry"],
        counts["exit"],
        len(unique_track_ids),
    )

    log.info(
        "CountLog: %s",
        json.dumps(
            payload,
            ensure_ascii=False,
        ),
    )

    log.info(
        "Video đầu ra: %s",
        output_path,
    )

    # Chế độ offline: không gửi Backend.
    if args.no_send:
        log.info(
            "Chế độ --no-send: không gửi dữ liệu tới Spring Boot."
        )
        return

    # Lấy khóa từ biến môi trường.
    service_key = os.getenv(
        "AI_SERVICE_KEY",
        "",
    )

    if not service_key:
        raise RuntimeError(
            "Thiếu biến môi trường AI_SERVICE_KEY."
        )

    api_url = cfg["backend_api_url"]

    # Gửi REST có retry.
    try:
        send_count_log(
            payload=payload,
            api_url=api_url,
            service_key=service_key,
            max_attempts=cfg.get(
                "http_max_attempts",
                3,
            ),
            timeout=(
                cfg.get("http_connect_timeout", 3),
                cfg.get("http_timeout", 20),
            ),
            backoff_seconds=cfg.get(
                "http_backoff_seconds",
                1,
            ),
        )

    except Exception as exc:
        log.exception(
            "Gửi CountLog tới Spring Boot thất bại: %s",
            exc,
        )
        raise


if __name__ == "__main__":
    main()