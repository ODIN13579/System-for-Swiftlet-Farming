import argparse
import gc
import json
import logging
import math
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

from src.counting import EntryExitCounter, resolve_counting_line
from src.api_client import send_count_log
from src.predator import PredatorMonitor


torch.set_num_threads(2)
cv2.setNumThreads(1)



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
        help="Đường dẫn video đầu ra",
    )

    parser.add_argument(
        "--no-send",
        action="store_true",
        help="Chạy AI và lưu bằng chứng cục bộ, không gửi HTTP",
    )

    parser.add_argument(
        "--imgsz",
        type=int,
        help="Kích thước ảnh đầu vào YOLO, ghi đè cấu hình",
    )
    parser.add_argument("--model", help="Đường dẫn weights, ghi đè model_path")
    parser.add_argument("--max-frames", type=int, help="Giới hạn frame cho smoke test")

    parser.add_argument("--artifact-dir", type=Path, help="New directory for per-run evidence")
    parser.add_argument("--predator-media-clock", action="store_true",
                        help="Use video time for predator cooldown during offline evaluation")
    args = parser.parse_args()
    if args.predator_media_clock and not (args.no_send and args.artifact_dir):
        parser.error("--predator-media-clock requires --no-send and --artifact-dir")
    log_dir = args.artifact_dir.resolve() if args.artifact_dir else ROOT / "logs"
    log_dir.mkdir(parents=True, exist_ok=args.artifact_dir is None)
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
        handlers=[
            logging.StreamHandler(),
            logging.FileHandler(
                log_dir / "ai-service.log",
                encoding="utf-8",
            ),
        ],
    )
    if args.max_frames is not None and args.max_frames < 1:
        parser.error("--max-frames phải lớn hơn 0")

    # Đọc cấu hình.
    config_path = Path(args.config)

    if not config_path.is_absolute():
        config_path = ROOT / config_path

    if not config_path.is_file():
        raise FileNotFoundError(
            f"Không tìm thấy cấu hình: {config_path}"
        )

    cfg = json.loads(
        config_path.read_text(encoding="utf-8-sig")
    )
    if args.no_send and args.artifact_dir:
        cfg["predator_outbox"] = str(log_dir / "predator-outbox.sqlite3")

    # Kiểm tra video.
    video_path = Path(args.video)

    if not video_path.is_absolute():
        video_path = ROOT / video_path

    if not video_path.is_file():
        raise FileNotFoundError(
            f"Không tìm thấy video: {video_path}"
        )

    # Kiểm tra mô hình YOLO.
    model_path = Path(args.model or cfg["model_path"])

    if not model_path.is_absolute():
        model_path = ROOT / model_path

    if not model_path.is_file():
        raise FileNotFoundError(
            f"Không tìm thấy mô hình: {model_path}"
        )

    log.info("Đang tải mô hình YOLOv8...")
    model = YOLO(str(model_path))
    model_version = (cfg.get("model_version") if not args.model else None) or (
        model_path.parent.parent.name if model_path.parent.name == "weights" else model_path.stem
    )
    service_key = os.getenv("AI_SERVICE_KEY", "")
    monitor = None
    class_ids = cfg.get("class_ids")
    confidence_threshold = cfg.get("confidence", 0.25)
    if cfg.get("predator_thresholds"):
        classes = {int(i): str(label).lower() for i, label in model.names.items()}
        needed = {"swiftlet", *cfg["predator_thresholds"]}
        if not needed.issubset(classes.values()):
            raise ValueError(f"Model thiếu các lớp BUILD 3: {sorted(needed - set(classes.values()))}")
        class_ids = [i for i, label in classes.items() if label in needed]
        bird_threshold = float(cfg.get("bird_detection_confidence", 0.25))
        confidence_threshold = min(confidence_threshold, bird_threshold, *cfg["predator_thresholds"].values())
        camera_id = int(cfg["device_id"])
        if camera_id < 1:
            raise ValueError("device_id phải là ID số của camera trong Backend")
        monitor = PredatorMonitor(cfg, ROOT, camera_id, "" if args.no_send else service_key)
        if not service_key and not args.no_send:
            log.warning("Thiếu AI_SERVICE_KEY, bằng chứng predator được giữ lại trong outbox")


    cap = cv2.VideoCapture(str(video_path))

    if not cap.isOpened():
        cap.release()
        if monitor is not None:
            monitor.start()
            monitor.close()
        raise RuntimeError(
            f"Không mở được video: {video_path}"
        )

    source_fps = cap.get(cv2.CAP_PROP_FPS)
    if args.predator_media_clock and (not math.isfinite(source_fps) or source_fps <= 0):
        cap.release()
        if monitor is not None:
            monitor.close()
        raise ValueError("Offline predator evaluation requires video with a valid source FPS")
    fps = source_fps if math.isfinite(source_fps) and source_fps > 0 else 25.0

    width = int(
        cap.get(cv2.CAP_PROP_FRAME_WIDTH)
    )

    height = int(
        cap.get(cv2.CAP_PROP_FRAME_HEIGHT)
    )

    try:
        line = resolve_counting_line(cfg, width, height)
    except ValueError:
        cap.release()
        if monitor is not None:
            monitor.close()
        raise

    # Tạo đường dẫn video đầu ra.
    output_path = Path(args.output) if args.output else (
        log_dir / "annotated.mp4" if args.artifact_dir else Path("outputs/annotated.mp4")
    )

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
        if monitor is not None:
            monitor.close()

        raise RuntimeError(
            f"Không tạo được video đầu ra: {output_path}"
        )

    counter = EntryExitCounter(
        line=line,
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

    event_log = log_dir / "count-events.jsonl"

    log.info("Bắt đầu xử lý video...")
    log.info("Video: %s", video_path)
    log.info("Độ phân giải: %sx%s", width, height)
    log.info("FPS gốc của video: %.2f", fps)
    imgsz = args.imgsz or cfg.get("imgsz", 320)
    log.info("Counting line (pixels): %s", line)
    log.info("Kích thước YOLO: %s", imgsz)
    log.info("Thiết bị xử lý: CPU")

    expected_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    stopped_early = False
    tracking = None
    try:
        if args.artifact_dir:
            tracking = (log_dir / "tracks.jsonl").open("x", encoding="utf-8")
        if monitor is not None:
            monitor.start()
        while True:
            if args.max_frames is not None and frame_index >= args.max_frames:
                stopped_early = True
                break
            ok, frame = cap.read()

            if not ok:
                break

            frame_index += 1
            crossings = []
            if monitor is not None:
                monitor.source_frame()

            # Phát hiện và theo dõi đối tượng.
            with torch.inference_mode():
                result = model.track(
                    frame,
                    persist=True,
                    tracker=cfg.get("tracker_config", "bytetrack.yaml"),
                    conf=confidence_threshold,
                    classes=class_ids,
                    imgsz=imgsz,
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

                class_numbers = result.boxes.cls.cpu().tolist()
                for box, raw_track_id, confidence, class_number in zip(
                    boxes, ids, confs, class_numbers,
                ):
                    x1, y1, x2, y2 = map(int, box)

                    track_id = int(raw_track_id)
                    if tracking:
                        tracking.write(json.dumps({
                            "frameIndex": frame_index, "trackId": track_id,
                            "classId": int(class_number), "confidence": confidence,
                            "xyxy": [x1, y1, x2, y2],
                        }) + "\n")

                    if monitor is not None:
                        label = classes[int(class_number)]
                        if label != "swiftlet":
                            monitor.accept(frame, frame_index, track_id, label, confidence, box,
                                           media_time=(frame_index - 1) / fps if args.predator_media_clock else None)
                            cv2.rectangle(frame, (x1, y1), (x2, y2), (0, 60, 255), 2)
                            cv2.putText(frame, f"{label} {confidence:.2f}", (x1, max(20, y1 - 8)),
                                        cv2.FONT_HERSHEY_SIMPLEX, 0.55, (0, 60, 255), 2)
                            continue
                        if confidence < bird_threshold:
                            continue
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
                        crossings.append(f"{event_type} ID {track_id}")
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
                            "clipOffsetSeconds": round((frame_index - 1) / fps, 6),
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

            if monitor is not None:
                monitor.gate.prune(frame_index)

            # Vẽ đường đếm lên video.

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
                frame, f"{model_version} | Source {fps:.1f} FPS",
                (12, max(18, height - 12)), cv2.FONT_HERSHEY_SIMPLEX,
                0.45, (255, 255, 255), 1,
            )

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

            cv2.putText(frame, f"Frame {frame_index}", (20, 108),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.55, (255, 255, 255), 2)
            for index, crossing in enumerate(crossings):
                cv2.putText(frame, crossing, (20, 132 + 20 * index),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 255), 2)
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
        if tracking:
            tracking.close()
        cap.release()
        writer.release()
        if monitor is not None:
            monitor.close()
        gc.collect()

    complete = not stopped_early and expected_frames > 0 and frame_index >= expected_frames
    if args.artifact_dir:
        (log_dir / "video-run.json").write_text(json.dumps({
            "video": str(video_path), "frames": frame_index, "expectedFrames": expected_frames,
            "fps": fps, "complete": complete, "offline": args.no_send,
            "predatorEnabled": monitor is not None,
            "predatorMediaClock": args.predator_media_clock,
        }, indent=2) + "\n", encoding="utf-8")
    if stopped_early or frame_index == 0 or (expected_frames > 0 and frame_index < expected_frames):
        log.warning("Không gửi CountLog cho clip thiếu frame: %d/%d", frame_index, expected_frames)
        return

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
        log_dir / "count-log-payload.json"
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

    if not service_key and monitor is not None:
        log.warning("CountLog được lưu cục bộ vì thiếu AI_SERVICE_KEY")
        return
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
