"""Export raw, per-frame YOLO detections for offline diagnosis."""

import argparse
from collections import Counter
import json
from pathlib import Path

import cv2
import torch
from ultralytics import YOLO


def audit(video, weights, output):
    torch.set_num_threads(2)
    model = YOLO(str(weights))
    capture = cv2.VideoCapture(str(video))
    frame_index = 0
    counts = Counter()
    at_threshold = Counter()
    try:
        if not capture.isOpened():
            raise ValueError(f"Could not open {video}")
        expected = int(capture.get(cv2.CAP_PROP_FRAME_COUNT))
        with output.open("x", encoding="utf-8") as stream:
            while True:
                batch = []
                for _ in range(8):
                    ok, frame = capture.read()
                    if not ok:
                        break
                    batch.append(frame)
                if not batch:
                    break
                for result in model.predict(batch, imgsz=640, conf=0.10, device="cpu", verbose=False):
                    frame_index += 1
                    boxes = []
                    for category, score, box in zip(result.boxes.cls.tolist(), result.boxes.conf.tolist(),
                                                    result.boxes.xyxy.tolist()):
                        label = model.names[int(category)]
                        counts[label] += 1
                        if score >= 0.30:
                            at_threshold[label] += 1
                        boxes.append({"class": label, "conf": score, "xyxy": box})
                    stream.write(json.dumps({"frame": frame_index, "boxes": boxes}) + "\n")
        if frame_index == 0 or (expected > 0 and frame_index != expected):
            raise ValueError(f"Incomplete decode: {frame_index}/{expected}")
    finally:
        capture.release()
    return {"frames": frame_index, "classes": model.names, "boxesAt10": counts, "boxesAt30": at_threshold}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--video", type=Path, required=True)
    parser.add_argument("--weights", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(audit(args.video, args.weights, args.output), indent=2))
