import { useAlertSession } from '../components/AlertSession'
import type { Zone } from '../services/api'
import AiCounts from '../components/AiCounts'
import DashboardAlerts from '../components/DashboardAlerts'
import { useCallback, useEffect, useState } from 'react'
import {
  Thermometer,
  ChartNoAxesCombined,
  Bird,
  Wifi,
  RefreshCw,
} from 'lucide-react'
import {
  api,
  type Device,
  type Telemetry,
} from '../services/api'

import { connectDeviceRealtime } from '../services/websocket'

const zoneIdValue = import.meta.env.VITE_ZONE_ID
const preferredZoneId = Number(zoneIdValue)

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

export default function Dashboard({ onNavigateToAlerts }: { onNavigateToAlerts: () => void }) {
  const { houses, houseId, selectHouse, loadingHouses, houseError } = useAlertSession()
  const [selection, setSelection] = useState<{ houseId: number; zones: Zone[]; zoneId: number; error?: string }>()
  const current = selection && selection.houseId === houseId ? selection : undefined
  useEffect(() => {
    if (houseId === '') return
    let cancelled = false
    async function load(id: number) {
      try {
        const zones: Zone[] = []
        for (let page = 0; !cancelled; page++) {
          const result = await api.getZones(id, page, 100)
          zones.push(...result.items.filter(item => item.active))
          if (page + 1 >= result.page.totalPages) break
        }
        const zoneId = zones.find(zone => zone.id === preferredZoneId)?.id ?? zones[0]?.id ?? 0
        if (!cancelled) setSelection({ houseId: id, zones, zoneId })
      } catch (error) {
        if (!cancelled) setSelection({ houseId: id, zones: [], zoneId: 0, error: String(error) })
      }
    }
    void load(houseId)
    return () => { cancelled = true }
  }, [houseId])
  return <>
    <div className="alerts-filters">
      <select aria-label="Nhà yến" value={houseId} disabled={loadingHouses} onChange={event => selectHouse(Number(event.target.value) || '')}>
        <option value="">Chọn nhà yến</option>
        {houses.map(house => <option key={house.id} value={house.id}>{house.name}</option>)}
      </select>
      <select aria-label="Khu vực" value={current?.zoneId || ''} disabled={!current?.zones.length} onChange={event => current && setSelection({ ...current, zoneId: Number(event.target.value) })}>
        <option value="" disabled>Chọn khu vực</option>
        {current?.zones.map(zone => <option key={zone.id} value={zone.id}>{zone.name}</option>)}
      </select>
    </div>
    {(houseError || current?.error) && <p role="alert">{houseError || current?.error}</p>}
    {(loadingHouses || (houseId !== '' && !current)) && <p role="status">Đang tải...</p>}
    {current?.zoneId ? <DashboardZone key={current.zoneId} zoneId={current.zoneId} onNavigateToAlerts={onNavigateToAlerts} /> : null}
  </>
}

function DashboardZone({ zoneId, onNavigateToAlerts }: { zoneId: number; onNavigateToAlerts: () => void }) {
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
  }, [zoneId])

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
