"""Report exact and near-duplicate candidates in the Build 3 labelled archive."""

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile

import cv2
import numpy as np

from eval_build3 import LABELS, PREFIX


def audit(archive):
    images = {}
    boxes = {}
    with ZipFile(archive) as source:
        for split in ("train", "val", "test"):
            images[split] = []
            boxes[split] = Counter()
            prefix = PREFIX + f"images/{split}/"
            for name in sorted(source.namelist()):
                if not name.startswith(prefix) or name.endswith("/"):
                    continue
                raw = source.read(name)
                pixels = cv2.imdecode(np.frombuffer(raw, dtype=np.uint8), cv2.IMREAD_COLOR)
                if pixels is None:
                    raise ValueError(f"Invalid image: {name}")
                label_path = name.replace("/images/", "/labels/").rsplit(".", 1)[0] + ".txt"
                for row in source.read(label_path).decode().splitlines():
                    if row.strip():
                        boxes[split][LABELS[int(row.split()[0])]] += 1
                small = cv2.resize(cv2.cvtColor(pixels, cv2.COLOR_BGR2GRAY), (9, 8))
                dhash = int.from_bytes(np.packbits(small[:, 1:] > small[:, :-1]).tobytes(), "big")
                pixel_hash = hashlib.sha256(str(pixels.shape).encode() + pixels.tobytes()).hexdigest()
                images[split].append((name, hashlib.sha256(raw).hexdigest(), pixel_hash, dhash))
        license_paths = [n for n in source.namelist() if n.startswith(PREFIX)
                         and "license" in Path(n).name.lower()]
    comparisons = {}
    for split in ("train", "val"):
        exact_bytes, exact_pixels, near = [], [], []
        for test in images["test"]:
            for candidate in images[split]:
                pair = [test[0], candidate[0]]
                if test[1] == candidate[1]:
                    exact_bytes.append(pair)
                if test[2] == candidate[2]:
                    exact_pixels.append(pair)
                distance = (test[3] ^ candidate[3]).bit_count()
                if distance <= 3:
                    near.append({"pair": pair, "distance": distance})
        comparisons[split] = {"byteMatches": exact_bytes, "pixelMatches": exact_pixels,
                              "nearCandidatesDHashAtMost3": near}
    return {"images": {k: len(v) for k, v in images.items()}, "boxes": boxes,
            "combinedSplitLicensePaths": license_paths, "testComparedTo": comparisons}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    result = audit(args.archive)
    with args.output.open("x", encoding="utf-8") as file:
        json.dump(result, file, indent=2)
