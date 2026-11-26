import { useEffect, useState } from 'react'
import {
  Bar,
  BarChart,
  CartesianGrid,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import {
  Activity,
  ArrowDownLeft,
  ArrowUpRight,
  Bird,
  Camera,
  Clock3,
  Radio,
  RefreshCw,
} from 'lucide-react'
import { api, type CountLog, type Device } from '../services/api'
import { connectDeviceRealtime } from '../services/websocket'

const COLORS = {
  text: '#173b35',
  muted: '#71847f',
  border: '#e4ece8',
  green: '#15966b',
  blue: '#4385e8',
  surface: '#ffffff',
  softGreen: '#eaf7f0',
  softBlue: '#edf4ff',
}

const panelStyle = {
  background: COLORS.surface,
  border: `1px solid ${COLORS.border}`,
  borderRadius: 18,
  padding: 22,
  boxShadow: '0 5px 20px rgba(25, 65, 53, 0.045)',
  minWidth: 0,
} as const

function time(value: string) {
  return new Date(value).toLocaleString('vi-VN', {
    timeZone: 'Asia/Ho_Chi_Minh',
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  })
}

function vietnamDayKey(value: string) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Ho_Chi_Minh',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(new Date(value))

  const get = (type: string) =>
    parts.find((part) => part.type === type)?.value ?? ''

  return `${get('year')}-${get('month')}-${get('day')}`
}

const chartTime = new Intl.DateTimeFormat('vi-VN', {
  timeZone: 'Asia/Ho_Chi_Minh',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})

function StatCard({
  title,
  value,
  subtitle,
  icon,
  color,
  background,
}: {
  title: string
  value: number
  subtitle: string
  icon: React.ReactNode
  color: string
  background: string
}) {
  return (
    <div
      style={{
        background: COLORS.surface,
        border: `1px solid ${COLORS.border}`,
        borderRadius: 16,
        padding: 20,
        minWidth: 0,
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 12,
          marginBottom: 18,
        }}
      >
        <span
          style={{
            fontSize: 13,
            fontWeight: 600,
            color: COLORS.muted,
          }}
        >
          {title}
        </span>
        <span
          style={{
            width: 42,
            height: 42,
            borderRadius: 13,
            background,
            color,
            display: 'grid',
            placeItems: 'center',
            flexShrink: 0,
          }}
        >
          {icon}
        </span>
      </div>

      <div
        style={{
          fontSize: 'clamp(28px, 3vw, 36px)',
          fontWeight: 750,
          letterSpacing: '-1.2px',
          lineHeight: 1.15,
          color: COLORS.text,
          fontVariantNumeric: 'tabular-nums',
        }}
      >
        {value.toLocaleString('vi-VN')}
      </div>

      <div
        style={{
          fontSize: 12,
          color: COLORS.muted,
          marginTop: 10,
        }}
      >
        {subtitle}
      </div>
    </div>
  )
}

function HourlyCountsChart({ rows }: { rows: CountLog[] }) {
  const today = vietnamDayKey(new Date().toISOString())

  const hourlyRows = rows
    .filter(
      (row) =>
        row.aggregationType === 'HOURLY' &&
        vietnamDayKey(row.periodStart) === today,
    )
    .sort(
      (a, b) =>
        Date.parse(a.periodStart) - Date.parse(b.periodStart),
    )

  return (
    <section
      aria-label="Biểu đồ lượt chim vào và ra trong ngày"
      style={panelStyle}
    >
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'flex-start',
          gap: 12,
          flexWrap: 'wrap',
          marginBottom: 8,
        }}
      >
        <div>
          <h3
            style={{
              margin: 0,
              color: COLORS.text,
              fontSize: 17,
              fontWeight: 700,
            }}
          >
            Lượt vào / ra trong ngày
          </h3>
          <p
            style={{
              color: COLORS.muted,
              fontSize: 12,
              margin: '7px 0 0',
            }}
          >
            Ngày Việt Nam: {today} · Dữ liệu theo giờ
          </p>
        </div>

        <span
          style={{
            borderRadius: 20,
            padding: '6px 10px',
            fontSize: 11,
            fontWeight: 650,
            background: '#f1f5f3',
            color: COLORS.muted,
          }}
        >
          {hourlyRows.length} kỳ đếm
        </span>
      </div>

      {hourlyRows.length === 0 ? (
        <div
          style={{
            minHeight: 155,
            display: 'flex',
            flexDirection: 'column',
            justifyContent: 'center',
            alignItems: 'center',
            textAlign: 'center',
            gap: 8,
            padding: '20px 12px',
          }}
        >
          <div
            style={{
              width: 46,
              height: 46,
              display: 'grid',
              placeItems: 'center',
              borderRadius: 15,
              background: '#f0f5f2',
              color: COLORS.muted,
            }}
          >
            <Activity size={22} />
          </div>
          <strong style={{ color: COLORS.text, fontSize: 14 }}>
            Chưa có dữ liệu đếm cho hôm nay
          </strong>
          <span
            style={{
              maxWidth: 380,
              color: COLORS.muted,
              fontSize: 12,
              lineHeight: 1.7,
            }}
          >
            Camera chưa có bản ghi HOURLY trong ngày này.
            Lịch sử các ngày trước vẫn được lưu ở bảng bên dưới.
          </span>
        </div>
      ) : (
        <div style={{ width: '100%', height: 280, marginTop: 16 }}>
          <ResponsiveContainer width="100%" height="100%">
            <BarChart
              data={hourlyRows}
              margin={{ top: 12, right: 8, left: -18, bottom: 4 }}
              barGap={5}
            >
              <CartesianGrid
                stroke="#edf1ef"
                strokeDasharray="4 4"
                vertical={false}
              />
              <XAxis
                dataKey="periodStart"
                tickFormatter={(value: string) =>
                  chartTime.format(new Date(value))
                }
                tick={{ fill: COLORS.muted, fontSize: 11 }}
                axisLine={{ stroke: COLORS.border }}
                tickLine={false}
                minTickGap={20}
              />
              <YAxis
                allowDecimals={false}
                width={40}
                tick={{ fill: COLORS.muted, fontSize: 11 }}
                axisLine={false}
                tickLine={false}
              />
              <Tooltip
                contentStyle={{
                  border: `1px solid ${COLORS.border}`,
                  borderRadius: 12,
                  boxShadow: '0 5px 20px rgba(20, 50, 40, .08)',
                  fontSize: 12,
                }}
                labelFormatter={(_, payload) => {
                  const row = payload[0]?.payload as
                    | CountLog
                    | undefined
                  return row
                    ? `${time(row.periodStart)} – ${time(row.periodEnd)}`
                    : ''
                }}
              />
              <Legend wrapperStyle={{ fontSize: 12, paddingTop: 10 }} />
              <Bar
                dataKey="entryCount"
                name="Lượt vào"
                fill={COLORS.green}
                radius={[5, 5, 0, 0]}
                maxBarSize={34}
              />
              <Bar
                dataKey="exitCount"
                name="Lượt ra"
                fill={COLORS.blue}
                radius={[5, 5, 0, 0]}
                maxBarSize={34}
              />
            </BarChart>
          </ResponsiveContainer>
        </div>
      )}
    </section>
  )
}

export default function AiCounts({ cameras }: { cameras: Device[] }) {
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [rows, setRows] = useState<CountLog[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [connected, setConnected] = useState(false)
  const [lastUpdated, setLastUpdated] = useState('')

  const cameraId =
    cameras.find((camera) => camera.id === selectedId)?.id ??
    cameras[0]?.id ??
    null

  useEffect(() => {
    let active = true
    let busy = false
    let reload = false
    let socket: WebSocket | null = null
    let reconnectTimer: number | undefined
    let retryDelay = 1000
    const controller = new AbortController()

    setRows([])
    setError('')
    setConnected(false)
    setLastUpdated('')
    setLoading(cameraId !== null)

    if (cameraId === null) {
      setLoading(false)
      return () => {
        active = false
        controller.abort()
      }
    }

    const deviceId = cameraId

    async function load() {
      if (!active) return
      if (busy) {
        reload = true
        return
      }

      busy = true

      try {
        const now = Date.now()
        const result = await api.getCountLogs(
          deviceId,
          new Date(now - 31 * 86400000).toISOString(),
          new Date(now).toISOString(),
          controller.signal,
        )

        if (active) {
          const sorted = [...result.items].sort(
            (a, b) =>
              Date.parse(b.periodStart) - Date.parse(a.periodStart),
          )
          setRows(sorted)
          setError('')
          setLastUpdated(new Date().toISOString())
        }
      } catch (err) {
        if (active) {
          setError(
            err instanceof Error
              ? err.message
              : 'Không tải được kết quả AI.',
          )
        }
      } finally {
        busy = false

        if (active) {
          setLoading(false)
          if (reload) {
            reload = false
            void load()
          }
        }
      }
    }

    function connect() {
      if (!active) return

      try {
        socket = connectDeviceRealtime(
          deviceId,
          (message) => {
            if (active && message.type === 'ai.count.updated') {
              void load()
            }
          },
          (status) => {
            if (!active) return

            setConnected(status === 'connected')

            if (status === 'connected') {
              retryDelay = 1000
              void load()
            }

            if (status === 'disconnected') {
              reconnectTimer = window.setTimeout(connect, retryDelay)
              retryDelay = Math.min(retryDelay * 2, 15000)
            }
          },
        )
      } catch {
        setConnected(false)
        reconnectTimer = window.setTimeout(connect, retryDelay)
        retryDelay = Math.min(retryDelay * 2, 15000)
      }
    }

    void load()
    connect()

    const timer = window.setInterval(() => void load(), 10000)

    return () => {
      active = false
      controller.abort()
      window.clearInterval(timer)
      window.clearTimeout(reconnectTimer)
      socket?.close()
    }
  }, [cameraId])

  const latest = rows[0]


  return (
    <article
      style={{
        marginTop: 24,
        display: 'flex',
        flexDirection: 'column',
        gap: 18,
        minWidth: 0,
      }}
    >
      <section style={panelStyle}>
        <div
          style={{
            display: 'flex',
            alignItems: 'flex-start',
            justifyContent: 'space-between',
            flexWrap: 'wrap',
            gap: 18,
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 13 }}>
            <div
              style={{
                width: 48,
                height: 48,
                borderRadius: 15,
                display: 'grid',
                placeItems: 'center',
                color: COLORS.green,
                background: COLORS.softGreen,
                flexShrink: 0,
              }}
            >
              <Bird size={25} />
            </div>
            <div>
              <div
                style={{
                  color: COLORS.green,
                  fontSize: 10,
                  fontWeight: 750,
                  letterSpacing: 1.1,
                  marginBottom: 5,
                }}
              >
                AI MONITORING
              </div>
              <h2
                style={{
                  color: COLORS.text,
                  fontSize: 22,
                  lineHeight: 1.25,
                  fontWeight: 750,
                  margin: 0,
                }}
              >
                Kết quả đếm chim AI
              </h2>
              <p
                style={{
                  color: COLORS.muted,
                  fontSize: 12,
                  margin: '7px 0 0',
                }}
              >
                Thống kê camera · Múi giờ Việt Nam (UTC+7)
              </p>
            </div>
          </div>

          <div
            role="status"
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 8,
              borderRadius: 30,
              padding: '9px 12px',
              background: connected ? '#eaf7f0' : '#f3f5f4',
              color: connected ? '#14794f' : COLORS.muted,
              fontSize: 12,
              fontWeight: 650,
            }}
          >
            <span
              style={{
                width: 8,
                height: 8,
                borderRadius: '50%',
                background: connected ? '#1da56d' : '#a0ada8',
              }}
            />
            {connected ? 'Realtime đã kết nối' : 'Đang chờ kết nối realtime'}
          </div>
        </div>

        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 10,
            flexWrap: 'wrap',
            marginTop: 22,
            paddingTop: 18,
            borderTop: `1px solid ${COLORS.border}`,
          }}
        >
          <Camera size={18} color={COLORS.muted} />
          <label
            htmlFor="ai-camera-select"
            style={{
              color: COLORS.muted,
              fontSize: 13,
              fontWeight: 600,
            }}
          >
            Camera giám sát
          </label>

          {cameras.length > 0 ? (
            <select
              id="ai-camera-select"
              value={cameraId ?? ''}
              onChange={(event) => setSelectedId(Number(event.target.value))}
              style={{
                minWidth: 190,
                maxWidth: '100%',
                border: `1px solid ${COLORS.border}`,
                borderRadius: 10,
                background: '#fff',
                color: COLORS.text,
                padding: '10px 12px',
                fontSize: 13,
                fontWeight: 600,
                outlineColor: COLORS.green,
              }}
            >
              {cameras.map((camera) => (
                <option key={camera.id} value={camera.id}>
                  {camera.name || camera.deviceId} (ID {camera.id})
                </option>
              ))}
            </select>
          ) : (
            <span style={{ color: COLORS.muted, fontSize: 13 }}>
              Chưa có camera trong khu vực này.
            </span>
          )}

          <div style={{ flex: 1 }} />

          {lastUpdated && (
            <span
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 6,
                color: COLORS.muted,
                fontSize: 11,
              }}
            >
              <Clock3 size={14} />
              Cập nhật lúc {time(lastUpdated)}
            </span>
          )}
        </div>
      </section>

      {error && (
        <div
          role="alert"
          style={{
            ...panelStyle,
            borderColor: '#f1c7c7',
            background: '#fffafa',
            color: '#a33a3a',
            fontSize: 13,
          }}
        >
          <strong>Không tải được dữ liệu mới.</strong>
          <p style={{ margin: '6px 0 0' }}>{error}</p>
        </div>
      )}

      {loading ? (
        <section style={panelStyle} role="status">
          <div style={{ color: COLORS.muted, fontSize: 13 }}>
            <RefreshCw
              size={16}
              style={{
                display: 'inline-block',
                verticalAlign: 'middle',
                marginRight: 8,
              }}
            />
            Đang tải dữ liệu đếm chim...
          </div>
        </section>
      ) : (
        <>
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 205px), 1fr))',
              gap: 14,
            }}
          >
            <StatCard
              title="Lượt vào kỳ gần nhất"
              value={latest?.entryCount ?? 0}
              subtitle={latest ? time(latest.periodStart) : 'Chưa có kỳ đếm'}
              icon={<ArrowDownLeft size={23} />}
              color={COLORS.green}
              background={COLORS.softGreen}
            />
            <StatCard
              title="Lượt ra kỳ gần nhất"
              value={latest?.exitCount ?? 0}
              subtitle={latest ? time(latest.periodEnd) : 'Chưa có kỳ đếm'}
              icon={<ArrowUpRight size={23} />}
              color={COLORS.blue}
              background={COLORS.softBlue}
            />
            <StatCard
              title="Phát hiện trong kỳ"
              value={latest?.detectCount ?? 0}
              subtitle={
                latest
                  ? `Xử lý lúc ${time(latest.processedAt)}`
                  : 'Chưa có kỳ đếm'
              }
              icon={<Activity size={22} />}
              color="#8762c6"
              background="#f3edfc"
            />
          </div>

          <section style={panelStyle}>
            <div
              style={{
                display: 'flex',
                alignItems: 'flex-start',
                justifyContent: 'space-between',
                gap: 12,
                flexWrap: 'wrap',
                marginBottom: 18,
              }}
            >
              <div>
                <h3
                  style={{
                    color: COLORS.text,
                    fontSize: 17,
                    margin: 0,
                    fontWeight: 700,
                  }}
                >
                  Lịch sử đếm gần đây
                </h3>
                <p
                  style={{
                    color: COLORS.muted,
                    fontSize: 12,
                    margin: '7px 0 0',
                  }}
                >
                  Các kỳ API trả về trong khoảng tìm kiếm
                </p>
              </div>

              <span
                style={{
                  borderRadius: 20,
                  padding: '6px 10px',
                  color: COLORS.text,
                  background: '#f1f5f3',
                  fontSize: 11,
                  fontWeight: 650,
                }}
              >
                {rows.length} bản ghi
              </span>
            </div>

            {rows.length === 0 ? (
              <div
                style={{
                  padding: '28px 12px',
                  textAlign: 'center',
                  color: COLORS.muted,
                  fontSize: 13,
                }}
              >
                Chưa có dữ liệu trong khoảng thời gian tìm kiếm.
              </div>
            ) : (
              <div
                style={{
                  width: '100%',
                  overflowX: 'auto',
                  border: `1px solid ${COLORS.border}`,
                  borderRadius: 12,
                }}
              >
                <table
                  style={{
                    width: '100%',
                    minWidth: 640,
                    borderCollapse: 'collapse',
                    textAlign: 'left',
                    fontSize: 12,
                  }}
                >
                  <thead>
                    <tr style={{ background: '#f6f9f7', color: COLORS.muted }}>
                      {[
                        'Thời gian bắt đầu',
                        'Thời gian kết thúc',
                        'Loại kỳ',
                        'Lượt vào',
                        'Lượt ra',
                        'Phát hiện',
                      ].map((heading) => (
                        <th
                          key={heading}
                          style={{
                            padding: '13px 14px',
                            fontWeight: 650,
                            whiteSpace: 'nowrap',
                            borderBottom: `1px solid ${COLORS.border}`,
                          }}
                        >
                          {heading}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {rows.slice(0, 10).map((row, index) => (
                      <tr
                        key={row.id}
                        style={{
                          background: index % 2 === 0 ? '#fff' : '#fbfcfb',
                          color: COLORS.text,
                        }}
                      >
                        <td
                          style={{
                            padding: '14px',
                            whiteSpace: 'nowrap',
                            borderBottom:
                              index === Math.min(rows.length, 10) - 1
                                ? 'none'
                                : `1px solid ${COLORS.border}`,
                          }}
                        >
                          {time(row.periodStart)}
                        </td>
                        <td
                          style={{
                            padding: '14px',
                            whiteSpace: 'nowrap',
                            borderBottom:
                              index === Math.min(rows.length, 10) - 1
                                ? 'none'
                                : `1px solid ${COLORS.border}`,
                          }}
                        >
                          {time(row.periodEnd)}
                        </td>
                        <td
                          style={{
                            padding: '14px',
                            borderBottom: `1px solid ${COLORS.border}`,
                          }}
                        >
                          <span
                            style={{
                              borderRadius: 6,
                              padding: '5px 7px',
                              background:
                                row.aggregationType === 'HOURLY'
                                  ? COLORS.softBlue
                                  : COLORS.softGreen,
                              color:
                                row.aggregationType === 'HOURLY'
                                  ? '#315fa8'
                                  : '#14794f',
                              fontSize: 10,
                              fontWeight: 700,
                            }}
                          >
                            {row.aggregationType}
                          </span>
                        </td>
                        <td
                          style={{
                            padding: '14px',
                            fontWeight: 750,
                            color: COLORS.green,
                            borderBottom: `1px solid ${COLORS.border}`,
                          }}
                        >
                          {row.entryCount}
                        </td>
                        <td
                          style={{
                            padding: '14px',
                            fontWeight: 750,
                            color: COLORS.blue,
                            borderBottom: `1px solid ${COLORS.border}`,
                          }}
                        >
                          {row.exitCount}
                        </td>
                        <td
                          style={{
                            padding: '14px',
                            fontWeight: 650,
                            borderBottom: `1px solid ${COLORS.border}`,
                          }}
                        >
                          {row.detectCount}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            <p
              style={{
                margin: '12px 0 0',
                color: COLORS.muted,
                fontSize: 11,
                lineHeight: 1.7,
              }}
            >
              Số liệu trên phản ánh từng kỳ đếm, không phải tổng đàn chim
              hiện có. Dữ liệu được sắp xếp theo thời gian bắt đầu mới nhất.
            </p>
          </section>

          <HourlyCountsChart rows={rows} />

          <div
            style={{
              display: 'flex',
              alignItems: 'flex-start',
              gap: 10,
              border: `1px solid ${COLORS.border}`,
              borderRadius: 12,
              background: '#f8faf9',
              padding: 14,
              color: COLORS.muted,
              fontSize: 12,
              lineHeight: 1.7,
            }}
          >
            <Radio size={17} style={{ flexShrink: 0, marginTop: 2 }} />
            <span>
              <strong style={{ color: COLORS.text }}>
                Cập nhật tự động.
              </strong>{' '}
              Hệ thống tải lại dữ liệu định kỳ mỗi 10 giây và tải lại khi
              nhận sự kiện đếm chim AI qua WebSocket.
            </span>
          </div>
        </>
      )}
    </article>
  )
}
