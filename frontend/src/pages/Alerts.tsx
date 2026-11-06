
import { useMemo, useState } from 'react'
import {
  AlertTriangle,
  Bell,
  CheckCircle2,
  Clock3,
  Eye,
  Search,
  ShieldAlert,
  X,
} from 'lucide-react'

type Severity = 'Nghiêm trọng' | 'Cảnh báo' | 'Thông tin'
type AlertStatus = 'Mới' | 'Đang xử lý' | 'Đã xử lý'

type AlertItem = {
  id: number
  title: string
  description: string
  type: string
  severity: Severity
  status: AlertStatus
  zone: string
  time: string
  detail: string
}

const initialAlerts: AlertItem[] = [
  {
    id: 1,
    title: 'Nhiệt độ vượt ngưỡng',
    description: 'Nhiệt độ trong nhà yến đang cao hơn mức cài đặt.',
    type: 'Môi trường',
    severity: 'Nghiêm trọng',
    status: 'Mới',
    zone: 'Khu vực A',
    time: '19:25:10',
    detail:
      'Cảm biến mô phỏng ghi nhận nhiệt độ 33.2°C. Cần kiểm tra thông gió và thiết bị làm mát.',
  },
  {
    id: 2,
    title: 'Camera AI mất kết nối',
    description: 'Camera tại khu vực giám sát không phản hồi.',
    type: 'Thiết bị',
    severity: 'Cảnh báo',
    status: 'Đang xử lý',
    zone: 'Khu vực A',
    time: '19:18:42',
    detail:
      'Thiết bị camera đang ở trạng thái Offline trong dữ liệu minh họa. Hãy kiểm tra nguồn điện và kết nối mạng.',
  },
  {
    id: 3,
    title: 'Độ ẩm nằm ngoài ngưỡng',
    description: 'Độ ẩm hiện tại thấp hơn ngưỡng mục tiêu.',
    type: 'Môi trường',
    severity: 'Cảnh báo',
    status: 'Mới',
    zone: 'Khu vực B',
    time: '19:10:05',
    detail:
      'Độ ẩm mô phỏng là 68%. Cần kiểm tra cảm biến và hệ thống phun sương trước khi điều chỉnh thiết bị.',
  },
  {
    id: 4,
    title: 'Phát hiện chuyển động bất thường',
    description: 'Camera AI ghi nhận một sự kiện cần kiểm tra.',
    type: 'AI Event',
    severity: 'Nghiêm trọng',
    status: 'Mới',
    zone: 'Khu vực C',
    time: '18:55:30',
    detail:
      'Đây là sự kiện AI minh họa, chưa có ảnh bằng chứng thực tế. Cần kiểm tra camera và xác minh sự kiện.',
  },
  {
    id: 5,
    title: 'Thiết bị hoạt động bình thường',
    description: 'Bộ điều khiển đã gửi dữ liệu mô phỏng thành công.',
    type: 'Thiết bị',
    severity: 'Thông tin',
    status: 'Đã xử lý',
    zone: 'Khu vực A',
    time: '18:40:12',
    detail:
      'Thông báo minh họa xác nhận thiết bị đã gửi dữ liệu. Trạng thái này không đại diện cho kết nối thật.',
  },
]

const severityClass: Record<Severity, string> = {
  'Nghiêm trọng': 'severity-critical',
  'Cảnh báo': 'severity-warning',
  'Thông tin': 'severity-info',
}

const statusClass: Record<AlertStatus, string> = {
  'Mới': 'alert-status-new',
  'Đang xử lý': 'alert-status-progress',
  'Đã xử lý': 'alert-status-done',
}

export default function Alerts() {
  const [alerts, setAlerts] = useState(initialAlerts)
  const [search, setSearch] = useState('')
  const [severityFilter, setSeverityFilter] = useState('all')
  const [statusFilter, setStatusFilter] = useState('all')
  const [selectedAlert, setSelectedAlert] = useState<AlertItem | null>(null)

  const filteredAlerts = useMemo(() => {
    return alerts.filter((alert) => {
      const keyword = search.toLowerCase()
      const matchesSearch =
        alert.title.toLowerCase().includes(keyword) ||
        alert.description.toLowerCase().includes(keyword) ||
        alert.zone.toLowerCase().includes(keyword)

      const matchesSeverity =
        severityFilter === 'all' || alert.severity === severityFilter

      const matchesStatus =
        statusFilter === 'all' || alert.status === statusFilter

      return matchesSearch && matchesSeverity && matchesStatus
    })
  }, [alerts, search, severityFilter, statusFilter])

  const criticalCount = alerts.filter(
    (alert) => alert.severity === 'Nghiêm trọng' && alert.status !== 'Đã xử lý',
  ).length

  const newCount = alerts.filter((alert) => alert.status === 'Mới').length

  const progressCount = alerts.filter(
    (alert) => alert.status === 'Đang xử lý',
  ).length

  function updateStatus(id: number, status: AlertStatus) {
    setAlerts((current) =>
      current.map((alert) => (alert.id === id ? { ...alert, status } : alert)),
    )

    setSelectedAlert((current) =>
      current && current.id === id ? { ...current, status } : current,
    )
  }

  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">GIÁM SÁT & PHẢN HỒI</div>
          <h1>Cảnh báo & AI Events</h1>
          <p>Theo dõi sự cố và cập nhật tiến độ xử lý tại nhà yến.</p>
        </div>

        <div className="live-badge">
          <span className="status-dot" />
          Dữ liệu mô phỏng
        </div>
      </div>

      <div className="alert-summary-grid">
        <article className="metric-card">
          <div className="alert-summary-top">
            <div className="metric-label">Cảnh báo nghiêm trọng</div>
            <ShieldAlert size={19} />
          </div>
          <div className="metric-value">{criticalCount}</div>
          <div className="metric-note">Chưa xử lý xong</div>
        </article>

        <article className="metric-card">
          <div className="alert-summary-top">
            <div className="metric-label">Cảnh báo mới</div>
            <Bell size={19} />
          </div>
          <div className="metric-value">{newCount}</div>
          <div className="metric-note">Đang chờ tiếp nhận</div>
        </article>

        <article className="metric-card">
          <div className="alert-summary-top">
            <div className="metric-label">Đang xử lý</div>
            <Clock3 size={19} />
          </div>
          <div className="metric-value">{progressCount}</div>
          <div className="metric-note">Chưa hoàn tất</div>
        </article>
      </div>

      <section className="panel alerts-panel">
        <div className="alerts-toolbar">
          <div>
            <h2>Danh sách sự kiện</h2>
            <p>{filteredAlerts.length} sự kiện phù hợp với bộ lọc</p>
          </div>
        </div>

        <div className="alerts-filters">
          <div className="alerts-search">
            <Search size={17} />
            <input
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="Tìm cảnh báo hoặc khu vực..."
              aria-label="Tìm cảnh báo"
            />
          </div>

          <select
            value={severityFilter}
            onChange={(event) => setSeverityFilter(event.target.value)}
            aria-label="Lọc mức độ"
          >
            <option value="all">Tất cả mức độ</option>
            <option value="Nghiêm trọng">Nghiêm trọng</option>
            <option value="Cảnh báo">Cảnh báo</option>
            <option value="Thông tin">Thông tin</option>
          </select>

          <select
            value={statusFilter}
            onChange={(event) => setStatusFilter(event.target.value)}
            aria-label="Lọc trạng thái"
          >
            <option value="all">Tất cả trạng thái</option>
            <option value="Mới">Mới</option>
            <option value="Đang xử lý">Đang xử lý</option>
            <option value="Đã xử lý">Đã xử lý</option>
          </select>
        </div>

        <div className="alerts-list">
          {filteredAlerts.map((alert) => (
            <article className="alert-item" key={alert.id}>
              <div className={`alert-item-icon ${severityClass[alert.severity]}`}>
                {alert.severity === 'Thông tin' ? (
                  <Bell size={19} />
                ) : (
                  <AlertTriangle size={19} />
                )}
              </div>

              <div className="alert-item-main">
                <div className="alert-item-title-row">
                  <h3>{alert.title}</h3>
                  <span className={`severity-tag ${severityClass[alert.severity]}`}>
                    {alert.severity}
                  </span>
                </div>

                <p>{alert.description}</p>

                <div className="alert-item-meta">
                  <span>{alert.type}</span>
                  <span>{alert.zone}</span>
                  <span>{alert.time}</span>
                </div>

                <div className="alert-item-bottom">
                  <span className={`alert-status ${statusClass[alert.status]}`}>
                    {alert.status}
                  </span>

                  <button
                    className="alert-detail-button"
                    onClick={() => setSelectedAlert(alert)}
                  >
                    <Eye size={15} />
                    Xem chi tiết
                  </button>
                </div>
              </div>
            </article>
          ))}

          {filteredAlerts.length === 0 && (
            <div className="alerts-empty">
              Không tìm thấy sự kiện phù hợp. Hãy thử thay đổi từ khóa hoặc bộ lọc.
            </div>
          )}
        </div>

        <div className="devices-footnote">
          <Bell size={15} />
          Cảnh báo mẫu chỉ phục vụ kiểm thử giao diện; chưa phải dữ liệu cảm biến hoặc AI thực tế.
        </div>
      </section>

      {selectedAlert && (
        <div
          className="alert-modal-backdrop"
          onClick={() => setSelectedAlert(null)}
        >
          <section
            className="alert-modal"
            role="dialog"
            aria-modal="true"
            aria-labelledby="alert-modal-title"
            onClick={(event) => event.stopPropagation()}
          >
            <div className="alert-modal-header">
              <div>
                <div className="eyebrow">CHI TIẾT SỰ KIỆN #{selectedAlert.id}</div>
                <h2 id="alert-modal-title">{selectedAlert.title}</h2>
              </div>

              <button
                className="alert-modal-close"
                onClick={() => setSelectedAlert(null)}
                aria-label="Đóng chi tiết"
              >
                <X size={20} />
              </button>
            </div>

            <div className="alert-modal-badges">
              <span className={`severity-tag ${severityClass[selectedAlert.severity]}`}>
                {selectedAlert.severity}
              </span>
              <span className={`alert-status ${statusClass[selectedAlert.status]}`}>
                {selectedAlert.status}
              </span>
            </div>

            <div className="alert-detail-grid">
              <div>
                <span>Loại sự kiện</span>
                <strong>{selectedAlert.type}</strong>
              </div>
              <div>
                <span>Khu vực</span>
                <strong>{selectedAlert.zone}</strong>
              </div>
              <div>
                <span>Thời gian ghi nhận</span>
                <strong>{selectedAlert.time}</strong>
              </div>
            </div>

            <div className="alert-detail-description">
              <h3>Mô tả & hướng kiểm tra</h3>
              <p>{selectedAlert.detail}</p>
            </div>

            <div className="alert-modal-actions">
              {selectedAlert.status !== 'Đang xử lý' && (
                <button
                  className="alert-action-secondary"
                  onClick={() => updateStatus(selectedAlert.id, 'Đang xử lý')}
                >
                  <Clock3 size={15} />
                  Đang xử lý
                </button>
              )}

              {selectedAlert.status !== 'Đã xử lý' && (
                <button
                  className="alert-action-primary"
                  onClick={() => updateStatus(selectedAlert.id, 'Đã xử lý')}
                >
                  <CheckCircle2 size={15} />
                  Đánh dấu đã xử lý
                </button>
              )}

              {selectedAlert.status !== 'Mới' && (
                <button
                  className="alert-action-secondary"
                  onClick={() => updateStatus(selectedAlert.id, 'Mới')}
                >
                  Đặt lại trạng thái
                </button>
              )}
            </div>

            <p className="alert-modal-note">
              Thay đổi trạng thái hiện chỉ lưu trong bộ nhớ của trang. Khi tải lại
              trình duyệt, dữ liệu sẽ trở về mẫu ban đầu.
            </p>
          </section>
        </div>
      )}
    </>
  )
}
