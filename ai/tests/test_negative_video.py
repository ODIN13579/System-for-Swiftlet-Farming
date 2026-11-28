import json
from pathlib import Path
import sqlite3
import sys

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from eval_negative_video import evaluate


@pytest.fixture
def run(tmp_path):
    video = tmp_path / "source.mp4"
    video.write_bytes(b"video identity fixture")
    (tmp_path / "video-run.json").write_text(json.dumps({
        "video": str(video), "frames": 3000, "expectedFrames": 3000, "fps": 25,
        "complete": True, "offline": True, "predatorEnabled": True, "predatorMediaClock": True,
    }))
    with sqlite3.connect(tmp_path / "predator-outbox.sqlite3") as db:
        db.execute("create table pending (species text)")
        db.execute("insert into pending values ('rat')")
    return tmp_path


def test_rate_requires_reviewed_duration(run):
    assert evaluate([run], 2)["falseAlertsPerHour"] == 30
    assert evaluate([run], 3)["falseAlertsPerHour"] is None


def test_duplicate_video_cannot_inflate_duration(run):
    with pytest.raises(ValueError, match="Duplicate"):
        evaluate([run, run], 2)


def test_truncated_or_wall_clock_run_cannot_be_evaluated(run):
    manifest = run / "video-run.json"
    for key in ("complete", "predatorMediaClock"):
        original = json.loads(manifest.read_text())
        manifest.write_text(json.dumps({**original, key: False}))
        with pytest.raises(ValueError, match="ineligible"):
            evaluate([run], 2)
        manifest.write_text(json.dumps(original))
