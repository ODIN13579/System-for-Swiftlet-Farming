"""Verify the original video and manual sample frames without running YOLO."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1] / 'samples' / 'predator'
for name, expected in json.loads((root/'sha256.json').read_text(encoding='utf-8')).items():
    data = (root/name).read_bytes()
    if hashlib.sha256(data).hexdigest() != expected:
        raise SystemExit('FAIL: '+name)
    print('OK:', name)
print('B2-34: sample integrity OK (manual snapshot, no AI detection)')
