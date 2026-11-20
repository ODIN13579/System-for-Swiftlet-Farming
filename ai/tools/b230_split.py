"""Split the pinned Swiftlet v6 export without changing its original files."""
from pathlib import Path
import argparse, csv, hashlib, json, math, re, shutil, tempfile
from collections import Counter

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / 'ai/dataset/extra_animals/Swiftlet'
MANIFEST = ROOT / 'docs/design/ai/dataset/b230-manifest.csv'
OUT = ROOT / 'ai/outputs/b230_sessions'
ap = argparse.ArgumentParser()
ap.add_argument('--prepare', action='store_true')
args = ap.parse_args()
rows, seen = [], {}
for old in ('train', 'valid', 'test'):
    base = SRC / old
    images = sorted((base/'images').glob('*.jpg'))
    if not images or {p.stem for p in images} != {p.stem for p in (base/'labels').glob('*.txt')}:
        raise SystemExit(f'Image/label mismatch: {old}')
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
        lines = label.read_text().splitlines()
        for line in lines:
            fields = line.split()
            if len(fields)!=5 or fields[0]!='0': raise SystemExit(f'Invalid label: {label}')
            x,y,w,h = map(float, fields[1:])
            if not (0<=x<=1 and 0<=y<=1 and 0<w<=1 and 0<h<=1): raise SystemExit(f'Invalid bbox: {label}')
        digest = hashlib.sha256(p.read_bytes()).hexdigest()
        if digest in seen and seen[digest]!=split: raise SystemExit(f'Image crosses splits: {n}')
        seen[digest]=split
        rows.append(dict(image=p.relative_to(SRC).as_posix(),group=group,split=split,boxes=len(lines),sha256=digest,label_sha256=hashlib.sha256(label.read_bytes()).hexdigest()))
import io
buf=io.StringIO()
writer=csv.DictWriter(buf,fieldnames=list(rows[0]),lineterminator='\n')
writer.writeheader(); writer.writerows(rows)
if not MANIFEST.exists() or MANIFEST.read_text(encoding='utf-8')!=buf.getvalue(): raise SystemExit('Dataset differs from pinned manifest; review before training.')
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
