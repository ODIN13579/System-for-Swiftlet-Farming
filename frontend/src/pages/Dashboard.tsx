import AiCounts from '../components/AiCounts'
import { useCallback, useEffect, useState } from 'react'
import {
  Thermometer,
  Bell,
  ChartNoAxesCombined,
  Bird,
  Wifi,
  RefreshCw,
  Cpu,
} from 'lucide-react'
import FanControl from '../components/FanControl'
import {
  api,
  type Device,
  type Telemetry,
} from '../services/api'

import { connectDeviceRealtime } from '../services/websocket'

const zoneIdValue = import.meta.env.VITE_ZONE_ID
const zoneId = zoneIdValue ? Number(zoneIdValue) : null

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

function isSensor(device: Device) {
  const text =
    `${device.name} ${device.type} ${device.deviceId}`.toLowerCase()

  return (
    text.includes('esp32') ||
    text.includes('sensor') ||
    text.includes('cảm biến')
  )
}

function hasTelemetryValue(data: Telemetry | null) {
  if (!data) return false

  return [
    data.temperature,
    data.humidity,
    data.co2,
    data.light,
  ].some((value) => value != null)
}

export default function Dashboard() {
  const [devices, setDevices] = useState<Device[]>([])
  const [telemetry, setTelemetry] = useState<Telemetry | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [telemetryMessage, setTelemetryMessage] = useState('')
  const [realtimeStatus, setRealtimeStatus] =
    useState<RealtimeStatus>('disconnected')

  const loadDashboard = useCallback(async () => {
    setLoading(true)
    setError('')
    setTelemetryMessage('')
    setTelemetry(null)

    try {
      if (
        zoneId === null ||
        !Number.isSafeInteger(zoneId) ||
        zoneId <= 0
      ) {
        throw new Error(
          'Vui lòng kiểm tra VITE_ZONE_ID trong file .env.',
        )
      }

      const result = await api.getDevices(zoneId)
      const loadedDevices = result.items ?? []

      setDevices(loadedDevices)

      const sensor = loadedDevices.find(isSensor)

      if (!sensor) {
        setTelemetryMessage(
          'Chưa tìm thấy thiết bị cảm biến trong khu vực này.',
        )
        return
      }

      try {
        const latest = await api.getLatestTelemetry(sensor.id)

        setTelemetry(latest)

        if (!hasTelemetryValue(latest)) {
          setTelemetryMessage(
            'Bản ghi mới nhất chưa có số đo môi trường.',
          )
        }
      } catch (err) {
        setTelemetryMessage(
          err instanceof Error
            ? `Chưa lấy được số đo: ${err.message}`
            : 'Chưa lấy được số đo mới nhất.',
        )
      }
    } catch (err) {
      setError(
        err instanceof Error
          ? err.message
          : 'Không thể tải dữ liệu Dashboard.',
      )
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void loadDashboard()
  }, [loadDashboard])

  const sensorId = devices.find(isSensor)?.id ?? null

  useEffect(() => {
    if (sensorId === null) {
      setRealtimeStatus('disconnected')
      return
    }

    const socket = connectDeviceRealtime(
      sensorId,
      (message) => {
        if (message.type === 'telemetry.updated') {
          const data = message.data as Telemetry

          setTelemetry((previous) => ({
            ...previous,
            ...data,
          }))

          setTelemetryMessage('')
        }

        if (message.type === 'device.status.changed') {
          const data = message.data as Partial<Device>

          if (data.id == null) return

          setDevices((previous) =>
            previous.map((device) =>
              device.id === data.id
                ? { ...device, ...data }
                : device,
            ),
          )
        }
      },
      (status) => {
        setRealtimeStatus(status)
      },
    )

    return () => {
      socket.close()
    }
  }, [sensorId])

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
        <article className="panel">
          <div className="panel-heading">
            <div>
              <h2>Trạng thái thiết bị</h2>
              <p>Tình trạng kết nối thiết bị trong nhà yến</p>
            </div>

            <span className="subtle-badge">
              {loading ? 'Đang tải...' : `${devices.length} thiết bị`}
            </span>
          </div>
          {sensorId !== null && (
            <FanControl deviceId={sensorId} />
          )}
          <div className="device-list">
            {devices.map((device) => (
              <div className="device-row" key={device.id}>
                <div
                  className={`device-symbol ${
                    device.online
                      ? 'device-online'
                      : 'device-offline'
                  }`}
                >
                  <Cpu size={19} />
                </div>

                
                <div className="device-info">
                  <strong>{device.name}</strong>
                  <span>
                    {device.type} · {device.deviceId}
                  </span>
                  <span className="device-last-seen">
                    Cập nhật lần cuối: {formatDateTime(device.lastSeenAt)}
                  </span>
                </div>


                <span
                  className={`device-state ${
                    device.online
                      ? 'state-online'
                      : 'state-offline'
                  }`}
                >
                  <span className="note-dot" />
                  {device.online ? 'Online' : 'Offline'}
                </span>
              </div>
            ))}

            {loading && devices.length === 0 && (
              <p style={{ padding: 16 }} role="status">
                Đang tải danh sách thiết bị...
              </p>
            )}

            {!loading && devices.length === 0 && (
              <p style={{ padding: 16 }}>
                {error
                  ? 'Không tải được danh sách thiết bị.'
                  : 'Khu vực chưa có thiết bị.'}
              </p>
            )}
          </div>

          <div className="panel-footer">
            Trạng thái và thời điểm cập nhật lấy từ dữ liệu Backend.
          </div>
        </article>

        <article className="panel">
          <div className="panel-heading">
            <div>
              <h2>Cảnh báo gần đây</h2>
              <p>Các sự kiện cần chú ý</p>
            </div>

            <span className="subtle-badge">Chưa tích hợp API</span>
          </div>

          <div className="alert-list">
            <div className="alert-row">
              <div className="alert-symbol warning">
                <Bell size={18} />
              </div>

              <div className="alert-info">
                <strong>Chưa có dữ liệu cảnh báo</strong>
                <span>
                  Cần tích hợp API cảnh báo từ Backend để hiển thị sự kiện
                  thực.
                </span>
              </div>
            </div>
          </div>

          <div className="panel-footer">
            Không sử dụng dữ liệu demo thay cho cảnh báo thực.
          </div>
        </article>
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
