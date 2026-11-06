
import { useEffect, useState } from 'react'
import {
  Thermometer,
  Bell,
  ChartNoAxesCombined,
  Bird,
  Wifi,
  RefreshCw,
  Cpu,
} from 'lucide-react'
import { api, type Device, type Telemetry } from '../services/api'
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

function formatValue(value: number | null, digits = 1) {
  return value === null ? '—' : value.toFixed(digits)
}

function isSensor(device: Device) {
  const text = `${device.name} ${device.type} ${device.deviceId}`.toLowerCase()
  return (
    text.includes('esp32') ||
    text.includes('sensor') ||
    text.includes('cảm biến')
  )
}

export default function Dashboard() {
  const [devices, setDevices] = useState<Device[]>([])
  const [telemetry, setTelemetry] = useState<Telemetry | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [telemetryMessage, setTelemetryMessage] = useState('')
  const [realtimeStatus, setRealtimeStatus] = useState<
    'connected' | 'disconnected' | 'error'
  >('disconnected')
  async function loadDashboard() {
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
        throw new Error('Vui lòng kiểm tra VITE_ZONE_ID trong file .env.')
      }

      const result = await api.getDevices(zoneId)
      setDevices(result.items)

      const sensor = result.items.find(isSensor)

      if (!sensor) {
        setTelemetryMessage(
          'Chưa tìm thấy thiết bị cảm biến trong khu vực này.',
        )
        return
      }

      try {
        const latest = await api.getLatestTelemetry(sensor.id)
        setTelemetry(latest)
        if (
          latest.temperature == null &&
          latest.humidity == null &&
          latest.co2 == null &&
          latest.light == null
        ) {
          setTelemetryMessage('Bản ghi mới nhất chưa có số đo môi trường.')
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
  }

  useEffect(() => {
    void loadDashboard()
  }, [])
  const sensorId = devices.find(isSensor)?.id ?? null
  console.log('Devices:', devices)
  console.log('Sensor ID:', sensorId)
  useEffect(() => {
    console.log('WebSocket effect running, sensorId:', sensorId)
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

        <div className="live-badge">
          <span className="status-dot" />
          {loading
            ? 'Đang tải dữ liệu'
            : realtimeStatus === 'connected'
              ? 'Realtime đang kết nối'
              : realtimeStatus === 'error'
                ? 'Lỗi kết nối realtime'
                : 'Đang chờ realtime'}
        </div>
      </div>

      {error && (
        <div className="panel" role="alert" style={{ padding: 16 }}>
          <p>{error}</p>
          <button
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
        <div className="panel" role="status" style={{ padding: 12, marginBottom: 16 }}>
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
                    ? `Đo lúc ${new Date(telemetry.timestamp).toLocaleString('vi-VN')}`
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
            <span className="subtle-badge">{devices.length} thiết bị</span>
          </div>

          <div className="device-list">
            {devices.map((device) => (
              <div className="device-row" key={device.id}>
                <div
                  className={`device-symbol ${
                    device.online ? 'device-online' : 'device-offline'
                  }`}
                >
                  <Cpu size={19} />
                </div>

                <div className="device-info">
                  <strong>{device.name}</strong>
                  <span>
                    {device.type} · {device.deviceId}
                  </span>
                </div>

                <span
                  className={`device-state ${
                    device.online ? 'state-online' : 'state-offline'
                  }`}
                >
                  <span className="note-dot" />
                  {device.online ? 'Online' : 'Offline'}
                </span>
              </div>
            ))}

            {!loading && devices.length === 0 && (
              <p style={{ padding: 16 }}>
                {error ? 'Không tải được thiết bị.' : 'Khu vực chưa có thiết bị.'}
              </p>
            )}
          </div>

          <div className="panel-footer">
            Trạng thái thiết bị lấy từ Backend.
          </div>
        </article>

        <article className="panel">
          <div className="panel-heading">
            <div>
              <h2>Cảnh báo gần đây</h2>
              <p>Các sự kiện cần chú ý</p>
            </div>
            <span className="subtle-badge">Demo</span>
          </div>

          <div className="alert-list">
            <div className="alert-row">
              <div className="alert-symbol warning">
                <Bell size={18} />
              </div>
              <div className="alert-info">
                <strong>Chưa kết nối dữ liệu cảnh báo</strong>
                <span>Đang chờ tích hợp API cảnh báo</span>
              </div>
            </div>
          </div>

          <div className="panel-footer">
            Chưa hiển thị cảnh báo thực từ Backend.
          </div>
        </article>
      </div>

      <div className="bottom-note">
        <div className="note-icon">
          <Bird size={20} />
        </div>
        <div>
          <strong>SwiftletCare · Giám sát nhà yến thông minh</strong>
          <p>
            Thiết bị và số đo được tải từ REST API, sau đó cập nhật
            qua WebSocket khi Backend phát sinh sự kiện realtime.
          </p>
          {telemetry?.timestamp && (
            <p>
              Thời điểm đo gần nhất:{' '}
              {new Date(telemetry.timestamp).toLocaleString('vi-VN')}
            </p>
          )}
          <button
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
