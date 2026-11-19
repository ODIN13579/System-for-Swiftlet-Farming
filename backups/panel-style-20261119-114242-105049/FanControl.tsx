import { useEffect, useRef, useState } from 'react'
import { actuatorPanelRequest as req } from '../services/api'

const devices = [
  ['FAN', 'Quạt thông gió'], ['MIST', 'Máy phun sương'],
  ['HEATER', 'Thiết bị sưởi'], ['AMPLIFIER', 'Bộ khuếch đại âm thanh'],
] as const
type Row = { id: number; deviceId: number; kind: string; mode: string; on: boolean }
type Result = { commandId: string; status: string; failureReason?: string }
type Body = {action:'SET_MODE';targetMode:'AUTO'|'MANUAL'} | {action:'SET_STATE';targetState:boolean}
type Props = { deviceId?: number | null }
const pause = (ms: number) => new Promise(resolve => setTimeout(resolve, ms))
const msg = (e: unknown) => e instanceof Error ? e.message : String(e)

export default function FanControl({deviceId}: Props) {
  return deviceId ? <Panel key={deviceId} id={deviceId}/> : <p>Chưa chọn ESP32.</p>
}
function Panel({id}: {id:number}) {
  const [rows, setRows] = useState<Row[]>([])
  const [notice, setNotice] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const lock = useRef(false)
  const alive = useRef(false)
  const version = useRef(0)
  async function read() {
    const v = ++version.current
    try {
      const data = await req<{items:Row[]}>(`/api/v1/devices/${id}/actuators?page=0&size=20`)
      if (alive.current && v===version.current) {
        setRows(data.items.filter(x => x.deviceId===id)); setError('')
      }
    } catch(e) { if (alive.current && v===version.current) setError(msg(e)) }
  }
  useEffect(() => {
    alive.current=true
    void read()
    const timer=setInterval(() => { if (!lock.current) void read() }, 3000)
    return () => {alive.current=false; ++version.current; clearInterval(timer)}
  }, [id])
  async function send(row:Row, body:Body) {
    if(lock.current) return
    lock.current=true; setBusy(true)
    const label = body.action==='SET_MODE' ? 'Chế độ '+body.targetMode : row.kind+' '+(body.targetState?'ON':'OFF')
    setNotice(label+': đang gửi…')
    try {
      let r=await req<Result>(`/api/v1/actuators/${row.id}/commands`, {method:'POST',body:JSON.stringify(body)})
      const deadline=Date.now()+22000
      while(alive.current && ['PENDING','SENT'].includes(r.status) && Date.now()<deadline) {
        await pause(600)
        if(!alive.current) return
        r=await req<Result>(`/api/v1/commands/${encodeURIComponent(r.commandId)}`)
      }
      if(alive.current) setNotice(label+': '+(r.status==='ACKNOWLEDGED'?'thiết bị đã xác nhận':
        ['PENDING','SENT'].includes(r.status)?'chưa có xác nhận':r.status+(r.failureReason?' — '+r.failureReason:'')))
    } catch(e) { if(alive.current) setNotice(label+': '+msg(e)) }
    finally { if(alive.current) { await read(); lock.current=false; setBusy(false) } }
  }
  const anchor=rows.find(x=>x.kind==='FAN') ?? rows[0]
  const mode=anchor && rows.every(x=>x.mode===anchor.mode) ? anchor.mode : '—'
  const disabled=busy || !!error
  return <section aria-label="Điều khiển thiết bị" style={{padding:16,border:'1px solid #62706c',borderRadius:12}}>
    <h3>Điều khiển thiết bị</h3>
    <p>ESP32 #{id} · Chế độ chung: <strong>{mode}</strong></p>
    {(['AUTO','MANUAL'] as const).map(m=><button key={m} disabled={disabled || !anchor || mode===m}
      onClick={()=>anchor && void send(anchor,{action:'SET_MODE',targetMode:m})}>{m}</button>)}
    {devices.map(([kind,title])=>{
      const row=rows.find(x=>x.kind===kind)
      return <div key={kind} style={{display:'flex',alignItems:'center',gap:8,flexWrap:'wrap',padding:'10px 0',borderBottom:'1px solid #62706c'}}>
        <strong style={{flex:'1 1 140px'}}>{title}</strong>
        <span>{row?(row.on?'ON':'OFF'):'Chưa có dữ liệu'}</span>
        {([true,false] as const).map(on=><button key={String(on)} aria-label={(on?'Bật ':'Tắt ')+title}
          disabled={disabled || !row || mode!=='MANUAL' || row.on===on}
          onClick={()=>row && void send(row,{action:'SET_STATE',targetState:on})}>{on?'Bật':'Tắt'}</button>)}
      </div>
    })}
    <p role="status">{error || notice || (mode==='AUTO'?'Chuyển sang MANUAL để điều khiển từng thiết bị.':'')}</p>
  </section>
}
