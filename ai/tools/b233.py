"""B2-33 offline benchmark. All paths supplied on the CLI are relative to cwd."""
import argparse, hashlib, json, os, platform, shutil, statistics, subprocess, sys, tempfile, time
from datetime import datetime
from http.server import BaseHTTPRequestHandler, HTTPServer
from importlib.metadata import version
from pathlib import Path
from threading import Thread

AI = Path(__file__).resolve().parents[1]
sys.path.insert(0,str(AI))
from src.api_client import send_count_log
import requests

def sha(p):
    with p.open('rb') as f: return hashlib.file_digest(f,'sha256').hexdigest()

def measure_request(payload, key_bytes):
    sizes={}
    class Sink(BaseHTTPRequestHandler):
        def log_message(self,*args): pass
        def do_POST(self):
            body=self.rfile.read(int(self.headers['Content-Length']))
            sizes.update(body=len(body),same=json.loads(body)==payload)
            sizes['upload']=len(self.raw_requestline)+2+len(body)+sum(
                len(f'{k}: {v}\r\n'.encode('latin-1')) for k,v in self.headers.raw_items())
            self.send_response(201); self.end_headers(); self.wfile.write(b'{"id":1}')
    with HTTPServer(('127.0.0.1',0),Sink) as srv, requests.Session() as session:
        session.trust_env=False
        # Match Host of local demo, irrespective of the temporary listener port.
        session.headers['Host']='localhost:8080'
        t=Thread(target=srv.serve_forever,daemon=True);t.start()
        try:
            send_count_log(payload,f'http://127.0.0.1:{srv.server_port}/api/v1/internal/ai/count-logs',
                           'x'*key_bytes,session=session,max_attempts=1)
        finally: srv.shutdown(); t.join()
    if not sizes.get('same'): raise RuntimeError('Payload mismatch')
    return sizes

def video_info(path):
    import cv2
    c=cv2.VideoCapture(str(path)); n=0
    try:
        fps=c.get(cv2.CAP_PROP_FPS); w=int(c.get(3));h=int(c.get(4))
        while c.grab(): n+=1
    finally: c.release()
    if n<=0 or fps<=0: raise ValueError(f'Video không đọc được: {path}')
    return n,fps,w,h

def main():
    from dotenv import dotenv_values
    p=argparse.ArgumentParser()
    for arg,default in [('config',AI/'configs/b231-sample.json'),('payload',AI/'logs/count-log-payload.json'),('video',AI/'samples/sample.mp4')]:
        p.add_argument('--'+arg,type=Path,default=default)
    p.add_argument('--runs',type=int,default=3);p.add_argument('--imgsz',type=int,default=320)
    p.add_argument('--cpu',default=platform.processor() or platform.machine())
    p.add_argument('--temperature',default='không đo')
    a=p.parse_args()
    if a.runs<1 or a.imgsz<1: p.error('runs và imgsz phải > 0')
    video=a.video.resolve(strict=True); config=json.loads(a.config.read_text(encoding='utf-8-sig'))
    model=(AI/config['model_path']).resolve(strict=True)
    payload=json.loads(a.payload.read_text(encoding='utf-8-sig'))
    if payload['aggregationType']!='HOURLY': p.error('Dùng CountLog HOURLY của B2-32')
    key=os.getenv('AI_SERVICE_KEY') or dotenv_values(AI/'.env').get('AI_SERVICE_KEY')
    if not key: p.error('Không biết độ dài AI_SERVICE_KEY để mô phỏng header; kiểm tra ai/.env.')
    sizes=measure_request(payload,len(key.encode('latin-1')))
    n,fps,w,h=video_info(video); secs=n/fps; vid_kbh=video.stat().st_size/secs*3.6
    out=AI/'outputs/b233'/datetime.now().strftime('%Y%m%d-%H%M%S-%f'); out.mkdir(parents=True)
    rows=[]
    with tempfile.TemporaryDirectory(dir=out) as tmp:
        root=Path(tmp);shutil.copytree(AI/'src',root/'src',ignore=shutil.ignore_patterns('__pycache__'))
        (root/'src/__init__.py').touch()
        config.update(model_path=str(model),period_start=None,period_end=None)
        cfg=root/'config.json';cfg.write_text(json.dumps(config),encoding='utf8')
        for i in range(1,a.runs+1):
            target=root/'result.mp4';log=out/f'run-{i}.log'
            print(f'Lần {i}/{a.runs} ...',flush=True)
            with log.open('w',encoding='utf8') as f:
                start=time.perf_counter()
                code=subprocess.run([sys.executable,'-m','src.main','--config',str(cfg),'--video',str(video),'--output',str(target),'--imgsz',str(a.imgsz),'--no-send'],cwd=root,stdout=f,stderr=subprocess.STDOUT,env=dict(os.environ,PYTHONIOENCODING='utf-8')).returncode
                elapsed=time.perf_counter()-start
            if code or not target.exists(): raise RuntimeError(f'Chạy AI lỗi, xem {log}')
            observed=video_info(target)[0]
            if observed!=n: raise RuntimeError(f'Video thiếu frame: {observed}/{n}; xem {log}')
            rows.append((observed,elapsed,observed/elapsed)); target.unlink()
    mid=statistics.median(row[2] for row in rows)
    report=AI.parent/'docs/testing/ai/fps-bandwidth-results.md'
    lines=['# B2-33 | FPS và lưu lượng AI',f'Phiên: {out.name}. CPU: {a.cpu}. OS: {platform.platform()}. Nhiệt độ: {a.temperature}.',
           f'Python {platform.python_version()}, ultralytics {version("ultralytics")}, torch {version("torch")}.',
           f'Video {video.name}: {w}×{h}, {n} frame, {fps:.3f} FPS nguồn. SHA-256: {sha(video)}.',
           f'Model: {model.name}; SHA-256 {sha(model)}. Config: {a.config.name}; SHA-256 {sha(a.config)}. imgsz={a.imgsz}, ByteTrack, CPU.',
           '', '| Lần | Frame đầu ra | Giây | FPS |','|---:|---:|---:|---:|']
    lines.extend(f'| {i} | {k} | {s:.3f} | {v:.3f} |' for i,(k,s,v) in enumerate(rows,1))
    lines+=['',f'FPS trung vị: {mid:.3f}. Mục tiêu ≥10 FPS: **{"ĐẠT" if mid>=10 else "CHƯA ĐẠT"}**.',
            'Mỗi lần mở tiến trình riêng; thời gian gồm import, nạp model, đọc video, YOLO, tracking, đếm, ghi video/log và thoát. Kiểm số frame ngoài khoảng đo.',
            '',f'CountLog camera {payload["deviceId"]}, kỳ {payload["periodStart"]} – {payload["periodEnd"]}. Giả định một POST thành công/giờ, không retry.',
            f'JSON: {sizes["body"]} byte. HTTP/1.1 upload (request line + headers + JSON): {sizes["upload"]} byte = {sizes["upload"]/1000:.3f} KB/giờ.',
            f'Video: {video.stat().st_size} byte / {secs:.3f} giây → {vid_kbh:,.2f} KB/giờ nếu liên tục cùng bitrate. Video / HTTP = {vid_kbh*1000/sizes["upload"]:,.0f} lần.',
            'POST thực đến máy nhận loopback tạm, dùng client dự án và khóa giả cùng độ dài. Không đo response, TCP/IP, TLS hoặc tổng lưu lượng card mạng. Video là ngoại suy từ MP4, không phải phép đo RTSP. KB=1000 byte.']
    body='\n'.join(lines)+'\n'; (out/'report.md').write_text(body,encoding='utf8')
    report.parent.mkdir(parents=True,exist_ok=True);report.write_text(body,encoding='utf8')
    print(f'FPS trung vị = {mid:.2f}; HTTP upload = {sizes["upload"]/1000:.3f} KB/h\n{report}')
if __name__=='__main__': main()
