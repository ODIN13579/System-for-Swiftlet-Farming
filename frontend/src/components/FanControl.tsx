
import { useEffect, useState } from 'react'
import { Fan, RefreshCw, Power } from 'lucide-react'
import { api, type Actuator } from '../services/api'

type Props = {
  deviceId: number
}

const FINAL_STATUSES = new Set([
  'ACKNOWLEDGED',
  'REJECTED',
  'FAILED',
  'EXPIRED',
])

function statusText(status: string) {
  const labels: Record<string, string> = {
    PENDING: 'Đang chờ thiết bị phản hồi',
    ACKNOWLEDGED: 'Thiết bị đã xác nhận lệnh',
    REJECTED: 'Thiết bị từ chối lệnh',
    FAILED: 'Gửi lệnh thất bại',
    EXPIRED: 'Lệnh đã hết hạn',
  }

  return labels[status] ?? status
}

export default function FanControl({ deviceId }: Props) {
  const [actuator, setActuator] = useState<Actuator | null>(null)
  const [loading, setLoading] = useState(true)
  const [sending, setSending] = useState(false)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [pendingCommandId, setPendingCommandId] = useState<string | null>(null)
  const [commandStatus, setCommandStatus] = useState('')

  async function loadActuator() {
    setError('')

    try {
      const result = await api.getActuators(deviceId)
      const fan = result.items.find((item) => item.kind === 'FAN')

      setActuator(fan ?? null)

      if (!fan) {
        setMessage('Thiết bị chưa có actuator FAN.')
      } else {
        setMessage('')
      }
    } catch (err) {
      setError(
        err instanceof Error
          ? err.message
          : 'Không thể tải trạng thái quạt.',
      )
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    let cancelled = false

    async function initialLoad() {
      try {
        const result = await api.getActuators(deviceId)

        if (cancelled) return

        const fan = result.items.find((item) => item.kind === 'FAN')
        setActuator(fan ?? null)
        setMessage(fan ? '' : 'Thiết bị chưa có actuator FAN.')
        setError('')
      } catch (err) {
        if (!cancelled) {
          setError(
            err instanceof Error
              ? err.message
              : 'Không thể tải trạng thái quạt.',
          )
        }
      } finally {
        if (!cancelled) setLoading(false)
      }
    }

    setLoading(true)
    void initialLoad()

    return () => {
      cancelled = true
    }
  }, [deviceId])

  // Kiểm tra trạng thái lệnh đến khi có kết quả cuối cùng.
  useEffect(() => {
    if (!pendingCommandId) return

    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | undefined
    let attempts = 0

    async function checkStatus() {
      try {
        const commandId = pendingCommandId
        if (!commandId) return

        const result = await api.getCommandStatus(commandId)

        if (cancelled) return

        setCommandStatus(result.status)

        if (FINAL_STATUSES.has(result.status)) {
          setPendingCommandId(null)

          if (result.status === 'ACKNOWLEDGED') {
            setMessage('Thiết bị đã xác nhận lệnh.')
            await loadActuator()
          } else {
            setError(
              result.failureReason
                ? `${statusText(result.status)}: ${result.failureReason}`
                : statusText(result.status),
            )
          }

          return
        }
      } catch {
        if (!cancelled) {
          setError('Chưa đọc được trạng thái lệnh. Hãy thử làm mới.')
        }
      }

      attempts += 1

      if (!cancelled && attempts < 10) {
        timer = setTimeout(() => void checkStatus(), 1000)
      } else if (!cancelled) {
        setPendingCommandId(null)
        setError(
          'Chưa nhận được kết quả cuối cùng. Hãy làm mới trạng thái trước khi gửi lệnh khác.',
        )
      }
    }

    void checkStatus()

    return () => {
      cancelled = true
      if (timer) clearTimeout(timer)
    }
  }, [pendingCommandId])

  async function sendMode(mode: 'AUTO' | 'MANUAL') {
    if (!actuator || sending || pendingCommandId) return

    setSending(true)
    setError('')
    setMessage('')

    try {
      const result = await api.createActuatorCommand(actuator.id, {
        action: 'SET_MODE',
        targetMode: mode,
      })

      setCommandStatus(result.status)
      setPendingCommandId(result.commandId)
      setMessage('Đã gửi lệnh đổi chế độ.')
    } catch (err) {
      setError(
        err instanceof Error
          ? err.message
          : 'Không thể gửi lệnh điều khiển.',
      )
    } finally {
      setSending(false)
    }
  }

  async function sendState(state: 'ON' | 'OFF') {
    if (!actuator || sending || pendingCommandId) return

    if (actuator.mode !== 'MANUAL') {
      setError('Hãy chuyển sang chế độ MANUAL trước.')
      return
    }

    setSending(true)
    setError('')
    setMessage('')

    try {
      const result = await api.createActuatorCommand(actuator.id, {
        action: 'SET_STATE',
        targetState: state,
      })

      setCommandStatus(result.status)
      setPendingCommandId(result.commandId)
      setMessage('Đã gửi lệnh bật/tắt quạt.')
    } catch (err) {
      setError(
        err instanceof Error
          ? err.message
          : 'Không thể gửi lệnh điều khiển.',
      )
    } finally {
      setSending(false)
    }
  }

  return (
    <section className="panel fan-control-panel">
      <div className="fan-control-heading">
        <div>
          <h2><Fan size={19} /> Điều khiển quạt</h2>
          <p>Thiết bị ID: {deviceId}</p>
        </div>

        <button
          type="button"
          onClick={() => void loadActuator()}
          disabled={loading || sending || !!pendingCommandId}
          aria-label="Làm mới trạng thái quạt"
        >
          <RefreshCw size={16} />
          Làm mới
        </button>
      </div>

      {loading ? (
        <p role="status">Đang tải trạng thái quạt...</p>
      ) : !actuator ? (
        <p role="status">
          {error || message || 'Không tìm thấy actuator FAN.'}
        </p>
      ) : (
        <>
          <div className="fan-control-status">
            <span>Chế độ: <strong>{actuator.mode}</strong></span>
            <span>
              Trạng thái: <strong>{actuator.on ? 'ON' : 'OFF'}</strong>
            </span>
          </div>

          <div className="fan-control-actions">
            <button
              type="button"
              onClick={() => void sendMode('AUTO')}
              disabled={sending || !!pendingCommandId || actuator.mode === 'AUTO'}
            >
              AUTO
            </button>

            <button
              type="button"
              onClick={() => void sendMode('MANUAL')}
              disabled={sending || !!pendingCommandId || actuator.mode === 'MANUAL'}
            >
              MANUAL
            </button>
          </div>

          <div className="fan-control-actions">
            <button
              type="button"
              onClick={() => void sendState('ON')}
              disabled={
                sending ||
                !!pendingCommandId ||
                actuator.mode !== 'MANUAL' ||
                actuator.on
              }
            >
              <Power size={16} /> Bật quạt
            </button>

            <button
              type="button"
              onClick={() => void sendState('OFF')}
              disabled={
                sending ||
                !!pendingCommandId ||
                actuator.mode !== 'MANUAL' ||
                !actuator.on
              }
            >
              Tắt quạt
            </button>
          </div>
        </>
      )}

      {sending && <p role="status">Đang gửi lệnh...</p>}

      {pendingCommandId && (
        <p role="status">
          Lệnh: {commandStatus || 'PENDING'} — đang chờ ACK
        </p>
      )}

      {message && <p role="status">{message}</p>}
      {error && <p role="alert">{error}</p>}
    </section>
  )
}
