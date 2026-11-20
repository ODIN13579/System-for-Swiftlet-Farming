"""Deterministic Build 2 fixture. Times passed into command() are UTC seconds."""
from collections import OrderedDict
from datetime import datetime, timezone
from uuid import uuid4

def stamp(t):
    return datetime.fromtimestamp(int(t), timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')

class Device:
    def __init__(self, code):
        self.code, self.boot = code, uuid4().hex[:16]
        self.mode, self.manual_until = 'AUTO', 0
        self.states = dict.fromkeys(('FAN','MIST','HEATER','AMPLIFIER'), 'OFF')
        self.cache = OrderedDict()
        self.cfg = dict(version=1, humidity_on=75, humidity_off=80, temp_min=26,
                        temp_max=31, telemetry_interval_ms=5000)

    def tick(self, now):
        if self.mode == 'MANUAL' and now >= self.manual_until:
            self.mode = 'AUTO'
        if self.mode == 'AUTO':  # fixed test readings: 24 C, 40%, 400 ppm
            self.states['FAN'] = 'ON' if 24 > self.cfg['temp_max'] else 'OFF'
            self.states['HEATER'] = 'ON' if 24 < self.cfg['temp_min'] else 'OFF'
            if 40 < self.cfg['humidity_on']: self.states['MIST'] = 'ON'
            if 40 >= self.cfg['humidity_off']: self.states['MIST'] = 'OFF'
            self.states['AMPLIFIER'] = 'OFF'  # no audio schedule in fixture

    def base(self, now):
        return dict(device_id=self.code, timestamp=stamp(now))

    def state(self, now, kind='FAN'):
        val = self.states.get(kind, 'OFF')
        return dict(**self.base(now), mode=self.mode, actuator=kind, state=val,
                    gpio_state='HIGH' if val=='ON' else 'LOW', safetyOverride=False)

    def command(self, d, now):
        self.tick(now)
        cid, a, k = (d.get(f) for f in ('command_id','action','actuator'))
        def reply(s, r, **kw):
            return dict(**self.state(now,k), command_id=cid,status=s,reason=r,**kw)
        if not isinstance(cid,str) or not cid or not isinstance(k,str):
            return reply('REJECTED','INVALID_PAYLOAD') if isinstance(k,str) else None
        if set(d)-{'command_id','action','actuator','expiresAt','targetMode','targetState'}:
            return reply('REJECTED','INVALID_PAYLOAD')
        sig = d.copy()
        if cid in self.cache:
            old,s,r = self.cache[cid]
            if old!=sig: return reply('REJECTED','COMMAND_ID_CONFLICT')
            return reply('DUPLICATE','ALREADY_PROCESSED',originalStatus=s,originalReason=r)
        try:
            e=datetime.strptime(d['expiresAt'],'%Y-%m-%dT%H:%M:%SZ').replace(tzinfo=timezone.utc).timestamp()
        except (KeyError,ValueError,TypeError): e=None
        s,r='REJECTED','INVALID_ACTION'
        if a not in ('SET_MODE','SET_STATE'): pass
        elif k not in self.states: r='INVALID_ACTUATOR'
        elif ('targetMode' in d)!=(a=='SET_MODE') or ('targetState' in d)!=(a=='SET_STATE'): r='INVALID_PAYLOAD'
        elif a=='SET_MODE' and d['targetMode'] not in ('AUTO','MANUAL'): r='INVALID_TARGET_MODE'
        elif a=='SET_STATE' and d['targetState'] not in ('ON','OFF'): r='INVALID_TARGET_STATE'
        elif e is None or e-int(now)>15: r='INVALID_EXPIRY'
        elif e<=int(now): r='EXPIRED'
        elif a=='SET_STATE' and self.mode!='MANUAL': r='MODE_NOT_MANUAL'
        else:
            if a=='SET_MODE':
                self.mode=d['targetMode']
                self.manual_until=int(now)+600
                self.tick(now)
            else: self.states[k]=d['targetState']
            s,r='APPLIED','OK'
        self.cache[cid]=(sig,s,r)
        if len(self.cache)>64: self.cache.popitem(last=False)
        return reply(s,r)

    def config(self,d,now):
        v=d.get('version')
        def ack(s,r):
            return dict(**self.base(now),version=v if type(v)==int else 0,status=s,reason=r)
        if type(v)!=int or not 0<=v<=0xffffffff: return ack('REJECTED','INVALID_VERSION')
        if v<self.cfg['version']: return ack('STALE','OLDER_VERSION')
        for k in ('humidity_on','humidity_off','temp_min','temp_max'):
            if type(d.get(k)) not in (float,int): return ack('REJECTED','INVALID_'+k.upper())
        i=d.get('telemetry_interval_ms')
        if type(i)!=int: return ack('REJECTED','INVALID_TELEMETRY_INTERVAL')
        if not (0<=d['humidity_on']<d['humidity_off']<=100 and -10<=d['temp_min']<d['temp_max']<=60 and 1000<=i<=60000):
            return ack('REJECTED','RANGE_OR_THRESHOLD_INVALID')
        candidate={k:d[k] for k in self.cfg}
        if candidate==self.cfg: return ack('ALREADY_CURRENT','SAME_VERSION')
        if v==self.cfg['version']: return ack('REJECTED','VERSION_CONFLICT')
        self.cfg=candidate
        self.tick(now)
        return ack('APPLIED','OK')
