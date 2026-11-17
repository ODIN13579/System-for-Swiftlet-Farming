from pathlib import Path
import shutil

files = [
    Path("src/pages/Houses.tsx"),
    Path("src/pages/Zones.tsx"),
    Path("src/pages/ZoneConfig.tsx"),
]

markers = ("Ã", "áº", "á»", "Ä", "Æ", "Â", "â‚", "Å")

def score(s):
    return sum(s.count(m) for m in markers)

def reverse_mojibake(s):
    raw = bytearray()
    for ch in s:
        n = ord(ch)
        if n <= 255:
            raw.append(n)
        else:
            try:
                encoded = ch.encode("cp1252")
                if len(encoded) != 1:
                    return None
                raw.extend(encoded)
            except UnicodeEncodeError:
                return None
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        return None

def repair_line(line):
    current = line
    for _ in range(3):
        candidate = reverse_mojibake(current)
        if candidate is None or score(candidate) >= score(current):
            break
        current = candidate
    return current

for path in files:
    if not path.exists():
        print(f"KHONG TIM THAY: {path}")
        continue

    backup = path.with_suffix(path.suffix + ".bak")
    shutil.copy2(path, backup)

    original = path.read_text(encoding="utf-8")
    fixed = "\n".join(repair_line(line) for line in original.split("\n"))
    path.write_text(fixed, encoding="utf-8", newline="")

    print(f"DA KIEM TRA: {path}")
    print(f"Ban sao luu: {backup}")

print("\nHoan tat. Hay kiem tra cac file va chay npm run build.")
