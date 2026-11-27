
import { RefreshCw } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { api, ApiError, type Actuator, type Device } from '../services/api'

const kinds = [
  ['FAN', 'Quạt thông gió'], ['MIST', 'Máy phun sương'],
  ['HEATER', 'Thiết bị sưởi'], ['AMPLIFIER', 'Bộ khuếch đại âm thanh'],
] as const
type Command = Parameters<typeof api.createActuatorCommand>[1]
const pending = (status: string) => status === 'PENDING' || status === 'SENT'

function wait(ms: number, signal: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    signal.throwIfAborted()
    const timer = window.setTimeout(() => {
      signal.removeEventListener('abort', abort)
      resolve()
    }, ms)
    function abort() {
      window.clearTimeout(timer)
      reject(signal.reason)
    }
    signal.addEventListener('abort', abort, { once: true })
  })
}

function commandError(error: unknown) {
  if (error instanceof ApiError) {
    if (error.status === 403) return 'Bạn không có quyền điều khiển ESP32.'
    if (error.status === 503) return 'MQTT hoặc chức năng điều khiển chưa sẵn sàng.'
    if (error.status === 409) {
      if (error.code === 'SAFETY_OVERRIDE') return 'Cơ chế an toàn đang ngăn tắt quạt.'
      if (error.code === 'DEVICE_OFFLINE') return 'ESP32 đang ngoại tuyến hoặc bị vô hiệu hóa.'
      if (error.code === 'COMMAND_IN_PROGRESS') return 'ESP32 đang xử lý một lệnh khác.'
      return 'Trạng thái ESP32 hiện tại không cho phép lệnh này. Hãy làm mới và thử lại.'
    }
  }
  return error instanceof Error ? error.message : 'Không thực hiện được yêu cầu.'
}

export default function FanControl({ device, available }: { device: Device; available: boolean }) {
  const [rows, setRows] = useState<Actuator[]>([])
  const [reading, setReading] = useState(false)
  const [readError, setReadError] = useState('')
  const [command, setCommand] = useState<{ busy: boolean; text: string; error?: boolean }>({ busy: false, text: '' })
  const activeCommand = useRef<AbortController | null>(null)
  const activeRead = useRef<AbortController | null>(null)

  const read = useCallback(async () => {
    if (activeRead.current || activeCommand.current) return
    const controller = new AbortController()
    activeRead.current = controller
    setReading(true)
    try {
      const result = await api.getActuators(device.id, 0, 20, controller.signal)
      if (!controller.signal.aborted) {
        setRows(result.items.filter(row => row.deviceId === device.id))
        setReadError('')
      }
    } catch (error) {
      if (!controller.signal.aborted) setReadError(commandError(error))
    } finally {
      if (activeRead.current === controller) {
        activeRead.current = null
        setReading(false)
      }
    }
  }, [device.id])

  useEffect(() => {
    const lifetime = new AbortController()
    const timer = window.setInterval(() => void read(), 2000)
    queueMicrotask(() => { if (!lifetime.signal.aborted) void read() })
    return () => {
      lifetime.abort()
      window.clearInterval(timer)
      activeRead.current?.abort()
      activeCommand.current?.abort()
      activeRead.current = null
      activeCommand.current = null
    }
  }, [read])

  const online = device.online && device.enabled
  const anchor = rows.find(row => row.kind === 'FAN') ?? rows[0]
  const mode = rows.some(row => row.stateReceivedAt) && anchor && rows.every(row => row.mode === anchor.mode)
    && (anchor.mode === 'AUTO' || anchor.mode === 'MANUAL') ? anchor.mode : undefined
  const canControl = anchor?.canControl === true
  const disabled = command.busy || !available || !online || !canControl || Boolean(readError)

  async function send(row: Actuator, body: Command) {
    if (disabled || activeCommand.current) return
    const controller = new AbortController()
    const { signal } = controller
    activeCommand.current = controller
    activeRead.current?.abort()
    activeRead.current = null
    setReading(false)
    setCommand({ busy: true, text: 'Đang gửi lệnh…' })
    try {
      const result = await api.createActuatorCommand(row.id, body,
        AbortSignal.any([signal, AbortSignal.timeout(10000)]))
      signal.throwIfAborted()
      const id = result.commandId
      let status = result.status
      let reason: string | null | undefined
      setCommand({ busy: true, text: 'Đang chờ ESP32 xác nhận…' })
      const statusDeadline = AbortSignal.timeout(15000)
      while (pending(status) && !statusDeadline.aborted) {
        await wait(450, signal)
        try {
          const response = await api.getCommandStatus(id, AbortSignal.any([signal, AbortSignal.timeout(2000)]))
          status = response.status
          reason = response.failureReason
        } catch (error) {
          signal.throwIfAborted()
          if (error instanceof ApiError && error.status < 500) throw error
        }
      }
      if (status !== 'ACKNOWLEDGED') {
        const failure = pending(status) || reason === 'ACK_TIMEOUT' ? 'Hết thời gian chờ ACK'
          : reason === 'SAFETY_OVERRIDE' ? 'Cơ chế an toàn từ chối lệnh'
          : ({ REJECTED: 'ESP32 từ chối lệnh', FAILED: 'Lệnh thất bại', EXPIRED: 'Lệnh đã hết hạn' }[status] ?? status)
        setCommand({ busy: false, text: reason ? failure + ' (' + reason + ')' : failure, error: true })
        return
      }
      setCommand({ busy: true, text: 'Đã nhận ACK. Đang đối chiếu trạng thái ESP32…' })
      const previousState = new Map(rows.map(item => [item.id, item.stateReceivedAt]))
      const isNewState = (item: Actuator) =>
        item.stateFresh && item.stateReceivedAt && item.stateReceivedAt !== previousState.get(item.id)
      const stateDeadline = AbortSignal.timeout(6000)
      while (!stateDeadline.aborted) {
        const snapshot = await api.getActuators(device.id, 0, 20, AbortSignal.any([signal, AbortSignal.timeout(2000)]))
        const scoped = snapshot.items.filter(item => item.deviceId === device.id)
        signal.throwIfAborted()
        setRows(scoped)
        const updated = scoped.find(item => item.id === row.id)
        const confirmed = body.action === 'SET_MODE'
          ? scoped.length > 0 && scoped.every(item => item.mode === body.targetMode) && scoped.some(isNewState)
          : updated && isNewState(updated) && updated.on === body.targetState
        if (confirmed) {
          setCommand({ busy: false, text: 'Đã nhận ACK và xác nhận trạng thái ESP32.' })
          return
        }
        await wait(450, signal)
      }
      setCommand({ busy: false, text: 'Đã nhận ACK nhưng chưa xác nhận được trạng thái mới.', error: true })
    } catch (error) {
      if (!signal.aborted) setCommand({ busy: false, text: commandError(error), error: true })
    } finally {
      if (activeCommand.current === controller) {
        activeCommand.current = null
        if (!signal.aborted) void read()
      }
    }
  }

  return <section className="panel actuator-controls" aria-label="Điều khiển thiết bị">
    <header className="actuator-controls-row">
      <h2>Điều khiển {device.name}</h2>
      <button type="button" className="devices-refresh" disabled={reading || command.busy} onClick={() => void read()}>
        <RefreshCw size={15} aria-hidden="true" /> Làm mới trạng thái
      </button>
    </header>
    <p>{!available ? 'Chưa xác định kết nối' : online ? 'Online' : 'Offline'} · Chế độ chung: <strong>{mode ?? 'Chưa xác định'}</strong></p>
    {!rows.length && !readError && <p>{reading ? 'Đang tải actuator…' : 'Chưa có actuator đăng ký cho ESP32.'}</p>}
    {anchor && !canControl && <p>Bạn không có quyền điều khiển ESP32.</p>}
    <div className="actuator-controls-row">
      {(['AUTO', 'MANUAL'] as const).map(value =>
        <button key={value} type="button" className="devices-refresh" aria-pressed={mode === value}
          disabled={disabled || !anchor || mode === value}
          onClick={() => anchor && void send(anchor, { action: 'SET_MODE', targetMode: value })}>{value}</button>,
      )}
    </div>
    {kinds.map(([kind, title]) => {
      const row = rows.find(item => item.kind === kind)
      return <div className="actuator-controls-row actuator-output" key={kind}>
        <strong>{title}</strong>
        <span>{row?.stateFresh ? (row.on ? 'ON' : 'OFF') : 'Chưa có trạng thái mới'}</span>
        {[true, false].map(on => <button type="button" className="devices-refresh" key={String(on)}
          aria-label={(on ? 'Bật ' : 'Tắt ') + title}
          disabled={disabled || !row?.stateFresh || mode !== 'MANUAL' || row.on === on ||
            (kind === 'FAN' && !on && row.safetyOverride !== false)}
          onClick={() => row && void send(row, { action: 'SET_STATE', targetState: on })}>
          {on ? 'Bật' : 'Tắt'}
        </button>)}
        {kind === 'FAN' && row?.safetyOverride === true && <span>Cơ chế an toàn đang giữ quạt bật.</span>}
      </div>
    })}
    {readError && <p role="alert">{readError}</p>}
    <p role={command.error ? 'alert' : 'status'}>{command.text}</p>
  </section>
}
