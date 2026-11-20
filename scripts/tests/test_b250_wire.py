"""Run against the isolated broker, after the existing backend integration suites."""
import json
import queue
import subprocess
import sys
import time
import unittest
import uuid
from pathlib import Path
from threading import Event
import paho.mqtt.client as mqtt
HERE=Path(__file__).resolve().parents[1]/'mqtt-simulator'
sys.path.insert(0,str(HERE))
from device import stamp

class Wire(unittest.TestCase):
    def setUp(self):
        code='B250-'+uuid.uuid4().hex[:12]
        self.root='swiftletcare/1/1/'+code+'/'
        self.q=queue.Queue()
        sub=Event()
        self.c=mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
        self.c.on_connect=lambda c,*_: c.subscribe(self.root+'#',1)
        self.c.on_subscribe=lambda *_: sub.set()
        self.c.on_message=lambda c,u,m: self.q.put((m.topic,json.loads(m.payload)))
        self.c.connect('127.0.0.1',51884)
        self.c.loop_start()
        self.addCleanup(self.c.loop_stop)
        self.addCleanup(self.c.disconnect)
        self.assertTrue(sub.wait(5),'observer SUBACK timeout')
        self.proc=subprocess.Popen([sys.executable,str(HERE/'simulator.py'),'--device',code])
        def stop():
            self.proc.terminate()
            self.proc.wait(timeout=8)
        self.addCleanup(stop)
        self.wait('telemetry')

    def wait(self,topic):
        end=time.monotonic()+8
        while True:
            t,p=self.q.get(timeout=max(0,end-time.monotonic()))
            if t==self.root+topic: return p

    def exchange(self,t,d):
        self.c.publish(self.root+t,json.dumps(d),qos=1)
        a=self.wait(t+'_ack')
        if t=='command': self.assertEqual(a['command_id'],d['command_id'])
        return a

    def test_command_and_config(self):
        def cmd(action='SET_STATE',kind='FAN',ttl=10,**kw):
            return dict(command_id=str(uuid.uuid4()),action=action,actuator=kind,
                        expiresAt=stamp(time.time()+ttl),**kw)
        def send(d): return self.exchange('command',d)
        self.assertEqual(send(cmd(targetState='ON'))['reason'],'MODE_NOT_MANUAL')
        d=cmd('SET_MODE',targetMode='MANUAL')
        a=send(d)
        self.assertEqual((a['status'],a['mode']),('APPLIED','MANUAL'))
        self.assertEqual(send(d)['originalStatus'],'APPLIED')
        self.assertEqual(send(dict(d,targetMode='AUTO'))['reason'],'COMMAND_ID_CONFLICT')
        for k in ('FAN','MIST','HEATER','AMPLIFIER'):
            for v in ('ON','OFF'):
                a=send(cmd(kind=k,targetState=v))
                self.assertEqual((a['status'],a['actuator'],a['state']),('APPLIED',k,v))
        self.assertEqual(send(cmd(ttl=-1,targetState='ON'))['reason'],'EXPIRED')
        self.assertEqual(send(cmd(ttl=120,targetState='ON'))['reason'],'INVALID_EXPIRY')
        self.assertEqual(send(cmd('SET_MODE',targetMode='AUTO'))['mode'],'AUTO')
        self.assertEqual(send(cmd(targetState='OFF'))['reason'],'MODE_NOT_MANUAL')
        cfg=dict(version=2,humidity_on=70,humidity_off=80,temp_min=25,temp_max=30,telemetry_interval_ms=1200)
        for doc,reason in [(cfg,'OK'),(cfg,'SAME_VERSION'),(dict(cfg,temp_min=26),'VERSION_CONFLICT'),
                           (dict(cfg,version=1),'OLDER_VERSION'),(dict(cfg,version=3,temp_min=31),'RANGE_OR_THRESHOLD_INVALID')]:
            self.assertEqual(self.exchange('cfg',doc)['reason'],reason)

if __name__=='__main__': unittest.main(verbosity=2)
