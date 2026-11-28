"""Compute false alerts/hour from complete, manually reviewed negative video runs."""

import argparse
from contextlib import closing
import hashlib
import json
import math
from pathlib import Path
import sqlite3


def evaluate(runs, minimum_minutes):
    if not math.isfinite(minimum_minutes) or minimum_minutes <= 0:
        raise ValueError("minimum minutes must be positive")
    seen = set()
    clips = []
    for run in runs:
        info = json.loads((run / "video-run.json").read_text(encoding="utf-8"))
        if not all(info.get(key) is True for key in (
            "complete", "offline", "predatorEnabled", "predatorMediaClock",
        )):
            raise ValueError(f"Incomplete or ineligible run: {run}")
        fps, frames = info["fps"], info["frames"]
        if not math.isfinite(fps) or fps <= 0 or frames <= 0 or frames < info["expectedFrames"]:
            raise ValueError(f"Invalid duration: {run}")
        with Path(info["video"]).open("rb") as source:
            digest = hashlib.file_digest(source, "sha256").hexdigest()
        if digest in seen:
            raise ValueError("Duplicate video bytes in the evaluation set")
        seen.add(digest)
        outbox = (run / "predator-outbox.sqlite3").resolve()
        with closing(sqlite3.connect(outbox.as_uri() + "?mode=ro", uri=True)) as db:
            events = dict(db.execute("select species, count(*) from pending group by species"))
        clips.append({"run": str(run), "videoSha256": digest, "seconds": frames / fps, "alerts": events})
    seconds = sum(clip["seconds"] for clip in clips)
    count = sum(sum(clip["alerts"].values()) for clip in clips)
    enough = seconds >= minimum_minutes * 60
    return {"status": "MEASURED" if enough else "NOT TESTED", "seconds": seconds,
            "minimumMinutes": minimum_minutes, "alerts": count,
            "falseAlertsPerHour": count * 3600 / seconds if enough else None, "clips": clips}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("runs", nargs="+", type=Path)
    parser.add_argument("--minimum-minutes", required=True, type=float)
    parser.add_argument("--reviewed-negative", action="store_true", required=True)
    args = parser.parse_args()
    print(json.dumps(evaluate(args.runs, args.minimum_minutes), indent=2))
