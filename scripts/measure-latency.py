"""SwiftletCare B2-51. Use --help. Run from repo root on the dashboard PC."""
import argparse,csv,getpass,json,math,os,platform,queue,statistics,subprocess,sys,threading,time,uuid
from datetime import datetime,timezone
from pathlib import Path
from collections import defaultdict
BASE=Path(__file__).resolve().parents[1]
OUT=BASE/'docs/testing/b251'
REPORT=BASE/'docs/testing/build2-nfr-measurements.md'
def ms(): return time.time_ns()//1000000
def utc(): return datetime.now(timezone.utc).isoformat(timespec='milliseconds').replace('+00:00','Z')
def stats(v):
    if not v:return '—','—','—'
    v=sorted(v);return tuple(round(x,1) for x in (statistics.median(v),v[math.ceil(.95*len(v))-1],v[-1]))
def start(a):
    p=OUT/(datetime.now().strftime('%Y%m%d-%H%M%S')+'-'+a.task+'-'+uuid.uuid4().hex[:5]);p.mkdir(parents=True)
    (p/'meta.json').write_text(json.dumps(dict(time=utc(),os=platform.platform(),cpu=a.cpu or platform.processor(),python=sys.version.split()[0],network=a.network,ci=bool(os.getenv('CI')),task=a.task),ensure_ascii=False,indent=2),encoding='utf8')
    print('Evidence:',p,flush=True);return p
def save(p,obj):
    (p/'result.json').write_text(json.dumps(obj,ensure_ascii=False,indent=2),encoding='utf8')
def row(name,v,total,limit=None,minimum=1):
    med,p95,mx=stats(v)
    status='Not Tested' if not total else ('Fail' if len(v)<total or (limit is not None and max(v)>limit) else ('Not Tested' if len(v)<minimum or limit is None else 'Pass'))
    return [name,f'{len(v)}/{total}',med,p95,mx,limit if limit is not None else 'chưa chốt',status]
def evaluate(records):
    d=defaultdict(dict);beats=[]
    for kind,key,t,status in sorted(records,key=lambda r:r[2]):
        if kind=='hb':beats.append(t)
        else:d[kind].setdefault(key,(t,status))
    def delta(a,b):return [d[b][k][0]-t for k,(t,s) in d[a].items() if k in d[b] and d[b][k][0]>=t]
    def offline(kind):
        vals=[]
        for t,s in d[kind].values():
            last=max((v for v in beats if v<=t),default=None)
            if last is not None: vals.append(t-last)
        return vals
    n=len(d['mqtt']); attempts=len(d['click'])+len(d['clickerr'])
    ack=[d['ack'][k][0]-t for k,(t,_) in d['click'].items() if k in d['ack'] and d['ack'][k][1]=='APPLIED' and d['ack'][k][0]>=t]
    return [row('Telemetry MQTT observer → WS',delta('mqtt','ws'),n,2000,100),
            row('Telemetry MQTT observer → DOM',delta('mqtt','dom'),n,2000,100),
            row('Click → ACK APPLIED',ack,attempts,2000),
            row('Heartbeat cuối → DB offline (poll)',offline('offdb'),1 if d['offdb'] or d['offdom'] else 0,10000),
            row('Heartbeat cuối → DOM Offline',offline('offdom'),1 if d['offdb'] or d['offdom'] else 0,10000)]
def live(a):
    import paho.mqtt.client as mqtt
    from playwright.sync_api import sync_playwright
    p=start(a);events=[]; lock=threading.Lock();ready=threading.Event();stop=threading.Event()
    active=threading.Event()
    f=(p/'events.csv').open('w',newline='',encoding='utf8');w=csv.writer(f);w.writerow(['kind','id','windows_epoch_ms','status'])
    def log(k,i,t=None,s=''):
        if not active.is_set():return
        r=(k,str(i),ms() if t is None else int(t),s)
        with lock:w.writerow(r);f.flush();events.append(r)
    root=f'swiftletcare/{a.house}/{a.zone}/{a.code}/'
    c=mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
    def sub(c,u,mid,codes,props):
        if all(not rc.is_failure for rc in codes):ready.set()
    c.on_subscribe=sub
    c.on_connect=lambda c,u,flags,rc,props:c.subscribe([(root+x,0) for x in ('telemetry','heartbeat','command_ack')]) if not rc.is_failure else None
    def msg(c,u,m):
        if m.retain:return
        t=ms()
        try:
            x=json.loads(m.payload);k=m.topic.split('/')[-1]
            if x.get('device_id')!=a.code:return
            if k=='telemetry':log('mqtt',x['event_id'],t)
            if k=='command_ack':log('ack',x['command_id'],t,x['status'])
            if k=='heartbeat':log('hb',a.code,t)
        except (KeyError,ValueError):pass
    c.on_message=msg;c.connect('127.0.0.1',a.port);c.loop_start()
    def dbpoll():
        prev=None
        while not stop.is_set():
            try:
                raw=subprocess.check_output(['docker','exec',a.container,'psql','-XAt','-U','swiftletcare','-d','swiftletcare','-c',f"select online from swiftletcare.devices where id={a.device} and device_id='{a.code}'"],timeout=4,stderr=subprocess.DEVNULL).decode().strip()
                if prev=='t' and raw=='f':log('offdb',ms())
                prev=raw
            except (OSError,subprocess.SubprocessError):pass
            stop.wait(.5)
    try:
        if not ready.wait(8):raise RuntimeError('No broker SUBACK')
        with sync_playwright() as pw:
            b=pw.chromium.launch(headless=False);page=b.new_page()
            page.expose_function('b251log',lambda x:log(x['kind'],x['id'] if x['kind']!='offdom' else x['t'],x['t'],x.get('status','')))
            js=(BASE/'scripts/b251/browser.js').read_text(encoding='utf8').replace('__DEVICE__',str(a.device)).replace('__CODE__',json.dumps(a.code))
            page.add_init_script(js);page.goto('http://localhost:5173',wait_until='domcontentloaded')
            input('Dang nhap trong Chromium, mo Dashboard, doi ESP32 Online, roi Enter o day: ')
            active.set();threading.Thread(target=dbpoll,daemon=True).start()
            print('Dang do: giu tab hien thi. Thu lenh trong Wokwi; sau >=100 telemetry, stop Wokwi mot lan va cho Offline. Ctrl+C trong terminal de ket thuc.',flush=True)
            try:
                while not page.is_closed():
                    page.wait_for_timeout(1000)
                    if int(time.monotonic())%30==0:print('Telemetry:',sum(k=='mqtt' for k,*_ in events),flush=True)
            except KeyboardInterrupt:pass
    finally:
        stop.set();active.clear();c.disconnect();c.loop_stop()
        with lock: f.close();save(p,evaluate(events))
def ai(a):
    import requests
    from dotenv import dotenv_values
    sys.path.insert(0,str(BASE/'ai'))
    from src.api_client import make_idempotency_key
    p=start(a);payload=json.loads((BASE/a.payload).read_text(encoding='utf-8-sig'));key=dotenv_values(BASE/'ai/.env').get('AI_SERVICE_KEY')
    if not key:raise ValueError('Missing ai/.env AI_SERVICE_KEY')
    url='http://localhost:8080/api/v1/internal/ai/count-logs'
    from urllib.parse import urlparse
    vals=[]; sizes=[];ids=set()
    with (p/'http.csv').open('w',newline='') as f, requests.Session() as s:
        w=csv.writer(f);w.writerow(['run','http','id','ms','request_bytes_est'])
        for j in range(a.samples):
            q=s.prepare_request(requests.Request('POST',url,json=payload,headers={'X-Service-Key':key,'Idempotency-Key':make_idempotency_key(payload)}))
            headers=dict(q.headers);headers.setdefault('Host',urlparse(url).netloc)
            size=len((f'POST {q.path_url} HTTP/1.1\r\n'+''.join(f'{k}: {v}\r\n' for k,v in headers.items())+'\r\n').encode('latin1'))+len(q.body)
            t=time.perf_counter();code='NETWORK';rid=None
            try:
                res=s.send(q,timeout=(3,10),allow_redirects=False);code=res.status_code
                if code in (200,201):rid=res.json().get('id')
            except (requests.RequestException,ValueError):pass
            elapsed=(time.perf_counter()-t)*1000
            w.writerow([j+1,code,rid,round(elapsed,2),size]);f.flush()
            if rid is not None:vals.append(elapsed);sizes.append(size);ids.add(rid)
            else:break
    save(p,[row('AI POST RTT (bao gồm replay)',vals,a.samples),['AI upload KB/h (1 POST/h, không retry)',len(sizes),'—','—',round(statistics.median(sizes)/1000,3) if sizes else '—','KB/h, chưa chốt','Not Tested'],['AI cùng ID khi replay',len(vals),'—','—','—','1 ID','Pass' if len(vals)==a.samples and len(ids)==1 and a.samples>=2 else 'Fail']])
def load(a):
    import paho.mqtt.client as mqtt
    p=start(a);clients=[];seen={};stored={};sent={};lock=threading.Lock();prefix=uuid.uuid4().hex[:8];ready=threading.Event()
    root=f'swiftletcare/{a.house}/{a.zone}/';codes={f'B251-{i:02}' for i in range(1,a.devices+1)}
    def message(c,u,m):
        t=time.perf_counter()
        try:x=json.loads(m.payload);k=x.get('event_id','')
        except ValueError:return
        if not k.startswith(prefix+'-') or m.retain:return
        with lock:
            if m.topic.endswith('/telemetry'):seen.setdefault(k,t)
            elif x.get('status')=='STORED':stored.setdefault(k,t)
    obs=mqtt.Client(mqtt.CallbackAPIVersion.VERSION2);obs.on_message=message
    obs.on_subscribe=lambda c,u,mid,rc,props:ready.set() if all(not v.is_failure for v in rc) else None
    obs.connect('127.0.0.1',51884);obs.subscribe([(root+'+/telemetry',0),(root+'+/telemetry_ack',0)]);obs.loop_start()
    try:
        if not ready.wait(8):raise RuntimeError('No load observer SUBACK')
        for code in sorted(codes):
            c=mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,client_id=prefix+'-'+code)
            ev=threading.Event()
            c.on_connect=lambda c,u,fl,rc,pr,e=ev:e.set() if not rc.is_failure else None
            c.connect('127.0.0.1',51884);c.loop_start();clients.append((code,c))
            if not ev.wait(4):raise RuntimeError('Missing CONNACK: '+code)
        base=time.perf_counter()
        for j in range(a.rounds):
            time.sleep(max(0,base+j*5-time.perf_counter()))
            for code,c in clients:
                eid=f'{prefix}-{code}-{j}';x=dict(device_id=code,event_id=eid,timestamp=utc(),temperature=24,humidity=40,co2=400,light=1001,sensor_status='OK')
                sent[eid]=time.perf_counter();c.publish(root+code+'/telemetry',json.dumps(x))
        time.sleep(3)
    finally:
        for _,c in clients:c.disconnect();c.loop_stop()
        obs.disconnect();obs.loop_stop()
    rows=[]
    with (p/'load.csv').open('w',newline='') as f:
        w=csv.writer(f);w.writerow(['event_id','broker_observed_ms','stored_ack_ms'])
        for k,t in sent.items():w.writerow([k,*[round((v[k]-t)*1000,2) if k in v else '' for v in (seen,stored)]])
    for title,values in [('MQTT broker',seen),('Backend STORED',stored)]:
        v=[(values[k]-t)*1000 for k,t in sent.items() if k in values]
        r=row(f'{a.devices} thiết bị: publish → {title}',v,len(sent))
        r[-1]=('Not Tested' if title=='Backend STORED' and not a.with_backend else ('Pass' if len(v)==a.devices*a.rounds else 'Fail'))
        r[-2]='nhận đủ, không SLA tốc độ';rows.append(r)
    save(p,rows)
def report(a):
    files=sorted(OUT.glob('*/result.json')); lines=['# B2-51 · Kết quả đo NFR','', 'Thời gian latency: ms. P95 nearest-rank. Max dùng để xét giới hạn từng mẫu. Not Tested = thiếu bằng chứng hoặc chưa chốt ngưỡng. KB/h là ước lượng HTTP upload.','']
    for path in files:
        m=json.loads((path.parent/'meta.json').read_text(encoding='utf8'))
        lines+=['## '+path.parent.name,'',f"{m['time']} · {m['os']} · {m['cpu']} · Python {m['python']} · {m['network']} · CI: {m['ci']}",'',f'[Dữ liệu gốc](b251/{path.parent.name}/)','', '| Phép đo | Nhận/tổng | Median | P95 | Max hoặc KB/h | Mục tiêu | Kết quả |','|---|---:|---:|---:|---:|---|---|']
        lines+=['| '+' | '.join(map(str,r))+' |' for r in json.loads(path.read_text(encoding='utf8'))]+['']
    if not files:
        lines += ['| Phép đo | Mẫu | Trạng thái |','|---|---:|---|'] + [f'| {label} | 0 | Not Tested |' for label in ('Telemetry → WS / DOM','Click → ACK APPLIED','Offline DB / DOM','AI REST latency / KB/h','Tải 10 thiết bị','Tải 50 thiết bị')] + ['']
    tasks={json.loads((p.parent/'meta.json').read_text(encoding='utf8'))['task'] for p in files}
    lines += ['Mục chưa có lượt đo: '+(', '.join(sorted({'live','ai','load'}-tasks)) or 'xem từng dòng và đối chiếu đủ 10/50 thiết bị')+'.','Phương pháp, sai số và điều kiện nghiệm thu: xem B251-runbook.md.']
    REPORT.parent.mkdir(parents=True,exist_ok=True);REPORT.write_text('\n'.join(lines)+'\n',encoding='utf8');print(REPORT)
if __name__=='__main__':
    parser=argparse.ArgumentParser();sub=parser.add_subparsers(dest='task',required=True)
    for name in ('live','ai','load','report'):
        s=sub.add_parser(name);s.add_argument('--cpu');s.add_argument('--network',default='Windows localhost; Docker/Wokwi (ghi rõ nếu khác)')
        if name=='live':
            s.add_argument('--device',type=int,default=2);s.add_argument('--port',type=int,default=1883);s.add_argument('--house',type=int,default=1);s.add_argument('--zone',type=int,default=1);s.add_argument('--code',default='ESP32-HOUSE01-ZONE01');s.add_argument('--container',default='system-for-swiftlet-farming-postgres-1')
        if name=='ai':
            s.add_argument('--samples',type=int,default=10);s.add_argument('--payload',default='ai/logs/count-log-payload.json')
        if name=='load':s.add_argument('--house',type=int,default=1);s.add_argument('--zone',type=int,default=1);s.add_argument('--devices',choices=[10,50],type=int,required=True);s.add_argument('--rounds',type=int,default=10);s.add_argument('--with-backend',action='store_true')
    a=parser.parse_args()
    import re
    if not re.fullmatch('[A-Za-z0-9_-]+',getattr(a,'code','ok')) or min(getattr(a,'samples',1),getattr(a,'rounds',1))<1:parser.error('invalid arguments')
    {'live':live,'ai':ai,'load':load,'report':report}[a.task](a)
