
import {
  Activity,
  AlertTriangle,
  CheckCircle2,
  Download,
  Droplets,
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
  BarChart,
  Bar,
} from 'recharts'

const environmentData = [
  { time: '06:00', temperature: 27.1, humidity: 84 },
  { time: '08:00', temperature: 28.0, humidity: 83 },
  { time: '10:00', temperature: 29.2, humidity: 81 },
  { time: '12:00', temperature: 31.0, humidity: 78 },
  { time: '14:00', temperature: 32.1, humidity: 75 },
  { time: '16:00', temperature: 30.5, humidity: 79 },
  { time: '18:00', temperature: 29.4, humidity: 82 },
]

const alertData = [
  { day: 'T2', critical: 2, warning: 4 },
  { day: 'T3', critical: 1, warning: 3 },
  { day: 'T4', critical: 3, warning: 5 },
  { day: 'T5', critical: 1, warning: 2 },
  { day: 'T6', critical: 2, warning: 4 },
  { day: 'T7', critical: 0, warning: 3 },
  { day: 'CN', critical: 1, warning: 2 },
]

const reportRows = [
  { zone: 'Khu vực A', temperature: '29.4°C', humidity: '82%', status: 'Ổn định' },
  { zone: 'Khu vực B', temperature: '30.1°C', humidity: '79%', status: 'Cần theo dõi' },
  { zone: 'Khu vực C', temperature: '28.8°C', humidity: '85%', status: 'Ổn định' },
]

export default function Reports() {
  function handleExport() {
    const header = ['Khu vực', 'Nhiệt độ', 'Độ ẩm', 'Đánh giá']
    const rows = reportRows.map((row) => [
      row.zone,
      row.temperature,
      row.humidity,
      row.status,
    ])
    const csv = [header, ...rows]
      .map((row) => row.map((cell) => `"${cell.replaceAll('"', '""')}"`).join(','))
      .join('\r\n')

    const blob = new Blob(['\uFEFF' + csv], {
      type: 'text/csv;charset=utf-8;',
    })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = 'swiftletcare-report.csv'
    link.click()
    URL.revokeObjectURL(url)
  }

  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">PHÂN TÍCH DỮ LIỆU</div>
          <h1>Thống kê & Báo cáo</h1>
          <p>Tổng hợp môi trường, thiết bị và cảnh báo của nhà yến.</p>
        </div>
        <div className="live-badge">
          <span className="status-dot" />
          Dữ liệu mô phỏng
        </div>
      </div>

      <div className="report-summary-grid">
        <article className="metric-card">
          <div className="report-card-top">
            <span className="metric-label">Nhiệt độ trung bình</span>
            <Thermometer size={19} />
          </div>
          <div className="metric-value">29.5<span className="report-unit">°C</span></div>
          <div className="metric-note">Trong ngày mô phỏng</div>
        </article>

        <article className="metric-card">
          <div className="report-card-top">
            <span className="metric-label">Độ ẩm trung bình</span>
            <Droplets size={19} />
          </div>
          <div className="metric-value">80.3<span className="report-unit">%</span></div>
          <div className="metric-note">Trong ngày mô phỏng</div>
        </article>

        <article className="metric-card">
          <div className="report-card-top">
            <span className="metric-label">Tổng cảnh báo</span>
            <AlertTriangle size={19} />
          </div>
          <div className="metric-value">33</div>
          <div className="metric-note">Trong tuần minh họa</div>
        </article>

        <article className="metric-card">
          <div className="report-card-top">
            <span className="metric-label">Thiết bị trực tuyến</span>
            <Wifi size={19} />
          </div>
          <div className="metric-value">3/4</div>
          <div className="metric-note">Theo dữ liệu mẫu</div>
        </article>
      </div>

      <div className="reports-chart-grid">
        <section className="panel report-chart-panel">
          <div className="report-panel-heading">
            <div>
              <h2>Biến động môi trường</h2>
              <p>Nhiệt độ và độ ẩm theo thời gian</p>
            </div>
            <Activity size={19} />
          </div>

          <div className="report-chart">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={environmentData} margin={{ top: 12, right: 8, left: -18, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" vertical={false} />
                <XAxis dataKey="time" tick={{ fontSize: 10 }} />
                <YAxis yAxisId="temperature" tick={{ fontSize: 10 }} />
                <YAxis yAxisId="humidity" orientation="right" domain={[60, 100]} tick={{ fontSize: 10 }} />
                <Tooltip />
                <Legend wrapperStyle={{ fontSize: 11 }} />
                <Line
                  yAxisId="temperature"
                  type="monotone"
                  dataKey="temperature"
                  name="Nhiệt độ (°C)"
                  stroke="#2f7654"
                  strokeWidth={2.5}
                  dot={{ r: 3 }}
                />
                <Line
                  yAxisId="humidity"
                  type="monotone"
                  dataKey="humidity"
                  name="Độ ẩm (%)"
                  stroke="#579bd7"
                  strokeWidth={2.5}
                  dot={{ r: 3 }}
                />
              </LineChart>
            </ResponsiveContainer>
          </div>
        </section>

        <section className="panel report-chart-panel">
          <div className="report-panel-heading">
            <div>
              <h2>Thống kê cảnh báo</h2>
              <p>Số cảnh báo theo ngày trong tuần</p>
            </div>
            <AlertTriangle size={19} />
          </div>

          <div className="report-chart">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={alertData} margin={{ top: 12, right: 8, left: -18, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" vertical={false} />
                <XAxis dataKey="day" tick={{ fontSize: 10 }} />
                <YAxis allowDecimals={false} tick={{ fontSize: 10 }} />
                <Tooltip />
                <Legend wrapperStyle={{ fontSize: 11 }} />
                <Bar dataKey="critical" name="Nghiêm trọng" stackId="alerts" fill="#d95c50" radius={[3, 3, 0, 0]} />
                <Bar dataKey="warning" name="Cảnh báo" stackId="alerts" fill="#e8b04b" radius={[3, 3, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </div>
        </section>
      </div>

      <section className="panel report-table-panel">
        <div className="report-table-heading">
          <div>
            <h2>Tổng hợp theo khu vực</h2>
            <p>Thông số môi trường minh họa tại từng khu vực</p>
          </div>
          <button className="report-export-button" onClick={handleExport}>
            <Download size={15} />
            Xuất CSV
          </button>
        </div>

        <div className="report-table-wrap">
          <table className="report-table">
            <thead>
              <tr>
                <th>Khu vực</th>
                <th>Nhiệt độ</th>
                <th>Độ ẩm</th>
                <th>Đánh giá</th>
              </tr>
            </thead>
            <tbody>
              {reportRows.map((row) => (
                <tr key={row.zone}>
                  <td>{row.zone}</td>
                  <td>{row.temperature}</td>
                  <td>{row.humidity}</td>
                  <td>
                    <span className={`report-state ${row.status === 'Ổn định' ? 'report-state-good' : 'report-state-watch'}`}>
                      {row.status === 'Ổn định' ? <CheckCircle2 size={14} /> : <AlertTriangle size={14} />}
                      {row.status}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <div className="devices-footnote">
          <Activity size={15} />
          Báo cáo hiện dùng dữ liệu mẫu; chưa đại diện cho số liệu thực tế của nhà yến.
        </div>
      </section>
    </>
  )
}
