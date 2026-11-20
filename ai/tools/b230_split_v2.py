"""Split the pinned Swiftlet v6 export without changing its original files."""
from pathlib import Path
import argparse, csv, hashlib, json, re, shutil, tempfile
from collections import Counter

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / 'ai/dataset/extra_animals/Swiftlet'
MANIFEST = ROOT / 'docs/design/ai/dataset/b230-manifest.csv'
OUT = ROOT / 'ai/outputs/b230_sessions'
ap = argparse.ArgumentParser()
ap.add_argument('--prepare', action='store_true')
args = ap.parse_args()
if not MANIFEST.is_file(): raise SystemExit(f'MISSING_MANIFEST: {MANIFEST}')
with MANIFEST.open(encoding='utf-8-sig', newline='') as handle:
    pinned = list(csv.DictReader(handle))
expected = {r['image']: r for r in pinned}
if len(expected) != len(pinned): raise SystemExit('DUPLICATE_MANIFEST_PATH')
rows, seen, errors, line_endings = [], {}, [], 0
for old in ('train', 'valid', 'test'):
    base = SRC / old
    images = sorted((base/'images').glob('*.jpg'))
    names={p.stem for p in images}
    labels={p.stem for p in (base/'labels').glob('*.txt')}
    errors.extend(f'EXTRA_LABEL {old}/labels/{n}.txt' for n in sorted(labels-names))
    for p in images:
        n = p.name
        if '_mp4' in n.lower():
            group = re.split('_mp4', n, flags=re.I)[0]
            if group == 'DJI_0303': split = 'train'
            elif group.startswith('WhatsApp-Video-2024-01-13-at-'): split = 'val'
            elif group.startswith('WhatsApp-Video-2024-01-15-at-'): split = 'test'
            else: raise SystemExit(f'Unclassified group: {n}')
        elif n.startswith('LBS'): group, split = 'LBS-unknown', 'hold'
        else: raise SystemExit(f'Unclassified file: {n}')
        label = base/'labels'/(p.stem+'.txt')
        if not label.is_file():
            errors.append(f'MISSING_LABEL {label.relative_to(SRC)}')
            continue
        raw = label.read_bytes()
        lines = raw.decode('utf-8').splitlines()
        for line in lines:
            fields = line.split()
            if len(fields)!=5 or fields[0]!='0': raise SystemExit(f'Invalid label: {label}')
            x,y,w,h = map(float, fields[1:])
            if not (0<=x<=1 and 0<=y<=1 and 0<w<=1 and 0<h<=1): raise SystemExit(f'Invalid bbox: {label}')
        digest = hashlib.sha256(p.read_bytes()).hexdigest()
        if digest in seen and seen[digest]!=split: raise SystemExit(f'Image crosses splits: {n}')
        seen[digest]=split
        rows.append(dict(image=p.relative_to(SRC).as_posix(),group=group,split=split,boxes=len(lines),sha256=digest,label_sha256=hashlib.sha256(label.read_bytes()).hexdigest()))
actual = {r['image']:r for r in rows}
for path in sorted(set(expected)-set(actual)):
    if not (SRC/path).is_file(): errors.append('MISSING_IMAGE '+path)
for path in sorted(set(actual)-set(expected)): errors.append('EXTRA_IMAGE '+path)
for path in sorted(set(expected)&set(actual)):
    exp,got=expected[path],actual[path]
    for col in ('sha256','group','split','boxes'):
        if exp[col]!=str(got[col]): errors.append(f'{col.upper()}_MISMATCH {path}')
    if exp['label_sha256']!=got['label_sha256']:
        p=SRC/path
        label=p.parent.parent/'labels'/(p.stem+'.txt')
        lf=label.read_bytes().replace(b'\r\n',b'\n')
        if any(hashlib.sha256(v).hexdigest()==exp['label_sha256'] for v in (lf, lf.replace(b'\n',b'\r\n'))):
            line_endings+=1
        else: errors.append(f'LABEL_CONTENT_MISMATCH {label.relative_to(SRC)}')
if errors:
    print('BLOCKED:',dict(Counter(e.split()[0] for e in errors)))
    for e in errors[:12]: print(e)
    raise SystemExit(1)
print(f'VERIFIED {len(rows)} images; labels differing only by line endings: {line_endings}')
print('Images:',dict(Counter(r['split'] for r in rows)), 'Boxes:',sum(r['boxes'] for r in rows))
if args.prepare:
    if OUT.exists(): raise SystemExit(f'Output exists: {OUT}. Reuse its data.yaml or rename it before preparing again.')
    OUT.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(dir=OUT.parent) as temp:
        stage=Path(temp)/'dataset'
        for r in rows:
            if r['split']=='hold': continue
            p=SRC/r['image']
            l=p.parent.parent/'labels'/(p.stem+'.txt')
            for src,kind in ((p,'images'),(l,'labels')):
                dest=stage/r['split']/kind/(p.parent.parent.name+'_'+src.name)
                dest.parent.mkdir(parents=True,exist_ok=True)
                shutil.copyfile(src,dest)
        (stage/'data.yaml').write_text('path: '+json.dumps(OUT.as_posix())+'\ntrain: train/images\nval: val/images\ntest: test/images\nnames: [swiftlet]\n',encoding='utf-8')
        stage.rename(OUT)
    print(OUT/'data.yaml')
