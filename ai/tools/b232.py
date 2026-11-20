"""Prepare a CountLog from B2-31 sessions, or replay an existing payload."""
import argparse
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import re
import sys

AI = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(AI))
from src.api_client import send_count_log, make_idempotency_key
import requests

def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))

def utc(value):
    t = datetime.fromisoformat(value.replace('Z', '+00:00'))
    if t.utcoffset() is None:
        raise ValueError('Cần múi giờ UTC hoặc +07:00.')
    return t.astimezone(timezone.utc)

def stamp(t):
    return t.isoformat(timespec='microseconds').replace('+00:00','Z')

def prepare(m, root, now):
    start,end = map(utc,(m['periodStart'],m['periodEnd']))
    kind = m['aggregationType']
    duration = {'HOURLY': timedelta(hours=1), 'DAILY': timedelta(days=1)}.get(kind)
    if end-start != duration or end > now:
        raise ValueError('Kỳ phải đúng độ dài và đã kết thúc.')
    dev = m['deviceId']
    if type(dev) is not int or dev < 1 or not m['clips']:
        raise ValueError('Cần camera ID hợp lệ và ít nhất một clip.')
    spans=[]; seen=set(); counts=[0,0,0]
    for clip in m['clips']:
        name=clip['id']
        if not re.fullmatch(r'[a-zA-Z0-9_-]+',name):
            raise ValueError('Clip ID không hợp lệ.')
        a,b=map(utc,(clip['start'],clip['end']))
        if not start<=a<b<=end or any(a<y and x<b for x,y in spans):
            raise ValueError('Clip ngoài kỳ hoặc chồng thời gian.')
        d=root/name
        r,c=read(d/'result.json'),read(d/'config.json')
        if r['clip']!=name or c['device_id']!=dev or r['sha256'] in seen:
            raise ValueError('Sai camera/clip hoặc video trùng.')
        seen.add(r['sha256']); spans.append((a,b))
        for i,k in enumerate(('entry','exit','tracks')):
            if type(r[k]) is not int or r[k]<0: raise ValueError('Số đếm không hợp lệ.')
            counts[i]+=r[k]
    return dict(deviceId=dev,aggregationType=kind,periodStart=stamp(start),periodEnd=stamp(end),
                entryCount=counts[0],exitCount=counts[1],detectCount=counts[2],processedAt=stamp(now))

class DirectSession(requests.Session):
    def post(self,*args,**kwargs):
        return super().post(*args,**kwargs,allow_redirects=False)

def transmit(p, url, key):
    with DirectSession() as s:
        def check_redirect(r,*args,**kwargs):
            if 300 <= r.status_code < 400: raise ValueError('URL trả chuyển hướng; kiểm tra URL backend.')
        s.hooks['response'].append(check_redirect)
        r=send_count_log(p,url,key,session=s)
    body=r.json()
    if not isinstance(body.get('id'),int): raise ValueError('Phản hồi thiếu id.')
    return r.status_code, body['id']

def main():
    ap=argparse.ArgumentParser(); cmd=ap.add_subparsers(dest='cmd',required=True)
    q=cmd.add_parser('prepare'); q.add_argument('manifest')
    q=cmd.add_parser('send'); q.add_argument('payload'); q.add_argument('--twice',action='store_true')
    q.add_argument('--config',type=Path,default=AI/'configs/config.demo-cam001.json')
    a=ap.parse_args()
    if a.cmd=='prepare':
        p=prepare(read(a.manifest), AI/'outputs/b231', datetime.now(timezone.utc))
        path=AI/'outputs/b232'/(make_idempotency_key(p)+'.json')
        path.parent.mkdir(parents=True,exist_ok=True)
        try:
            with path.open('x',encoding='utf-8') as f: json.dump(p,f,indent=2)
        except FileExistsError:
            old=read(path)
            if dict(old,processedAt=p['processedAt'])!=p:
                raise ValueError('Kỳ đã lưu có số liệu khác; kiểm tra trước khi gửi.')
        print(path)
        return
    from dotenv import dotenv_values
    filekey=dotenv_values(AI/'.env').get('AI_SERVICE_KEY')
    envkey=os.getenv('AI_SERVICE_KEY')
    if envkey and filekey and envkey!=filekey: raise ValueError('AI_SERVICE_KEY của terminal khác ai/.env.')
    p=read(a.payload); key=envkey or filekey
    url=read(a.config)['backend_api_url']
    first=transmit(p,url,key)
    print(f'HTTP {first[0]} id={first[1]}')
    if a.twice:
        second=transmit(p,url,key)
        print(f'HTTP {second[0]} id={second[1]}')
        if first[0] not in (200,201) or second!=(200,first[1]):
            raise ValueError('Gửi lặp chưa trả 200 với cùng ID.')
if __name__=='__main__':
    try: main()
    except (ValueError,KeyError,OSError,requests.RequestException) as exc:
        sys.exit(str(exc))
