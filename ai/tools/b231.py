"""Offline evaluation of independent counting sessions. Run with ai/.venv."""
import argparse, csv, hashlib, json, os, re, subprocess, sys
from pathlib import Path

AI = Path(__file__).resolve().parents[1]
OUT = AI / 'outputs/b231'
REPORT = AI.parent / 'docs/testing/ai/counting-eval-v1.md'

def digest(p):
    with p.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()

def run(a):
    import cv2
    if not re.fullmatch(r'[A-Za-z0-9_-]+', a.clip): sys.exit('Use a unique alphanumeric clip ID.')
    video = Path(a.video).resolve(strict=True)
    cfg = Path(a.config).resolve(strict=True)
    c = json.loads(cfg.read_text(encoding='utf-8-sig'))
    model = (AI / c['model_path']).resolve(strict=True)
    cap = cv2.VideoCapture(str(video))
    w,h = (int(cap.get(k)) for k in (3,4))
    cap.release()
    x,y,xx,yy = c['counting_line']
    if not (0<=x<w and 0<=xx<w and 0<=y<h and 0<=yy<h and (x,y)!=(xx,yy)): sys.exit('Invalid video or counting line outside frame.')
    d = OUT / a.clip
    d.mkdir(parents=True, exist_ok=False)
    snapshot = d/'config.json'
    snapshot.write_text(json.dumps(c,indent=2), encoding='utf8')
    cmd = [sys.executable,'-m','src.main','--config',str(snapshot),'--video',str(video),'--output',str(d/'annotated.mp4'),'--imgsz','320','--no-send']
    with (d/'console.txt').open('w',encoding='utf8') as f:
        rc = subprocess.run(cmd,cwd=AI,env=dict(os.environ,PYTHONIOENCODING='utf-8'),stdout=f,stderr=subprocess.STDOUT).returncode
    log = (d/'console.txt').read_text(encoding='utf8')
    totals = re.findall(r'ENTRY=(\d+) EXIT=(\d+) tracking IDs=(\d+)',log)
    if rc or len(totals)!=1: sys.exit(f'Incomplete run: see {d}/console.txt')
    entry,ex,ids = map(int,totals[0])
    meta=dict(clip=a.clip,video=video.name,sha256=digest(video),model_sha256=digest(model),entry=entry,exit=ex,tracks=ids,line=[x,y,xx,yy],inside=c['inside_direction'])
    (d/'result.json').write_text(json.dumps(meta,indent=2),encoding='utf8')
    print(d, 'ENTRY=',entry,'EXIT=',ex)
def score(a):
    rows=[]; seen=set(); models=set()
    with Path(a.csv).open(encoding='utf-8-sig',newline='') as f:
        for r in csv.DictReader(f):
            clip=r['clip']
            if not re.fullmatch(r'[A-Za-z0-9_-]+',clip): sys.exit('Invalid clip ID')
            m=json.loads((OUT/clip/'result.json').read_text(encoding='utf8'))
            if m['sha256'] in seen: sys.exit('Duplicate video: '+clip)
            seen.add(m['sha256']); models.add(m['model_sha256'])
            try: g,h,s=[int(r[k]) for k in ('gt_entry','gt_exit','id_switches')]
            except ValueError: sys.exit('Missing manual count or ID review: '+clip)
            cond=r['condition'].strip()
            if min(g,h,s)<0 or not cond: sys.exit('Incomplete review: '+clip)
            rows.append((clip,cond,g,m['entry'],h,m['exit'],abs(m['entry']-g),abs(m['exit']-h),s))
    if not rows or len(models)!=1: sys.exit('Use one model and at least one reviewed video.')
    n=len(rows); e=sum(r[6] for r in rows); x=sum(r[7] for r in rows)
    lines=['# B2-31 | Đánh giá lượt vào/ra','',f'Checkpoint SHA-256: {models.pop()}. ByteTrack, imgsz=320, chạy offline theo từng video.','',
    '| Video | Điều kiện | GT vào | AI vào | GT ra | AI ra | Sai số tuyệt đối vào | Sai số tuyệt đối ra | ID-switch |','|---|---|---:|---:|---:|---:|---:|---:|---:|']
    lines += ['| '+' | '.join(map(str,r))+' |' for r in rows]
    lines += ['',f'{n} video. MAE vào = {e/n:.3f}; MAE ra = {x/n:.3f}; MAE gộp hai hướng = {(e+x)/(2*n):.3f} lượt/hướng/video. Tổng ID-switch = {sum(r[8] for r in rows)}.',
    '', 'Các con số biểu thị lượt qua đường. Không suy ra số chim cư trú. Chưa có ngưỡng nghiệm thu sai số được chốt.','',
    'Bằng chứng: ai/outputs/b231/<video-id>/ gồm config.json, result.json, console.txt, annotated.mp4; đối chiếu với nhật ký đếm tay.']
    REPORT.parent.mkdir(parents=True,exist_ok=True)
    if REPORT.exists() and not a.replace: sys.exit('Report already exists: review first; add --replace to update.')
    REPORT.write_text('\n'.join(lines)+'\n',encoding='utf8'); print(REPORT)
p=argparse.ArgumentParser(); sub=p.add_subparsers(required=True)
r=sub.add_parser('run')
for k in ('clip','video','config'): r.add_argument('--'+k,required=True)
r.set_defaults(fn=run)
s=sub.add_parser('score');s.add_argument('--csv',required=True);s.add_argument('--replace',action='store_true');s.set_defaults(fn=score)
if __name__=='__main__':
    args=p.parse_args();args.fn(args)
