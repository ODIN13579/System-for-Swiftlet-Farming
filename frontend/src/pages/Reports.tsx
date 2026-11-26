import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  Activity,
  CalendarDays,
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  Clock3,
  Download,
  Droplets,
  RefreshCw,
  Search,
  Thermometer,
  Wind,
  Sun,
  Database,
} from 'lucide-react'
import {
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import {
  api,
  type Device,
  type House,
  type Telemetry,
  type Zone,
} from '../services/api'

type Metric = 'temperature' | 'humidity' | 'co2' | 'light'
type Grouping = 'hour' | 'day'

const metricOptions: {
  value: Metric
  label: string
  unit: string
  description: string
  color: string
}[] = [
  {
    value: 'temperature',
    label: 'Nhiệt độ',
    unit: '°C',
    description: 'Theo dõi nhiệt độ nhà yến',
    color: '#e58a45',
  },
  {
    value: 'humidity',
    label: 'Độ ẩm',
    unit: '%',
    description: 'Theo dõi độ ẩm không khí',
    color: '#4299cf',
  },
  {
    value: 'co2',
    label: 'CO₂',
    unit: 'ppm',
    description: 'Theo dõi nồng độ CO₂',
    color: '#8b79d9',
  },
  {
    value: 'light',
    label: 'Ánh sáng',
    unit: 'lux',
    description: 'Theo dõi cường độ ánh sáng',
    color: '#c99b35',
  },
]

const PAGE_SIZE = 20

const inputStyle: React.CSSProperties = {
  width: '100%',
  minWidth: 0,
  minHeight: 42,
  padding: '10px 12px',
  border: '1px solid var(--border, #dfe7e2)',
  borderRadius: 10,
  background: 'var(--surface, #fff)',
  color: 'var(--text, #24352d)',
  fontSize: 13,
  outlineOffset: 2,
  boxSizing: 'border-box',
}

const labelStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 7,
  color: 'var(--text, #34483d)',
  fontSize: 12,
  fontWeight: 600,
}

const mutedText: React.CSSProperties = {
  color: 'var(--muted, #7a8980)',
  fontSize: 12,
  lineHeight: 1.6,
  margin: '5px 0 0',
}

const primaryButton: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  gap: 7,
  minHeight: 38,
  padding: '9px 13px',
  border: '1px solid #286b4b',
  borderRadius: 10,
  background: '#286b4b',
  color: '#fff',
  fontSize: 12,
  fontWeight: 600,
  cursor: 'pointer',
}

const secondaryButton: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  gap: 7,
  minHeight: 38,
  padding: '9px 13px',
  border: '1px solid var(--border, #dfe7e2)',
  borderRadius: 10,
  background: 'var(--surface, #fff)',
  color: 'var(--text, #34483d)',
  fontSize: 12,
  fontWeight: 600,
  cursor: 'pointer',
}

function localDateTimeInput(date: Date) {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function toVietnamIso(value: string) {
  const date = new Date(value)

  if (Number.isNaN(date.getTime())) {
    throw new Error('Khoảng thời gian không hợp lệ.')
  }

  const offset = -date.getTimezoneOffset()
  const sign = offset >= 0 ? '+' : '-'
  const pad = (n: number) => String(Math.abs(n)).padStart(2, '0')

  return `${value}:00${sign}${pad(Math.floor(Math.abs(offset) / 60))}:${pad(Math.abs(offset) % 60)}`
}

function formatTime(value?: string) {
  if (!value) return '—'

  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return 'Thời gian không hợp lệ'

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

function formatNumber(value: number) {
  return new Intl.NumberFormat('vi-VN', {
    maximumFractionDigits: 2,
  }).format(value)
}

function getMetric(row: Telemetry, metric: Metric): number | null {
  const value = row[metric]
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function csvCell(value: unknown) {
  const text = value == null ? '' : String(value)
  return `"${text.replaceAll('"', '""')}"`
}

function SectionHeading({
  icon,
  title,
  description,
  action,
}: {
  icon: React.ReactNode
  title: string
  description: string
  action?: React.ReactNode
}) {
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        gap: 12,
        flexWrap: 'wrap',
        marginBottom: 18,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 11 }}>
        <div
          style={{
            width: 38,
            height: 38,
            flexShrink: 0,
            display: 'grid',
            placeItems: 'center',
            borderRadius: 11,
            background: '#eaf4ed',
            color: '#286b4b',
          }}
        >
          {icon}
        </div>
        <div>
          <h2
            style={{
              margin: '1px 0 0',
              fontSize: 16,
              fontWeight: 700,
              letterSpacing: '-0.25px',
            }}
          >
            {title}
          </h2>
          <p style={mutedText}>{description}</p>
        </div>
      </div>
      {action}
    </div>
  )
}

function SummaryCard({
  icon,
  label,
  value,
  detail,
  accent,
}: {
  icon: React.ReactNode
  label: string
  value: string
  detail: string
  accent: string
}) {
  return (
    <div
      style={{
        minWidth: 0,
        padding: 17,
        border: '1px solid var(--border, #e3eae5)',
        borderRadius: 14,
        background: 'var(--surface, #fff)',
        boxShadow: '0 2px 8px rgba(27, 55, 39, 0.025)',
      }}
    >
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          gap: 10,
          marginBottom: 15,
        }}
      >
        <span style={{ color: 'var(--muted, #738278)', fontSize: 12 }}>
          {label}
        </span>
        <span
          style={{
            width: 34,
            height: 34,
            display: 'grid',
            placeItems: 'center',
            borderRadius: 10,
            background: `${accent}18`,
            color: accent,
          }}
        >
          {icon}
        </span>
      </div>
      <div
        style={{
          color: 'var(--text, #24352d)',
          fontSize: 25,
          fontWeight: 750,
          letterSpacing: '-0.8px',
          overflowWrap: 'anywhere',
        }}
      >
        {value}
      </div>
      <p style={{ ...mutedText, marginTop: 6 }}>{detail}</p>
    </div>
  )
}

export default function Reports() {
  const now = new Date()
  const todayStart = new Date(now)
  todayStart.setHours(0, 0, 0, 0)

  const [houses, setHouses] = useState<House[]>([])
  const [zones, setZones] = useState<Zone[]>([])
  const [devices, setDevices] = useState<Device[]>([])

  const [houseId, setHouseId] = useState('')
  const [zoneId, setZoneId] = useState('')
  const [deviceId, setDeviceId] = useState('')
  const [metric, setMetric] = useState<Metric>('temperature')
  const [grouping, setGrouping] = useState<Grouping>('hour')
  const [from, setFrom] = useState(localDateTimeInput(todayStart))
  const [to, setTo] = useState(localDateTimeInput(now))

  const [rows, setRows] = useState<Telemetry[]>([])
  const [page, setPage] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [totalElements, setTotalElements] = useState(0)
  const [loading, setLoading] = useState(false)
  const [loadingOptions, setLoadingOptions] = useState(true)
  const [error, setError] = useState('')
  const [optionsError, setOptionsError] = useState('')
  const requestVersion = useRef(0)

  useEffect(() => {
    let active = true
    const controller = new AbortController()

    async function loadHouses() {
      setLoadingOptions(true)
      setOptionsError('')

      try {
        const result = await api.getHouses(0, 100)
        if (!active || controller.signal.aborted) return

        const items = result.items ?? []
        setHouses(items)
        setHouseId((current) => current || (items.length ? String(items[0].id) : ''))
      } catch (err) {
        if (active && !controller.signal.aborted) {
          setOptionsError(
            err instanceof Error ? err.message : 'Không tải được danh sách nhà yến.',
          )
        }
      } finally {
        if (active && !controller.signal.aborted) setLoadingOptions(false)
      }
    }

    void loadHouses()

    return () => {
      active = false
      controller.abort()
    }
  }, [])

  useEffect(() => {
    let active = true
    const controller = new AbortController()

    if (!houseId) {
      setZones([])
      setZoneId('')
      return () => {
        active = false
        controller.abort()
      }
    }

    async function loadZones() {
      setOptionsError('')

      try {
        const result = await api.getZones(Number(houseId), 0, 100)
        if (!active || controller.signal.aborted) return

        const items = result.items ?? []
        setZones(items)
        setZoneId((current) =>
          items.some((item) => String(item.id) === current)
            ? current
            : items.length
              ? String(items[0].id)
              : '',
        )
      } catch (err) {
        if (active && !controller.signal.aborted) {
          setZones([])
          setZoneId('')
          setOptionsError(
            err instanceof Error ? err.message : 'Không tải được danh sách khu vực.',
          )
        }
      }
    }

    void loadZones()

    return () => {
      active = false
      controller.abort()
    }
  }, [houseId])

  useEffect(() => {
    let active = true
    const controller = new AbortController()

    if (!zoneId) {
      setDevices([])
      setDeviceId('')
      return () => {
        active = false
        controller.abort()
      }
    }

    async function loadDevices() {
      setOptionsError('')

      try {
        const result = await api.getDevices(Number(zoneId), 0, 100)
        if (!active || controller.signal.aborted) return

        const sensorDevices = (result.items ?? []).filter((device) => {
          const type = device.type.toLowerCase()
          const name = device.name.toLowerCase()

          return (
            type.includes('sensor') ||
            type.includes('esp32') ||
            name.includes('sensor') ||
            name.includes('esp32') ||
            name.includes('cảm biến')
          )
        })

        setDevices(sensorDevices)
        setDeviceId((current) =>
          sensorDevices.some((device) => String(device.id) === current)
            ? current
            : sensorDevices.length
              ? String(sensorDevices[0].id)
              : '',
        )
      } catch (err) {
        if (active && !controller.signal.aborted) {
          setDevices([])
          setDeviceId('')
          setOptionsError(
            err instanceof Error ? err.message : 'Không tải được thiết bị.',
          )
        }
      }
    }

    void loadDevices()

    return () => {
      active = false
      controller.abort()
    }
  }, [zoneId])

  const loadTelemetry = useCallback(async () => {
    const requestId = ++requestVersion.current

    if (!deviceId) {
      setRows([])
      setTotalPages(0)
      setTotalElements(0)
      setError('')
      setLoading(false)
      return
    }

    if (
      !from ||
      !to ||
      Number.isNaN(new Date(from).getTime()) ||
      Number.isNaN(new Date(to).getTime()) ||
      new Date(from).getTime() >= new Date(to).getTime()
    ) {
      setRows([])
      setTotalPages(0)
      setTotalElements(0)
      setError('Thời gian bắt đầu phải trước thời gian kết thúc.')
      setLoading(false)
      return
    }

    setLoading(true)
    setError('')

    try {
      const result = await api.getTelemetryHistory(
        Number(deviceId),
        toVietnamIso(from),
        toVietnamIso(to),
        page,
        PAGE_SIZE,
      )

      if (requestId !== requestVersion.current) return

      setRows(result.items ?? [])
      setTotalPages(result.page?.totalPages ?? 0)
      setTotalElements(result.page?.totalElements ?? 0)
    } catch (err) {
      if (requestId !== requestVersion.current) return

      setRows([])
      setTotalPages(0)
      setTotalElements(0)
      setError(
        err instanceof Error ? err.message : 'Không tải được lịch sử Telemetry.',
      )
    } finally {
      if (requestId === requestVersion.current) setLoading(false)
    }
  }, [deviceId, from, to, page])

  useEffect(() => {
    void loadTelemetry()

    return () => {
      requestVersion.current += 1
    }
  }, [loadTelemetry])

  const selectedMetric = metricOptions.find((item) => item.value === metric)!
  const selectedDevice = devices.find((item) => String(item.id) === deviceId)
  const selectedHouse = houses.find((item) => String(item.id) === houseId)
  const selectedZone = zones.find((item) => String(item.id) === zoneId)

  const chartData = useMemo(() => {
    const validRows = rows
      .filter(
        (row) =>
          row.timestamp &&
          !Number.isNaN(new Date(row.timestamp).getTime()),
      )
      .map((row) => ({
        time: new Date(row.timestamp!).getTime(),
        value: getMetric(row, metric),
      }))
      .sort((a, b) => a.time - b.time)

    const buckets = new Map<number, number[]>()

    for (const row of validRows) {
      if (row.value === null) continue

      const date = new Date(row.time)
      if (grouping === 'hour') date.setMinutes(0, 0, 0)
      else date.setHours(0, 0, 0, 0)

      const key = date.getTime()
      const values = buckets.get(key) ?? []
      values.push(row.value)
      buckets.set(key, values)
    }

    const start = new Date(from)
    const end = new Date(to)
    const cursor = new Date(start)

    if (grouping === 'hour') cursor.setMinutes(0, 0, 0)
    else cursor.setHours(0, 0, 0, 0)

    const result: { time: number; label: string; value: number | null }[] = []
    const maxBuckets = 500

    while (cursor.getTime() <= end.getTime() && result.length < maxBuckets) {
      const key = cursor.getTime()
      const values = buckets.get(key) ?? []
      const value = values.length
        ? values.reduce((sum, number) => sum + number, 0) / values.length
        : null

      result.push({
        time: key,
        label: new Intl.DateTimeFormat('vi-VN', {
          timeZone: 'Asia/Ho_Chi_Minh',
          ...(grouping === 'hour'
            ? {
                day: '2-digit',
                month: '2-digit',
                hour: '2-digit',
                minute: '2-digit',
              }
            : {
                day: '2-digit',
                month: '2-digit',
                year: '2-digit',
              }),
        }).format(cursor),
        value,
      })

      if (grouping === 'hour') cursor.setHours(cursor.getHours() + 1)
      else cursor.setDate(cursor.getDate() + 1)
    }

    return result
  }, [rows, metric, grouping, from, to])

  const validMetricValues = useMemo(
    () =>
      rows
        .map((row) => getMetric(row, metric))
        .filter((value): value is number => value !== null),
    [rows, metric],
  )

  const averageValue = validMetricValues.length
    ? validMetricValues.reduce((sum, value) => sum + value, 0) /
      validMetricValues.length
    : null

  function handleExport() {
    if (rows.length === 0) return

    const header = [
      'Thời gian (UTC+7)',
      'Nhiệt độ (°C)',
      'Độ ẩm (%)',
      'CO2 (ppm)',
      'Ánh sáng (lux)',
    ]

    const csvRows = rows.map((row) => [
      formatTime(row.timestamp),
      row.temperature ?? '',
      row.humidity ?? '',
      row.co2 ?? '',
      row.light ?? '',
    ])

    const csv = [header, ...csvRows]
      .map((row) => row.map(csvCell).join(','))
      .join('\r\n')

    const blob = new Blob(['\uFEFF' + csv], {
      type: 'text/csv;charset=utf-8;',
    })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `swiftletcare-telemetry-page-${page + 1}.csv`
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
  }

  const hasSelectedDevice = Boolean(deviceId)
  const hasChartValues = chartData.some((item) => item.value !== null)

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 18, paddingBottom: 24 }}>
      <header
        className="page-heading"
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          flexWrap: 'wrap',
          gap: 16,
          padding: '4px 0 2px',
        }}
      >
        <div>
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 7,
              color: '#39815b',
              fontSize: 10,
              fontWeight: 800,
              letterSpacing: '1.5px',
              marginBottom: 8,
            }}
          >
            <Activity size={14} />
            PHÂN TÍCH DỮ LIỆU
          </div>
          <h1
            style={{
              margin: 0,
              fontSize: 'clamp(24px, 3vw, 30px)',
              fontWeight: 760,
              letterSpacing: '-0.8px',
              lineHeight: 1.25,
            }}
          >
            Thống kê &amp; Báo cáo
          </h1>
          <p style={{ ...mutedText, fontSize: 13, marginTop: 7 }}>
            Theo dõi biến động môi trường và tra cứu lịch sử cảm biến nhà yến.
          </p>
        </div>

        <div
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 8,
            padding: '9px 12px',
            border: '1px solid #dcebe0',
            borderRadius: 999,
            background: '#f0f7f1',
            color: '#286b4b',
            fontSize: 12,
            fontWeight: 650,
          }}
        >
          <span
            style={{
              width: 8,
              height: 8,
              borderRadius: '50%',
              background: '#35a36b',
              boxShadow: '0 0 0 3px #35a36b20',
            }}
          />
          Dữ liệu từ Backend
        </div>
      </header>

      {optionsError && (
        <div
          role="alert"
          style={{
            padding: '12px 14px',
            border: '1px solid #f0d2c8',
            borderRadius: 11,
            background: '#fff6f2',
            color: '#a44931',
            fontSize: 13,
          }}
        >
          {optionsError}
        </div>
      )}

      <section
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(185px, 1fr))',
          gap: 12,
        }}
      >
        <SummaryCard
          icon={<Database size={18} />}
          label="Tổng bản ghi phù hợp"
          value={totalElements.toLocaleString('vi-VN')}
          detail="Theo bộ lọc và khoảng thời gian"
          accent="#39815b"
        />
        <SummaryCard
          icon={<Activity size={18} />}
          label="Bản ghi trên trang"
          value={rows.length.toLocaleString('vi-VN')}
          detail={`Trang ${totalPages ? page + 1 : 0} · Tối đa ${PAGE_SIZE} bản ghi`}
          accent="#4299cf"
        />
        <SummaryCard
          icon={
            metric === 'temperature' ? (
              <Thermometer size={18} />
            ) : metric === 'humidity' ? (
              <Droplets size={18} />
            ) : metric === 'light' ? (
              <Sun size={18} />
            ) : (
              <Wind size={18} />
            )
          }
          label={`Trung bình ${selectedMetric.label.toLowerCase()}`}
          value={
            averageValue === null
              ? '—'
              : `${formatNumber(averageValue)} ${selectedMetric.unit}`
          }
          detail="Tính từ các bản ghi hợp lệ trên trang hiện tại"
          accent={selectedMetric.color}
        />
        <SummaryCard
          icon={<Clock3 size={18} />}
          label="Khoảng thời gian"
          value={from && to ? 'Đã chọn' : 'Chưa chọn'}
          detail={
            from && to
              ? `${formatTime(from)} – ${formatTime(to)}`
              : 'Chọn thời gian để tra cứu'
          }
          accent="#8b79d9"
        />
      </section>

      <section
        className="panel"
        style={{
          padding: '20px',
          borderRadius: 16,
          border: '1px solid var(--border, #e3eae5)',
          boxShadow: '0 4px 18px rgba(27, 55, 39, 0.035)',
        }}
      >
        <SectionHeading
          icon={<Search size={19} />}
          title="Bộ lọc thống kê"
          description="Thu hẹp dữ liệu theo vị trí, thiết bị, chỉ số và thời gian."
        />

        {loadingOptions ? (
          <div
            role="status"
            style={{
              padding: 20,
              textAlign: 'center',
              color: 'var(--muted, #738278)',
              fontSize: 13,
            }}
          >
            Đang tải danh sách nhà yến...
          </div>
        ) : (
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fit, minmax(190px, 1fr))',
              gap: 15,
            }}
          >
            <label style={labelStyle}>
              Nhà yến
              <select
                style={inputStyle}
                value={houseId}
                onChange={(event) => {
                  setHouseId(event.target.value)
                  setPage(0)
                }}
              >
                <option value="">Chọn nhà yến</option>
                {houses.map((house: House) => (
                  <option key={house.id} value={house.id}>
                    {house.name}
                  </option>
                ))}
              </select>
            </label>

            <label style={labelStyle}>
              Khu vực
              <select
                style={inputStyle}
                value={zoneId}
                onChange={(event) => {
                  setZoneId(event.target.value)
                  setPage(0)
                }}
                disabled={!houseId}
              >
                <option value="">Chọn khu vực</option>
                {zones.map((zone: Zone) => (
                  <option key={zone.id} value={zone.id}>
                    {zone.name}
                  </option>
                ))}
              </select>
            </label>

            <label style={labelStyle}>
              Thiết bị cảm biến
              <select
                style={inputStyle}
                value={deviceId}
                onChange={(event) => {
                  setDeviceId(event.target.value)
                  setPage(0)
                }}
                disabled={!zoneId}
              >
                <option value="">Chọn thiết bị</option>
                {devices.map((device: Device) => (
                  <option key={device.id} value={device.id}>
                    {device.name}
                  </option>
                ))}
              </select>
            </label>

            <label style={labelStyle}>
              Chỉ số môi trường
              <select
                style={inputStyle}
                value={metric}
                onChange={(event) => setMetric(event.target.value as Metric)}
              >
                {metricOptions.map((item) => (
                  <option key={item.value} value={item.value}>
                    {item.label} ({item.unit})
                  </option>
                ))}
              </select>
            </label>

            <label style={labelStyle}>
              Cách gom nhóm
              <select
                style={inputStyle}
                value={grouping}
                onChange={(event) => setGrouping(event.target.value as Grouping)}
              >
                <option value="hour">Theo giờ</option>
                <option value="day">Theo ngày</option>
              </select>
            </label>

            <label style={labelStyle}>
              <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <CalendarDays size={14} />
                Từ thời điểm
              </span>
              <input
                style={inputStyle}
                type="datetime-local"
                value={from}
                max={to}
                onChange={(event) => {
                  setFrom(event.target.value)
                  setPage(0)
                }}
              />
            </label>

            <label style={labelStyle}>
              <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <CalendarDays size={14} />
                Đến thời điểm
              </span>
              <input
                style={inputStyle}
                type="datetime-local"
                value={to}
                min={from}
                onChange={(event) => {
                  setTo(event.target.value)
                  setPage(0)
                }}
              />
            </label>
          </div>
        )}

        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            flexWrap: 'wrap',
            gap: 12,
            paddingTop: 16,
            marginTop: 17,
            borderTop: '1px solid var(--border, #e8eee9)',
          }}
        >
          <p style={{ ...mutedText, margin: 0 }}>
            {selectedHouse?.name || 'Chưa chọn nhà yến'}
            {' / '}
            {selectedZone?.name || 'Chưa chọn khu vực'}
            {' / '}
            {selectedDevice?.name || 'Chưa chọn thiết bị'}
          </p>

          <button
            type="button"
            style={secondaryButton}
            onClick={() => {
              void loadTelemetry()
            }}
            disabled={loading || loadingOptions}
          >
            <RefreshCw size={15} />
            {loading ? 'Đang tải...' : 'Áp dụng / Làm mới'}
          </button>
        </div>
      </section>

      <section
        className="panel report-chart-panel"
        style={{
          padding: 20,
          borderRadius: 16,
          border: '1px solid var(--border, #e3eae5)',
          boxShadow: '0 4px 18px rgba(27, 55, 39, 0.035)',
        }}
      >
        <SectionHeading
          icon={<Activity size={19} />}
          title={`Biến động ${selectedMetric.label.toLowerCase()}`}
          description={`${selectedMetric.description}. Đơn vị đo: ${selectedMetric.unit}.`}
          action={
            <span
              style={{
                padding: '7px 10px',
                borderRadius: 8,
                background: `${selectedMetric.color}15`,
                color: selectedMetric.color,
                fontSize: 12,
                fontWeight: 700,
              }}
            >
              {selectedMetric.unit}
            </span>
          }
        />

        {loading ? (
          <div
            role="status"
            style={{
              minHeight: 250,
              display: 'grid',
              placeItems: 'center',
              color: 'var(--muted, #738278)',
              fontSize: 13,
            }}
          >
            Đang tải dữ liệu biểu đồ...
          </div>
        ) : error ? (
          <div
            role="alert"
            style={{
              padding: 16,
              borderRadius: 10,
              background: '#fff5f1',
              color: '#a44931',
              fontSize: 13,
            }}
          >
            {error}
          </div>
        ) : !hasSelectedDevice ? (
          <div
            style={{
              minHeight: 220,
              display: 'grid',
              placeItems: 'center',
              textAlign: 'center',
              color: 'var(--muted, #738278)',
              fontSize: 13,
            }}
          >
            Chọn nhà yến, khu vực và thiết bị cảm biến để xem biểu đồ.
          </div>
        ) : !hasChartValues ? (
          <div
            style={{
              minHeight: 220,
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              justifyContent: 'center',
              textAlign: 'center',
              gap: 8,
              color: 'var(--muted, #738278)',
            }}
          >
            <div
              style={{
                width: 48,
                height: 48,
                display: 'grid',
                placeItems: 'center',
                borderRadius: 14,
                background: '#f0f5f1',
                color: '#71927d',
              }}
            >
              <Activity size={22} />
            </div>
            <strong style={{ color: 'var(--text, #34483d)', fontSize: 14 }}>
              Chưa có dữ liệu để vẽ biểu đồ
            </strong>
            <p style={{ ...mutedText, margin: 0, maxWidth: 360 }}>
              Không tìm thấy số đo hợp lệ cho chỉ số và khoảng thời gian đang chọn.
              Hãy thử một khoảng thời gian khác.
            </p>
          </div>
        ) : (
          <>
            <div
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
                flexWrap: 'wrap',
                gap: 8,
                marginBottom: 10,
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <span
                  style={{
                    width: 9,
                    height: 9,
                    borderRadius: '50%',
                    background: selectedMetric.color,
                  }}
                />
                <span style={{ fontSize: 12, fontWeight: 650 }}>
                  {selectedMetric.label}
                </span>
                <span style={{ ...mutedText, margin: 0 }}>
                  · {validMetricValues.length} số đo hợp lệ trên trang
                </span>
              </div>
              <span style={mutedText}>
                {grouping === 'hour' ? 'Gom theo giờ' : 'Gom theo ngày'}
              </span>
            </div>

            <div style={{ width: '100%', height: 310, minWidth: 0 }}>
              <ResponsiveContainer width="100%" height="100%">
                <LineChart
                  data={chartData}
                  margin={{ top: 12, right: 12, left: 4, bottom: 24 }}
                >
                  <CartesianGrid
                    strokeDasharray="3 5"
                    vertical={false}
                    stroke="#e7ede8"
                  />
                  <XAxis
                    dataKey="label"
                    tick={{ fontSize: 10, fill: '#7a8980' }}
                    tickLine={false}
                    axisLine={{ stroke: '#dfe7e1' }}
                    angle={-25}
                    textAnchor="end"
                    height={52}
                    minTickGap={18}
                  />
                  <YAxis
                    unit={` ${selectedMetric.unit}`}
                    tick={{ fontSize: 10, fill: '#7a8980' }}
                    tickLine={false}
                    axisLine={false}
                    width={65}
                    domain={['auto', 'auto']}
                  />
                  <Tooltip
                    contentStyle={{
                      border: '1px solid #e1e9e3',
                      borderRadius: 12,
                      boxShadow: '0 8px 24px rgba(27, 55, 39, 0.09)',
                      fontSize: 12,
                    }}
                    labelStyle={{
                      color: '#34483d',
                      fontWeight: 700,
                      marginBottom: 5,
                    }}
                    formatter={(value) => [
                      value == null
                        ? 'Không có dữ liệu'
                        : `${formatNumber(Number(value))} ${selectedMetric.unit}`,
                      selectedMetric.label,
                    ]}
                  />
                  <Line
                    type="monotone"
                    dataKey="value"
                    name={selectedMetric.label}
                    stroke={selectedMetric.color}
                    strokeWidth={2.5}
                    dot={false}
                    activeDot={{
                      r: 5,
                      strokeWidth: 2,
                      fill: '#fff',
                    }}
                    connectNulls={false}
                  />
                </LineChart>
              </ResponsiveContainer>
            </div>

            <div
              style={{
                display: 'flex',
                alignItems: 'flex-start',
                gap: 8,
                padding: '11px 12px',
                marginTop: 8,
                borderRadius: 10,
                background: 'var(--soft-surface, #f5f8f5)',
                color: 'var(--muted, #738278)',
                fontSize: 11,
                lineHeight: 1.6,
              }}
            >
              <CheckCircle2 size={15} style={{ flexShrink: 0, marginTop: 1 }} />
              <span>
                Biểu đồ tổng hợp các bản ghi đang tải trên trang hiện tại.
                Khoảng trống thể hiện thời điểm không có số đo hợp lệ; không tự tạo
                dữ liệu cho những thời điểm bị thiếu.
              </span>
            </div>
          </>
        )}
      </section>

      <section
        className="panel report-table-panel"
        style={{
          padding: 20,
          borderRadius: 16,
          border: '1px solid var(--border, #e3eae5)',
          boxShadow: '0 4px 18px rgba(27, 55, 39, 0.035)',
        }}
      >
        <SectionHeading
          icon={<Database size={19} />}
          title="Lịch sử Telemetry"
          description={`${totalElements.toLocaleString('vi-VN')} bản ghi phù hợp với bộ lọc hiện tại.`}
          action={
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
              <button
                type="button"
                style={secondaryButton}
                onClick={() => void loadTelemetry()}
                disabled={loading}
              >
                <RefreshCw size={14} />
                Làm mới
              </button>
              <button
                type="button"
                style={{
                  ...primaryButton,
                  opacity: loading || rows.length === 0 ? 0.55 : 1,
                  cursor: loading || rows.length === 0 ? 'not-allowed' : 'pointer',
                }}
                onClick={handleExport}
                disabled={loading || rows.length === 0}
              >
                <Download size={14} />
                Xuất CSV
              </button>
            </div>
          }
        />

        {loading ? (
          <div
            role="status"
            style={{
              padding: '36px 12px',
              textAlign: 'center',
              color: 'var(--muted, #738278)',
              fontSize: 13,
            }}
          >
            Đang tải lịch sử Telemetry...
          </div>
        ) : error ? (
          <div
            role="alert"
            style={{
              padding: 15,
              borderRadius: 10,
              background: '#fff5f1',
              color: '#a44931',
              fontSize: 13,
            }}
          >
            {error}
          </div>
        ) : !hasSelectedDevice ? (
          <p style={{ ...mutedText, padding: '22px 0' }}>
            Chọn nhà yến, khu vực và thiết bị cảm biến để xem dữ liệu.
          </p>
        ) : rows.length === 0 ? (
          <div
            style={{
              padding: '38px 12px',
              textAlign: 'center',
              color: 'var(--muted, #738278)',
            }}
          >
            <Database size={26} style={{ marginBottom: 10, opacity: 0.55 }} />
            <p
              style={{
                margin: 0,
                color: 'var(--text, #34483d)',
                fontWeight: 650,
                fontSize: 13,
              }}
            >
              Không có bản ghi phù hợp
            </p>
            <p style={mutedText}>
              Thử đổi thiết bị hoặc mở rộng khoảng thời gian tra cứu.
            </p>
          </div>
        ) : (
          <div
            className="report-table-wrap"
            style={{
              width: '100%',
              overflowX: 'auto',
              border: '1px solid var(--border, #e3eae5)',
              borderRadius: 12,
            }}
          >
            <table
              className="report-table"
              style={{
                width: '100%',
                minWidth: 650,
                borderCollapse: 'separate',
                borderSpacing: 0,
                textAlign: 'left',
                fontSize: 12,
              }}
            >
              <thead>
                <tr style={{ background: 'var(--soft-surface, #f4f7f4)' }}>
                  {[
                    'Thời gian (UTC+7)',
                    'Nhiệt độ (°C)',
                    'Độ ẩm (%)',
                    'CO₂ (ppm)',
                    'Ánh sáng (lux)',
                  ].map((heading) => (
                    <th
                      key={heading}
                      style={{
                        padding: '13px 14px',
                        color: 'var(--muted, #65756a)',
                        fontSize: 11,
                        fontWeight: 700,
                        whiteSpace: 'nowrap',
                        borderBottom: '1px solid var(--border, #e3eae5)',
                      }}
                    >
                      {heading}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {rows.map((row, index) => (
                  <tr
                    key={`${row.timestamp ?? 'no-time'}-${index}`}
                    style={{
                      background:
                        index % 2 === 0
                          ? 'var(--surface, #fff)'
                          : 'var(--row-alt, #fbfcfb)',
                    }}
                  >
                    <td
                      style={{
                        padding: '13px 14px',
                        whiteSpace: 'nowrap',
                        fontWeight: 600,
                        color: 'var(--text, #34483d)',
                        borderBottom:
                          index === rows.length - 1
                            ? 'none'
                            : '1px solid var(--border, #edf1ed)',
                      }}
                    >
                      {formatTime(row.timestamp)}
                    </td>
                    {[row.temperature, row.humidity, row.co2, row.light].map(
                      (value, valueIndex) => (
                        <td
                          key={valueIndex}
                          style={{
                            padding: '13px 14px',
                            color:
                              value == null
                                ? 'var(--muted, #9aa59d)'
                                : 'var(--text, #34483d)',
                            fontVariantNumeric: 'tabular-nums',
                            borderBottom:
                              index === rows.length - 1
                                ? 'none'
                                : '1px solid var(--border, #edf1ed)',
                          }}
                        >
                          {value == null ? '—' : formatNumber(value)}
                        </td>
                      ),
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            gap: 12,
            flexWrap: 'wrap',
            paddingTop: 17,
            marginTop: 16,
            borderTop: '1px solid var(--border, #e8eee9)',
          }}
        >
          <span style={mutedText}>
            {totalElements === 0
              ? 'Chưa có dữ liệu'
              : `Hiển thị ${rows.length} / ${totalElements.toLocaleString('vi-VN')} bản ghi`}
          </span>

          <div style={{ display: 'flex', alignItems: 'center', gap: 9 }}>
            <button
              type="button"
              aria-label="Trang trước"
              style={{
                ...secondaryButton,
                minWidth: 38,
                padding: '8px 10px',
                opacity: loading || page <= 0 ? 0.45 : 1,
                cursor: loading || page <= 0 ? 'not-allowed' : 'pointer',
              }}
              onClick={() => setPage((current) => Math.max(0, current - 1))}
              disabled={loading || page <= 0}
            >
              <ChevronLeft size={16} />
            </button>

            <span
              style={{
                minWidth: 90,
                textAlign: 'center',
                color: 'var(--text, #34483d)',
                fontSize: 12,
                fontWeight: 650,
              }}
            >
              Trang {totalPages ? page + 1 : 0} / {totalPages}
            </span>

            <button
              type="button"
              aria-label="Trang sau"
              style={{
                ...secondaryButton,
                minWidth: 38,
                padding: '8px 10px',
                opacity: loading || page + 1 >= totalPages ? 0.45 : 1,
                cursor:
                  loading || page + 1 >= totalPages
                    ? 'not-allowed'
                    : 'pointer',
              }}
              onClick={() =>
                setPage((current) =>
                  Math.min(Math.max(0, totalPages - 1), current + 1),
                )
              }
              disabled={loading || page + 1 >= totalPages}
            >
              <ChevronRight size={16} />
            </button>
          </div>
        </div>
      </section>
    </div>
  )
}
