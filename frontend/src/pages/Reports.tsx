
import { useCallback, useEffect, useState } from 'react'
import {
  Activity,
  AlertTriangle,
  Bird,
  CheckCircle2,
  Download,
  Droplets,
  RefreshCw,
  Thermometer,
  Wifi,
} from 'lucide-react'
import {
  CartesianGrid,
  Legend,
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
  type Telemetry,
} from '../services/api'

const zoneIdValue = import.meta.env.VITE_ZONE_ID
const zoneId = zoneIdValue ? Number(zoneIdValue) : null

type EnvironmentPoint = {
  time: string
  temperature: number | null
  humidity: number | null
}

type ReportRow = {
  id: number
  device: string
  deviceId: string
  temperature: number | null
  humidity: number | null
  online: boolean
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

function average(values: Array<number | null | undefined>) {
  const valid = values.filter(
    (value): value is number =>
      typeof value === 'number' && Number.isFinite(value),
  )

  if (valid.length === 0) return null

  return valid.reduce((sum, value) => sum + value, 0) / valid.length
}

function formatNumber(value: number | null, digits = 1) {
  return value === null ? '—' : value.toFixed(digits)
}

function getLocalDateString(date: Date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')

  return `${year}-${month}-${day}`
}

function dateToISOString(dateString: string, endOfDay = false) {
  const date = new Date(
    `${dateString}T${endOfDay ? '23:59:59.999' : '00:00:00.000'}`,
  )

  return date.toISOString()
}

function formatTime(timestamp?: string) {
  if (!timestamp) return '—'

  const date = new Date(timestamp)

  if (Number.isNaN(date.getTime())) return '—'

  return date.toLocaleTimeString('vi-VN', {
    hour: '2-digit',
    minute: '2-digit',
  })
}

export default function Reports() {
  const today = getLocalDateString(new Date())

  const [fromDate, setFromDate] = useState(today)
  const [toDate, setToDate] = useState(today)

  const [devices, setDevices] = useState<Device[]>([])
  const [environmentData, setEnvironmentData] = useState<
    EnvironmentPoint[]
  >([])
  const [reportRows, setReportRows] = useState<ReportRow[]>([])

  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [partialError, setPartialError] = useState('')

  const [averageTemperature, setAverageTemperature] =
    useState<number | null>(null)
  const [averageHumidity, setAverageHumidity] =
    useState<number | null>(null)

  const loadReports = useCallback(async () => {
    setLoading(true)
    setError('')
    setPartialError('')

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

      if (!fromDate || !toDate || fromDate > toDate) {
        throw new Error('Khoảng thời gian báo cáo không hợp lệ.')
      }

      const deviceResponse = await api.getDevices(zoneId)
      const loadedDevices = deviceResponse.items ?? []

      setDevices(loadedDevices)

      const sensors = loadedDevices.filter(isSensor)

      if (sensors.length === 0) {
        setEnvironmentData([])
        setReportRows([])
        setAverageTemperature(null)
        setAverageHumidity(null)
        setPartialError(
          'Khu vực chưa có thiết bị cảm biến để lập báo cáo môi trường.',
        )
        return
      }

      const from = dateToISOString(fromDate)
      const to = dateToISOString(toDate, true)

      const results = await Promise.all(
        sensors.map(async (device) => {
          try {
            const [historyResponse, latest] = await Promise.all([
              api.getTelemetryHistory(device.id, from, to, 0, 100),
              api.getLatestTelemetry(device.id).catch(() => null),
            ])

            return {
              device,
              history: historyResponse.items ?? [],
              latest,
              failed: false,
            }
          } catch {
            return {
              device,
              history: [] as Telemetry[],
              latest: null,
              failed: true,
            }
          }
        }),
      )

      const failedCount = results.filter((item) => item.failed).length

      if (failedCount > 0) {
        setPartialError(
          `Không tải được lịch sử của ${failedCount} thiết bị. Báo cáo có thể chưa đầy đủ.`,
        )
      }

      const allReadings = results.flatMap((result) =>
        result.history
          .filter((item) => {
            if (!item.timestamp) return false

            const time = new Date(item.timestamp).getTime()

            return (
              Number.isFinite(time) &&
              time >= new Date(from).getTime() &&
              time <= new Date(to).getTime()
            )
          })
          .map((item) => ({
            ...item,
            deviceId: result.device.id,
          })),
      )

      const sortedReadings = [...allReadings].sort(
        (a, b) =>
          new Date(a.timestamp!).getTime() -
          new Date(b.timestamp!).getTime(),
      )

      setAverageTemperature(
        average(sortedReadings.map((item) => item.temperature)),
      )
      setAverageHumidity(
        average(sortedReadings.map((item) => item.humidity)),
      )

      // Gộp các bản ghi cùng phút để biểu đồ dễ đọc hơn.
      const buckets = new Map<
        string,
        {
          temperatures: number[]
          humidities: number[]
        }
      >()

      for (const item of sortedReadings) {
        const date = new Date(item.timestamp!)
        const key = `${getLocalDateString(date)} ${formatTime(item.timestamp)}`

        if (!buckets.has(key)) {
          buckets.set(key, {
            temperatures: [],
            humidities: [],
          })
        }

        const bucket = buckets.get(key)!

        if (
          typeof item.temperature === 'number' &&
          Number.isFinite(item.temperature)
        ) {
          bucket.temperatures.push(item.temperature)
        }

        if (
          typeof item.humidity === 'number' &&
          Number.isFinite(item.humidity)
        ) {
          bucket.humidities.push(item.humidity)
        }
      }

      const chartData: EnvironmentPoint[] = Array.from(
        buckets.entries(),
      ).map(([time, values]) => ({
        time,
        temperature: average(values.temperatures),
        humidity: average(values.humidities),
      }))

      setEnvironmentData(chartData)

      const rows: ReportRow[] = results.map((result) => {
        const latestInRange = [...result.history]
          .filter((item) => {
            if (!item.timestamp) return false

            const time = new Date(item.timestamp).getTime()

            return (
              Number.isFinite(time) &&
              time >= new Date(from).getTime() &&
              time <= new Date(to).getTime()
            )
          })
          .sort(
            (a, b) =>
              new Date(b.timestamp!).getTime() -
              new Date(a.timestamp!).getTime(),
          )[0]

        return {
          id: result.device.id,
          device: result.device.name,
          deviceId: result.device.deviceId,
          temperature:
            latestInRange?.temperature ??
            (result.latest?.temperature ?? null),
          humidity:
            latestInRange?.humidity ??
            (result.latest?.humidity ?? null),
          online: result.device.online,
        }
      })

      setReportRows(rows)
    } catch (err) {
      setError(
        err instanceof Error
          ? err.message
          : 'Không thể tải báo cáo.',
      )

      setEnvironmentData([])
      setReportRows([])
      setAverageTemperature(null)
      setAverageHumidity(null)
    } finally {
      setLoading(false)
    }
  }, [fromDate, toDate])

  useEffect(() => {
    void loadReports()
  }, [loadReports])

  function handleExport() {
    const header = [
      'Ten thiet bi',
      'Ma thiet bi',
      'Nhiet do gan nhat (C)',
      'Do am gan nhat (%)',
      'Trang thai',
    ]

    const rows = reportRows.map((row) => [
      row.device,
      row.deviceId,
      row.temperature === null ? '' : String(row.temperature),
      row.humidity === null ? '' : String(row.humidity),
      row.online ? 'Online' : 'Offline',
    ])

    const csv = [header, ...rows]
      .map((row) =>
        row
          .map((cell) => `"${cell.replaceAll('"', '""')}"`)
          .join(','),
      )
      .join('\r\n')

    const blob = new Blob(['\uFEFF' + csv], {
      type: 'text/csv;charset=utf-8;',
    })

    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')

    link.href = url
    link.download = `swiftletcare-report-${fromDate}-${toDate}.csv`
    document.body.appendChild(link)
    link.click()
    link.remove()

    // Giải phóng URL sau khi trình duyệt đã bắt đầu tải.
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
  }

  const onlineCount = devices.filter((device) => device.online).length

  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">PHÂN TÍCH DỮ LIỆU</div>
          <h1>Thống kê &amp; Báo cáo</h1>
          <p>
            Tổng hợp dữ liệu môi trường và trạng thái thiết bị từ Backend.
          </p>
        </div>

        <div className="live-badge" role="status">
          <span className="status-dot" />
          {loading ? 'Đang tải dữ liệu' : 'Dữ liệu Backend'}
        </div>
      </div>

      <section
        className="panel"
        style={{ padding: 16, marginBottom: 20 }}
        aria-label="Bộ lọc thời gian báo cáo"
      >
        <div
          style={{
            display: 'flex',
            flexWrap: 'wrap',
            alignItems: 'end',
            gap: 12,
          }}
        >
          <label style={{ display: 'grid', gap: 6 }}>
            <span> Từ ngày </span>
            <input
              type="date"
              value={fromDate}
              max={toDate}
              onChange={(event) => setFromDate(event.target.value)}
            />
          </label>

          <label style={{ display: 'grid', gap: 6 }}>
            <span>Đến ngày</span>
            <input
              type="date"
              value={toDate}
              min={fromDate}
              onChange={(event) => setToDate(event.target.value)}
            />
          </label>

          <button
            type="button"
            className="devices-refresh"
            onClick={() => void loadReports()}
            disabled={loading}
          >
            <RefreshCw size={15} />
            {loading ? 'Đang tải...' : 'Tải báo cáo'}
          </button>
        </div>
      </section>

      {error && (
        <section
          className="panel"
          role="alert"
          style={{ padding: 16, marginBottom: 16 }}
        >
          <p>{error}</p>
          <button
            type="button"
            className="devices-refresh"
            onClick={() => void loadReports()}
            disabled={loading}
          >
            Thử lại
          </button>
        </section>
      )}

      {partialError && (
        <section
          className="panel"
          role="status"
          style={{ padding: 12, marginBottom: 16 }}
        >
          {partialError}
        </section>
      )}

      <div className="report-summary-grid">
        <article className="metric-card">
          <div className="report-card-top">
            <span className="metric-label">Nhiệt độ trung bình</span>
            <Thermometer size={19} />
          </div>
          <div className="metric-value">
            {formatNumber(averageTemperature)}
            <span className="report-unit">°C</span>
          </div>
          <div className="metric-note">
            Trong khoảng thời gian đã chọn
          </div>
        </article>

        <article className="metric-card">
          <div className="report-card-top">
            <span className="metric-label">Độ ẩm trung bình</span>
            <Droplets size={19} />
          </div>
          <div className="metric-value">
            {formatNumber(averageHumidity)}
            <span className="report-unit">%</span>
          </div>
          <div className="metric-note">
            Trong khoảng thời gian đã chọn
          </div>
        </article>

        <article className="metric-card">
          <div className="report-card-top">
            <span className="metric-label">Thiết bị Online</span>
            <Wifi size={19} />
          </div>
          <div className="metric-value">
            {onlineCount}
            <span className="report-unit">/{devices.length}</span>
          </div>
          <div className="metric-note">
            Trạng thái do Backend cung cấp
          </div>
        </article>

        <article className="metric-card">
          <div className="report-card-top">
            <span className="metric-label">Bản ghi môi trường</span>
            <Activity size={19} />
          </div>
          <div className="metric-value">{environmentData.length}</div>
          <div className="metric-note">
            Số mốc thời gian trên biểu đồ
          </div>
        </article>
      </div>

      <div className="reports-chart-grid">
        <section className="panel report-chart-panel">
          <div className="report-panel-heading">
            <div>
              <h2>Biến động môi trường</h2>
              <p>Nhiệt độ và độ ẩm từ lịch sử đo thực tế</p>
            </div>
            <Activity size={19} />
          </div>

          <div className="report-chart">
            {environmentData.length === 0 ? (
              <div style={{ padding: 20 }}>
                {loading
                  ? 'Đang tải lịch sử môi trường...'
                  : 'Không có dữ liệu môi trường trong khoảng thời gian này.'}
              </div>
            ) : (
              <ResponsiveContainer width="100%" height="100%">
                <LineChart
                  data={environmentData}
                  margin={{ top: 12, right: 8, left: -18, bottom: 0 }}
                >
                  <CartesianGrid
                    strokeDasharray="3 3"
                    vertical={false}
                  />
                  <XAxis dataKey="time" tick={{ fontSize: 10 }} />
                  <YAxis
                    yAxisId="temperature"
                    tick={{ fontSize: 10 }}
                  />
                  <YAxis
                    yAxisId="humidity"
                    orientation="right"
                    domain={[0, 100]}
                    tick={{ fontSize: 10 }}
                  />
                  <Tooltip />
                  <Legend wrapperStyle={{ fontSize: 11 }} />
                  <Line
                    yAxisId="temperature"
                    type="monotone"
                    dataKey="temperature"
                    name="Nhiệt độ (°C)"
                    stroke="#2f7654"
                    strokeWidth={2.5}
                    dot={false}
                    connectNulls
                  />
                  <Line
                    yAxisId="humidity"
                    type="monotone"
                    dataKey="humidity"
                    name="Độ ẩm (%)"
                    stroke="#579bd7"
                    strokeWidth={2.5}
                    dot={false}
                    connectNulls
                  />
                </LineChart>
              </ResponsiveContainer>
            )}
          </div>
        </section>

        <section className="panel report-chart-panel">
          <div className="report-panel-heading">
            <div>
              <h2>Thống kê chim IN/OUT</h2>
              <p>Sẵn sàng kết nối dữ liệu đếm chim từ Backend</p>
            </div>
            <Bird size={19} />
          </div>

          <div
            className="report-chart"
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              padding: 24,
              textAlign: 'center',
            }}
          >
            <div>
              <Bird size={36} />
              <p>
                <strong>Chưa có dữ liệu CountLog</strong>
              </p>
              <p>
                Biểu đồ sẽ hiển thị số chim bay vào (IN) và bay ra (OUT)
                sau khi nhóm tích hợp API thống kê.
              </p>
              <p>
                Không hiển thị số liệu giả khi chưa có dữ liệu thực.
              </p>
            </div>
          </div>
        </section>
      </div>

      <section className="panel report-table-panel">
        <div className="report-table-heading">
          <div>
            <h2>Tổng hợp theo thiết bị cảm biến</h2>
            <p>
              Số đo mới nhất trong khoảng chọn, nếu có lịch sử đo phù hợp
            </p>
          </div>

          <button
            type="button"
            className="report-export-button"
            onClick={handleExport}
            disabled={reportRows.length === 0}
          >
            <Download size={15} />
            Xuất CSV
          </button>
        </div>

        <div className="report-table-wrap">
          <table className="report-table">
            <thead>
              <tr>
                <th>Thiết bị</th>
                <th>Mã thiết bị</th>
                <th>Nhiệt độ</th>
                <th>Độ ẩm</th>
                <th>Trạng thái</th>
              </tr>
            </thead>

            <tbody>
              {reportRows.map((row) => (
                <tr key={row.id}>
                  <td>{row.device}</td>
                  <td>{row.deviceId}</td>
                  <td>
                    {row.temperature === null
                      ? '—'
                      : `${formatNumber(row.temperature)} °C`}
                  </td>
                  <td>
                    {row.humidity === null
                      ? '—'
                      : `${formatNumber(row.humidity)} %`}
                  </td>
                  <td>
                    <span
                      className={`report-state ${
                        row.online
                          ? 'report-state-good'
                          : 'report-state-watch'
                      }`}
                    >
                      {row.online ? (
                        <CheckCircle2 size={14} />
                      ) : (
                        <AlertTriangle size={14} />
                      )}
                      {row.online ? 'Online' : 'Offline'}
                    </span>
                  </td>
                </tr>
              ))}

              {!loading && reportRows.length === 0 && (
                <tr>
                  <td colSpan={5}>
                    Chưa có dữ liệu thiết bị để hiển thị.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>

        <div className="devices-footnote">
          <Activity size={15} />
          Báo cáo môi trường sử dụng API lịch sử đo. Cảnh báo và CountLog
          chưa được kết nối trong phiên bản này.
        </div>
      </section>
    </>
  )
}
