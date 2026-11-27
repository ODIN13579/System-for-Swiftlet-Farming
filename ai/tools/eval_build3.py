"""Evaluate predator boxes against the supplied BUILD 3 archive test labels."""

import argparse
import csv
import hashlib
from collections import Counter, defaultdict
from pathlib import Path
from zipfile import ZipFile

import cv2
import numpy as np
from ultralytics import YOLO


PREFIX = "ai/dataset/combined_v1/"
SPECIES = ("snake", "rat", "owl")
LABELS = ("swiftlet", *SPECIES)


def overlap(a, b):
    width = max(0, min(a[2], b[2]) - max(a[0], b[0]))
    height = max(0, min(a[3], b[3]) - max(a[1], b[1]))
    common = width * height
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - common
    return common / union if union > 0 else 0


def ground_truth(text, width, height):
    result = defaultdict(list)
    for row in text.splitlines():
        if not row.strip():
            continue
        category, x, y, w, h = row.split()
        x, y, w, h = map(float, (x, y, w, h))
        if not (0 <= x <= 1 and 0 <= y <= 1 and 0 < w <= 1 and 0 < h <= 1):
            raise ValueError(f"Invalid normalized box: {row}")
        result[LABELS[int(category)]].append((
            max(0, (x - w / 2) * width), max(0, (y - h / 2) * height),
            min(width, (x + w / 2) * width), min(height, (y + h / 2) * height),
        ))
    return result


def evaluate(archive, weights, output):
    model = YOLO(str(weights))
    if set(model.names.values()) != set(LABELS):
        raise ValueError("Checkpoint classes differ from archived YOLO labels")
    totals = {label: Counter() for label in SPECIES}
    with ZipFile(archive) as source:
        splits = {split: sorted(e for e in source.namelist()
                                if e.startswith(PREFIX + f"images/{split}/") and not e.endswith("/"))
                  for split in ("train", "val", "test")}
        seen = {hashlib.sha256(source.read(path)).digest() for split in ("train", "val")
                for path in splits[split]}
        evaluated = omitted = 0
        for path in splits["test"]:
            raw = source.read(path)
            digest = hashlib.sha256(raw).digest()
            if digest in seen:
                omitted += 1
                continue
            seen.add(digest)
            image = cv2.imdecode(np.frombuffer(raw, dtype=np.uint8), cv2.IMREAD_COLOR)
            if image is None:
                raise ValueError(f"Cannot decode {path}")
            labels_path = path.replace("/images/", "/labels/").rsplit(".", 1)[0] + ".txt"
            truth = ground_truth(source.read(labels_path).decode("utf-8"), image.shape[1], image.shape[0])
            result = model.predict(image, imgsz=640, conf=0.30, device="cpu", verbose=False)[0]
            predictions = defaultdict(list)
            for box, score, category in zip(result.boxes.xyxy.tolist(), result.boxes.conf.tolist(),
                                             result.boxes.cls.tolist()):
                predictions[model.names[int(category)]].append((score, box))
            for label, stats in totals.items():
                pending = truth[label][:]
                for _, box in sorted(predictions[label], key=lambda item: item[0], reverse=True):
                    best = max(enumerate(pending), key=lambda item: overlap(box, item[1]), default=None)
                    if best is not None and overlap(box, best[1]) >= 0.50:
                        pending.pop(best[0])
                        stats["TP"] += 1
                    else:
                        stats["FP"] += 1
                stats["FN"] += len(pending)
            evaluated += 1

    with output.open("w", encoding="utf-8", newline="") as file:
        writer = csv.writer(file)
        writer.writerow(["species", "tp", "fp", "fn", "precision", "recall"])
        for label, stats in totals.items():
            tp, fp, fn = (stats[k] for k in ("TP", "FP", "FN"))
            writer.writerow([label, tp, fp, fn, tp / (tp + fp) if tp + fp else "",
                             tp / (tp + fn) if tp + fn else ""])
    print("Image inventory:", {split: len(paths) for split, paths in splits.items()})
    print(f"Evaluated: {evaluated}, omitted byte-identical duplicates: {omitted}")
    print(output.read_text(encoding="utf-8"))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", required=True, type=Path)
    parser.add_argument("--weights", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    evaluate(args.archive, args.weights, args.output)
