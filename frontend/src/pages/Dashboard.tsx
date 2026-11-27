import { DeviceSelectors, useDeviceScope } from '../components/DeviceScope'
import AiCounts from '../components/AiCounts'
import DashboardAlerts from '../components/DashboardAlerts'
import { useEffect, useRef, useState } from 'react'
import {
  Thermometer,
  ChartNoAxesCombined,
  Bird,
  Wifi,
  RefreshCw,
} from 'lucide-react'
import {
  api,
  ApiError,
  type Telemetry,
} from '../services/api'

import { connectDeviceRealtime } from '../services/websocket'

type MetricValue = {
  label: string
  value: number | null
  unit: string
  icon: typeof Thermometer
  tone: string
}

type RealtimeStatus = 'connected' | 'disconnected' | 'error'

function formatValue(value: number | null, digits = 1) {
  return value === null || !Number.isFinite(value)
    ? '—'
    : value.toFixed(digits)
}

function formatDateTime(value?: string | null) {
  if (!value) return 'Chưa ghi nhận'

  const date = new Date(value)

  if (Number.isNaN(date.getTime())) {
    return 'Thời gian không hợp lệ'
  }

  return new Intl.DateTimeFormat('vi-VN', {
    timeZone: 'Asia/Ho_Chi_Minh',
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(date)
}

export default function Dashboard({ onNavigateToAlerts }: { onNavigateToAlerts: () => void }) {
  const scope = useDeviceScope()
  return <>
    <DeviceSelectors />
    {scope.zoneId === undefined
      ? <p role={scope.error ? 'alert' : 'status'}>{scope.error || (scope.loading ? 'Đang tải...' : 'Chưa chọn khu vực.')}</p>
      : <DashboardZone key={scope.zoneId + ':' + scope.device?.id} zoneId={scope.zoneId} onNavigateToAlerts={onNavigateToAlerts} />}
  </>
}

function DashboardZone({ zoneId, onNavigateToAlerts }: { zoneId: number; onNavigateToAlerts: () => void }) {
  const { device, devices, loading, error, reload } = useDeviceScope()
  const [telemetry, setTelemetry] = useState<Telemetry | null>(null)
  const latestTime = useRef(-Infinity)
  const [telemetryMessage, setTelemetryMessage] = useState('')
  const [realtimeStatus, setRealtimeStatus] = useState<RealtimeStatus>('disconnected')
  const [revision, setRevision] = useState(0)
  const sensorId = device?.id
  const loadDashboard = () => { reload(); setRevision(value => value + 1) }

  useEffect(() => {
    if (sensorId === undefined) return
    const controller = new AbortController()
    let socket: WebSocket | undefined
    let retry: number | undefined
    let poll: number | undefined
    let delay = 1000
    function update(data: Telemetry) {
      if (controller.signal.aborted) return
      const time = Date.parse(data.timestamp ?? '')
      if (!Number.isFinite(time) || time < latestTime.current) return
      latestTime.current = time
      setTelemetry(data)
      setTelemetryMessage('')
    }
    async function read(id: number) {
      try {
        update(await api.getLatestTelemetry(id, controller.signal))
      } catch (error) {
        if (!controller.signal.aborted && !(error instanceof ApiError && error.status === 404)) {
          setTelemetryMessage('Không tải được số đo mới. Đang thử lại.')
        }
      } finally {
        if (!controller.signal.aborted) poll = window.setTimeout(() => void read(id), 5000)
      }
    }
    function connect(id: number) {
      socket = connectDeviceRealtime(id, message => {
        if (message.type === 'telemetry.updated' && message.data && typeof message.data === 'object') {
          const data = message.data as Telemetry
          if (data.deviceId === id) update(data)
        }
      }, state => {
        if (controller.signal.aborted) return
        setRealtimeStatus(state)
        if (state === 'connected') delay = 1000
      }, event => {
        if (controller.signal.aborted || event.code === 1008) return
        retry = window.setTimeout(() => connect(id), delay)
        delay = Math.min(delay * 2, 15000)
      })
    }
    void read(sensorId)
    connect(sensorId)
    return () => {
      controller.abort()
      window.clearTimeout(retry)
      window.clearTimeout(poll)
      socket?.close()
    }
  }, [sensorId, revision])

  const metrics: MetricValue[] = [
    {
      label: 'Nhiệt độ',
      value: telemetry?.temperature ?? null,
      unit: '°C',
      icon: Thermometer,
      tone: 'green',
    },
    {
      label: 'Độ ẩm',
      value: telemetry?.humidity ?? null,
      unit: '%',
      icon: Bird,
      tone: 'blue',
    },
    {
      label: 'CO₂',
      value: telemetry?.co2 ?? null,
      unit: 'ppm',
      icon: Wifi,
      tone: 'purple',
    },
    {
      label: 'Ánh sáng',
      value: telemetry?.light ?? null,
      unit: 'lux',
      icon: ChartNoAxesCombined,
      tone: 'orange',
    },
  ]

  const realtimeLabel = loading
    ? 'Đang tải dữ liệu'
    : realtimeStatus === 'connected'
      ? 'Realtime đang kết nối'
      : realtimeStatus === 'error'
        ? 'Lỗi kết nối realtime'
        : 'Realtime chưa kết nối'

  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">TỔNG QUAN HỆ THỐNG</div>
          <h1>Dashboard</h1>
          <p>
            Chào mừng bạn đến với hệ thống giám sát nhà yến SwiftletCare.
          </p>
        </div>

        <div className="live-badge" role="status">
          <span className="status-dot" />
          {realtimeLabel}
        </div>
      </div>

      {error && (
        <div
          className="panel"
          role="alert"
          style={{ padding: 16, marginBottom: 16 }}
        >
          <p>{error}</p>

          <button
            type="button"
            className="devices-refresh"
            onClick={() => void loadDashboard()}
            disabled={loading}
          >
            <RefreshCw size={15} />
            Thử lại
          </button>
        </div>
      )}

      <p role="status">{device ? device.name + (device.online ? ' · Online' : ' · Offline' + (telemetry ? ' · Hiển thị số đo cuối cùng' : '')) : 'Chưa có ESP32.'}</p>
      {telemetryMessage && (
        <div
          className="panel"
          role="status"
          style={{ padding: 12, marginBottom: 16 }}
        >
          {telemetryMessage}
        </div>
      )}

      <div className="metrics-grid">
        {metrics.map((metric) => {
          const Icon = metric.icon

          return (
            <article className="metric-card" key={metric.label}>
              <div className={`metric-icon ${metric.tone}`}>
                <Icon size={22} />
              </div>

              <div className="metric-label">{metric.label}</div>

              <div className="metric-value">
                {formatValue(metric.value)}
                <span>{metric.unit}</span>
              </div>

              <div className="metric-note">
                <span className="note-dot" />
                {metric.value === null
                  ? 'Chưa có số đo'
                  : telemetry?.timestamp
                    ? `Đo lúc ${formatDateTime(telemetry.timestamp)}`
                    : 'Số đo từ Backend'}
              </div>
            </article>
          )
        })}
      </div>

      <div className="dashboard-grid">
        <DashboardAlerts
          zoneId={zoneId}
          onOpenAlerts={onNavigateToAlerts}
        />

      </div>

      <AiCounts cameras={devices.filter((device) => device.type === 'CAMERA')} />

      <div className="bottom-note">
        <div className="note-icon">
          <Bird size={20} />
        </div>

        <div>
          <strong>
            SwiftletCare · Giám sát nhà yến thông minh
          </strong>

          <p>
            Số đo môi trường được tải từ REST API và cập nhật qua WebSocket
            khi Backend phát sinh sự kiện realtime.
          </p>

          {telemetry?.timestamp && (
            <p>
              Thời điểm đo gần nhất:{' '}
              {formatDateTime(telemetry.timestamp)}
            </p>
          )}

          <button
            type="button"
            className="devices-refresh"
            onClick={() => void loadDashboard()}
            disabled={loading}
          >
            <RefreshCw size={15} />
            {loading ? 'Đang tải...' : 'Làm mới dữ liệu'}
          </button>
        </div>
      </div>
    </>
  )
}
