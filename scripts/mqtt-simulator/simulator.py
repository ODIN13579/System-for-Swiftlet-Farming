import argparse
import json
import time
import uuid
from device import Device
import paho.mqtt.client as mqtt

def main():
    p=argparse.ArgumentParser()
    p.add_argument('--host', default='127.0.0.1')
    p.add_argument('--port',type=int,default=51884)
    p.add_argument('--house',type=int,default=1)
    p.add_argument('--zone',type=int,default=1)
    p.add_argument('--device',default='ESP32-B250-TEST')
    opt=p.parse_args()
    if any(c in opt.device for c in '/+#') or not opt.device: p.error('Invalid device code')
    root=f'swiftletcare/{opt.house}/{opt.zone}/{opt.device}/'
    dev=Device(opt.device)
    client=mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
    def publish(topic,payload):
        client.publish(root+topic,json.dumps(payload,separators=(',',':')))
    def state(now):
        publish('state',dict(**dev.state(now),actuators=dev.states,boot_id=dev.boot))
    def connect(c,u,flags,reason,props):
        if reason.is_failure: raise RuntimeError(str(reason))
        c.subscribe([(root+'command',1),(root+'cfg',1)])
    def message(c,u,msg):
        topic=msg.topic[len(root):]
        now=int(time.time())
        try:
            d=json.loads(msg.payload)
            if not isinstance(d,dict): raise ValueError()
        except (ValueError,UnicodeError):
            if topic=='cfg': publish('cfg_ack',dict(**dev.base(now),version=0,status='REJECTED',reason='INVALID_JSON'))
            return
        ack=dev.command(d,now) if topic=='command' else dev.config(d,now)
        if ack:
            publish(topic+'_ack',ack)
            if ack['status']=='APPLIED': state(now)
    client.on_connect=connect
    client.on_message=message
    client.connect(opt.host,opt.port)
    hb=tele=0
    try:
        while True:
            if client.loop(timeout=0.15)!=mqtt.MQTT_ERR_SUCCESS: raise ConnectionError('broker disconnected')
            now=time.time()
            dev.tick(now)
            if now>=hb:
                publish('heartbeat',dev.base(now))
                hb=now+2
            if now>=tele:
                state(now)
                publish('telemetry',dict(**dev.base(now),event_id=str(uuid.uuid4()),temperature=24,
                        humidity=40,co2=400,light=1001,sensor_status='OK'))
                tele=now+dev.cfg['telemetry_interval_ms']/1000
    finally: client.disconnect()

if __name__=='__main__': main()
