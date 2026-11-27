
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  AlertTriangle,
  Bell,
  CheckCircle2,
  Clock3,
  Eye,
  Search,
  ShieldAlert,
  X,
  RefreshCw,
  BellRing,
  BellOff,
} from 'lucide-react'
import {
  api,
  type AlertHistory,
  type AlertLevel,
  type AlertRecord,
  type AlertStatus,
  type Zone,
} from '../services/api'
import { useAlertSession } from '../components/AlertSession'
import {
  requestNotificationPermission,
} from '../services/browserNotifications'
type Severity = 'Nghiêm trọng' | 'Cảnh báo'
type StatusLabel = 'Mới' | 'Đang xử lý' | 'Đã xử lý'

const severityClass: Record<Severity, string> = {
  'Nghiêm trọng': 'severity-critical',
  'Cảnh báo': 'severity-warning',
}

const statusClass: Record<StatusLabel, string> = {
  'Mới': 'alert-status-new',
  'Đang xử lý': 'alert-status-progress',
  'Đã xử lý': 'alert-status-done',
}

function severityLabel(level: AlertLevel): Severity {
  return level === 'CRITICAL' ? 'Nghiêm trọng' : 'Cảnh báo'
}

function statusLabel(status: AlertStatus): StatusLabel {
  switch (status) {
    case 'IN_PROGRESS':
      return 'Đang xử lý'
    case 'RESOLVED':
      return 'Đã xử lý'
    default:
      return 'Mới'
  }
}

function eventTitle(type: string, message: string): string {
  if (message.trim()) return message
  return type.replaceAll('_', ' ')
}

function formatDate(value: string | null | undefined): string {
  if (!value) return '—'

  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value

  return date.toLocaleString('vi-VN', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

function errorMessage(error: unknown): string {
  return error instanceof Error
    ? error.message
    : 'Đã xảy ra lỗi không xác định.'
}

export default function Alerts({ alertId }: { alertId?: number }) {
  const { houses, houseId, selectHouse: setHouseId, status: wsStatus, revision, loadingHouses, houseError } = useAlertSession()
  const [zones, setZones] = useState<Zone[]>([])
  const [zoneId, setZoneId] = useState<number | ''>('')

  const [alerts, setAlerts] = useState<AlertRecord[]>([])
  const [search, setSearch] = useState('')
  const [severityFilter, setSeverityFilter] = useState<'all' | AlertLevel>('all')
  const [statusFilter, setStatusFilter] = useState<'all' | AlertStatus>('all')

  const [selectedAlert, setSelectedAlert] = useState<AlertRecord | null>(null)
  const [history, setHistory] = useState<AlertHistory[]>([])
  const [snapshotUrl, setSnapshotUrl] = useState<string | null>(null)

  const [loadingAlerts, setLoadingAlerts] = useState(false)
  const [loadingDetail, setLoadingDetail] = useState(false)
  const [savingStatus, setSavingStatus] = useState(false)
  const [pageError, setPageError] = useState('')
  const [detailError, setDetailError] = useState('')
  const [snapshotError, setSnapshotError] = useState('')
  const [notificationPermission, setNotificationPermission] =
    useState<NotificationPermission | 'unsupported'>(() => {
      if (!('Notification' in window)) return 'unsupported'
      return Notification.permission
    })

  const [notificationsEnabled, setNotificationsEnabled] = useState(
    () => localStorage.getItem('swiftletcare-notifications-enabled') === 'true',
  )

  async function toggleBrowserNotifications() {
    if (notificationsEnabled) {
      localStorage.setItem('swiftletcare-notifications-enabled', 'false')
      setNotificationsEnabled(false)
      return
    }

    const permission = await requestNotificationPermission()
    setNotificationPermission(permission)

    if (permission === 'granted') {
      localStorage.setItem('swiftletcare-notifications-enabled', 'true')
      setNotificationsEnabled(true)
    }
  }
  const zoneNames = useMemo(
    () => new Map(zones.map((zone) => [zone.id, zone.name])),
    [zones],
  )

  const listRequest = useRef<AbortController | null>(null)
  const refreshAlerts = useCallback(async () => {
    listRequest.current?.abort()
    const request = new AbortController()
    listRequest.current = request
    if (houseId === '') {
      setAlerts([])
      return
    }

    setLoadingAlerts(true)
    setPageError('')

    try {
      const result = await api.getAlerts(houseId, {
        zoneId: zoneId === '' ? undefined : zoneId,
        level: severityFilter === 'all' ? undefined : severityFilter,
        status: statusFilter === 'all' ? undefined : statusFilter,
        page: 0,
        size: 100,
      }, request.signal)

      if (!request.signal.aborted) setAlerts(result.items)
    } catch (error) {
      if (!request.signal.aborted) { setAlerts([]); setPageError(errorMessage(error)) }
    } finally {
      if (!request.signal.aborted) setLoadingAlerts(false)
    }
  }, [houseId, zoneId, severityFilter, statusFilter])

  // Tải danh sách nhà yến khi mở trang.
  useEffect(() => {
    if (houseId === '') {
      setZones([])
      setZoneId('')
      return
    }

    let cancelled = false

    async function loadZones() {
      try {
        const result = await api.getZones(Number(houseId), 0, 100)
        if (!cancelled) {
          setZones(result.items.filter((zone) => zone.active))
        }
      } catch (error) {
        if (!cancelled) {
          setZones([])
          setPageError(errorMessage(error))
        }
      }
    }

    setZoneId('')
    void loadZones()

    return () => {
      cancelled = true
    }
  }, [houseId])

  // Lấy dữ liệu thật từ API khi nhà yến hoặc bộ lọc thay đổi.
  useEffect(() => {
    void refreshAlerts()
    return () => listRequest.current?.abort()
  }, [refreshAlerts, revision])

  // Nhận sự kiện realtime theo nhà yến.
  const detailRequest = useRef<AbortController | null>(null)
  const loadDetail = useCallback(async (id: number) => {
    detailRequest.current?.abort()
    const request = new AbortController()
    detailRequest.current = request
    setSelectedAlert(null)
    setLoadingDetail(true)
    setDetailError('')

    try {
      const [detail, events] = await Promise.all([
        api.getAlert(id, request.signal),
        api.getAlertHistory(id, request.signal),
      ])

      if (request.signal.aborted) return
      setSelectedAlert(detail)
      setHistory(events)
    } catch (error) {
      if (!request.signal.aborted) setDetailError(errorMessage(error))
    } finally {
      if (!request.signal.aborted) setLoadingDetail(false)
    }
  }, [])

  // Tải ảnh bằng chứng riêng để không làm hỏng cửa sổ chi tiết nếu thiếu ảnh.
  useEffect(() => {
    if (alertId !== undefined) void loadDetail(alertId)
    return () => detailRequest.current?.abort()
  }, [alertId, loadDetail])

  useEffect(() => {
    let cancelled = false
    let objectUrl: string | null = null

    setSnapshotUrl(null)
    setSnapshotError('')

    if (!selectedAlert?.snapshotId) return

    async function loadSnapshot(snapshotId: number) {
      try {
        const blob = await api.getSnapshotBlob(snapshotId)
        if (cancelled) return

        objectUrl = URL.createObjectURL(blob)
        setSnapshotUrl(objectUrl)
      } catch (error) {
        if (!cancelled) {
          setSnapshotError(errorMessage(error))
        }
      }
    }

    void loadSnapshot(selectedAlert.snapshotId)

    return () => {
      cancelled = true
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [selectedAlert?.snapshotId])

  const filteredAlerts = useMemo(() => {
    const keyword = search.trim().toLocaleLowerCase('vi-VN')

    return alerts.filter((alert) => {
      const title = eventTitle(alert.type, alert.message)
      const zoneName = zoneNames.get(alert.zoneId) ?? `Khu vực #${alert.zoneId}`

      return (
        title.toLocaleLowerCase('vi-VN').includes(keyword) ||
        alert.message.toLocaleLowerCase('vi-VN').includes(keyword) ||
        zoneName.toLocaleLowerCase('vi-VN').includes(keyword) ||
        String(alert.id).includes(keyword)
      )
    })
  }, [alerts, search, zoneNames])

  const criticalCount = alerts.filter(
    (alert) => alert.level === 'CRITICAL' && alert.status !== 'RESOLVED',
  ).length

  const newCount = alerts.filter((alert) => alert.status === 'NEW').length

  const progressCount = alerts.filter(
    (alert) => alert.status === 'IN_PROGRESS',
  ).length

  async function updateStatus(nextStatus: AlertStatus) {
    if (!selectedAlert || savingStatus) return

    const currentStatus = selectedAlert.status
    const isValidTransition =
      (currentStatus === 'NEW' && nextStatus === 'IN_PROGRESS') ||
      (currentStatus === 'IN_PROGRESS' && nextStatus === 'RESOLVED')

    if (!isValidTransition) return

    setSavingStatus(true)
    setDetailError('')

    try {
      await api.updateAlertStatus(selectedAlert.id, nextStatus)
      await Promise.all([
        refreshAlerts(),
        loadDetail(selectedAlert.id),
      ])
    } catch (error) {
      setDetailError(errorMessage(error))
    } finally {
      setSavingStatus(false)
    }
  }

  const selectedZoneName = selectedAlert
    ? zoneNames.get(selectedAlert.zoneId) ?? `Khu vực #${selectedAlert.zoneId}`
    : ''

  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">GIÁM SÁT & PHẢN HỒI</div>
          <h1>Cảnh báo & AI Events</h1>
          <p>Theo dõi sự cố và cập nhật tiến độ xử lý tại nhà yến.</p>
        </div>

        <div className="live-badge" title="Trạng thái kết nối cập nhật cảnh báo">
          <span className="status-dot" />
          {wsStatus === 'connected'
            ? 'Realtime đang kết nối'
            : wsStatus === 'error'
              ? 'Đang thử kết nối lại'
              : 'Đang đồng bộ dữ liệu'}
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
          <button
            type="button"
            className="alert-detail-button"
            onClick={() => void toggleBrowserNotifications()}
            disabled={notificationPermission === 'unsupported'}
          >
            {notificationsEnabled ? (
              <BellRing size={16} />
            ) : (
              <BellOff size={16} />
            )}

            {notificationsEnabled
              ? 'Tắt thông báo'
              : notificationPermission === 'denied'
                ? 'Thông báo bị chặn'
                : notificationPermission === 'unsupported'
                  ? 'Trình duyệt không hỗ trợ'
                  : 'Bật thông báo'}
          </button>
          <button
            className="alert-detail-button"
            onClick={() => void refreshAlerts()}
            disabled={loadingAlerts}
          >
            <RefreshCw size={15} />
            Làm mới
          </button>
        </div>

        <div className="alerts-filters">
          <select
            value={houseId}
            onChange={(event) => {
              setSelectedAlert(null)
              detailRequest.current?.abort()
              setAlerts([])
              setHouseId(event.target.value ? Number(event.target.value) : '')
            }}
            aria-label="Chọn nhà yến"
            disabled={loadingHouses}
          >
            <option value="">
              {loadingHouses ? 'Đang tải nhà yến...' : 'Chọn nhà yến'}
            </option>
            {houses.map((house) => (
              <option key={house.id} value={house.id}>
                {house.name}
              </option>
            ))}
          </select>

          <select
            value={zoneId}
            onChange={(event) =>
              setZoneId(event.target.value ? Number(event.target.value) : '')
            }
            aria-label="Lọc khu vực"
            disabled={houseId === ''}
          >
            <option value="">Tất cả khu vực</option>
            {zones.map((zone) => (
              <option key={zone.id} value={zone.id}>
                {zone.name}
              </option>
            ))}
          </select>

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
            onChange={(event) =>
              setSeverityFilter(event.target.value as 'all' | AlertLevel)
            }
            aria-label="Lọc mức độ"
          >
            <option value="all">Tất cả mức độ</option>
            <option value="CRITICAL">Nghiêm trọng</option>
            <option value="WARNING">Cảnh báo</option>
          </select>

          <select
            value={statusFilter}
            onChange={(event) =>
              setStatusFilter(event.target.value as 'all' | AlertStatus)
            }
            aria-label="Lọc trạng thái"
          >
            <option value="all">Tất cả trạng thái</option>
            <option value="NEW">Mới</option>
            <option value="IN_PROGRESS">Đang xử lý</option>
            <option value="RESOLVED">Đã xử lý</option>
          </select>
        </div>

        {(pageError || houseError) && (
          <div className="alerts-empty" role="alert">
            Không tải được dữ liệu: {pageError || houseError}
          </div>
        )}

        <div className="alerts-list">
          {loadingAlerts && alerts.length === 0 && (
            <div className="alerts-empty">Đang tải cảnh báo từ hệ thống...</div>
          )}

          {!loadingAlerts &&
            !pageError &&
            houseId === '' &&
            !loadingHouses && (
              <div className="alerts-empty">
                Chưa có nhà yến đang hoạt động hoặc tài khoản không có quyền xem.
              </div>
            )}

          {filteredAlerts.map((alert) => {
            const severity = severityLabel(alert.level)
            const status = statusLabel(alert.status)
            const zoneName =
              zoneNames.get(alert.zoneId) ?? `Khu vực #${alert.zoneId}`

            return (
              <article className="alert-item" key={alert.id} data-alert-id={alert.id}>
                <div className={`alert-item-icon ${severityClass[severity]}`}>
                  <AlertTriangle size={19} />
                </div>

                <div className="alert-item-main">
                  <div className="alert-item-title-row">
                    <h3>{eventTitle(alert.type, alert.message)}</h3>
                    <span className={`severity-tag ${severityClass[severity]}`}>
                      {severity}
                    </span>
                  </div>

                  <p>{alert.message || alert.type}</p>

                  <div className="alert-item-meta">
                    <span>{alert.type}</span>
                    <span>{zoneName}</span>
                    <span>{formatDate(alert.createdAt)}</span>
                  </div>

                  <div className="alert-item-bottom">
                    <span className={`alert-status ${statusClass[status]}`}>
                      {status}
                    </span>

                    <button
                      className="alert-detail-button"
                      onClick={() => void loadDetail(alert.id)}
                    >
                      <Eye size={15} />
                      Xem chi tiết
                    </button>
                  </div>
                </div>
              </article>
            )
          })}

          {!loadingAlerts && filteredAlerts.length === 0 && houseId !== '' && (
            <div className="alerts-empty">
              Không tìm thấy cảnh báo phù hợp. Hãy thử thay đổi từ khóa hoặc bộ lọc.
            </div>
          )}
        </div>

        <div className="devices-footnote">
          <Bell size={15} />
          Dữ liệu được lấy từ backend. Realtime cần WebSocket được xác thực và backend đang hoạt động.
        </div>
      </section>

      {detailError && !selectedAlert && <p role="alert">{detailError}</p>}
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
                <div className="eyebrow">
                  CHI TIẾT SỰ KIỆN #{selectedAlert.id}
                </div>
                <h2 id="alert-modal-title">
                  {eventTitle(selectedAlert.type, selectedAlert.message)}
                </h2>
              </div>

              <button
                className="alert-modal-close"
                onClick={() => setSelectedAlert(null)}
                aria-label="Đóng chi tiết"
              >
                <X size={20} />
              </button>
            </div>

            {loadingDetail && <p>Đang tải chi tiết và lịch sử...</p>}

            {detailError && (
              <div className="alerts-empty" role="alert">
                {detailError}
              </div>
            )}

            <div className="alert-modal-badges">
              <span
                className={`severity-tag ${
                  severityClass[severityLabel(selectedAlert.level)]
                }`}
              >
                {severityLabel(selectedAlert.level)}
              </span>
              <span
                className={`alert-status ${
                  statusClass[statusLabel(selectedAlert.status)]
                }`}
              >
                {statusLabel(selectedAlert.status)}
              </span>
            </div>

            <div className="alert-detail-grid">
              <div>
                <span>Loại sự kiện</span>
                <strong>{selectedAlert.type}</strong>
              </div>
              <div>
                <span>Khu vực</span>
                <strong>{selectedZoneName}</strong>
              </div>
              <div>
                <span>Thiết bị ID</span>
                <strong>{selectedAlert.deviceId ?? '—'}</strong>
              </div>
              <div>
                <span>Thời gian ghi nhận</span>
                <strong>{formatDate(selectedAlert.createdAt)}</strong>
              </div>
              <div>
                <span>Thời gian tiếp nhận</span>
                <strong>{formatDate(selectedAlert.handledAt)}</strong>
              </div>
            </div>

            <div className="alert-detail-description">
              <h3>Mô tả sự kiện</h3>
              <p>{selectedAlert.message || 'Backend không cung cấp mô tả.'}</p>
            </div>

            <div className="alert-detail-description">
              <h3>Ảnh bằng chứng</h3>
              {snapshotUrl ? (
                <img
                  src={snapshotUrl}
                  alt={`Ảnh bằng chứng của cảnh báo ${selectedAlert.id}`}
                  style={{
                    display: 'block',
                    maxWidth: '100%',
                    maxHeight: 360,
                    objectFit: 'contain',
                    borderRadius: 8,
                  }}
                />
              ) : snapshotError ? (
                <p>{snapshotError}</p>
              ) : selectedAlert.snapshotId ? (
                <p>Đang tải ảnh bằng chứng...</p>
              ) : (
                <p>Sự kiện này không có ảnh bằng chứng được liên kết.</p>
              )}
            </div>

            <div className="alert-detail-description">
              <h3>Lịch sử xử lý</h3>
              {history.length === 0 ? (
                <p>Chưa có bản ghi lịch sử hoặc API không trả về dữ liệu.</p>
              ) : (
                <ul>
                  {history.map((item, index) => (
                    <li key={`${item.id}-${index}`}>
                      {formatDate(item.changedAt)} —{' '}
                      {item.fromStatus
                        ? statusLabel(item.fromStatus)
                        : 'Khởi tạo'}{' '}
                      → {statusLabel(item.toStatus)}
                      {item.note ? ` — ${item.note}` : ''}
                    </li>
                  ))}
                </ul>
              )}
            </div>

            <div className="alert-modal-actions">
              {selectedAlert.status === 'NEW' && (
                <button
                  className="alert-action-secondary"
                  disabled={savingStatus}
                  onClick={() => void updateStatus('IN_PROGRESS')}
                >
                  <Clock3 size={15} />
                  Tiếp nhận xử lý
                </button>
              )}

              {selectedAlert.status === 'IN_PROGRESS' && (
                <button
                  className="alert-action-primary"
                  disabled={savingStatus}
                  onClick={() => void updateStatus('RESOLVED')}
                >
                  <CheckCircle2 size={15} />
                  Đánh dấu đã xử lý
                </button>
              )}
            </div>

            <p className="alert-modal-note">
              {savingStatus
                ? 'Đang lưu trạng thái lên backend...'
                : 'Trạng thái được cập nhật qua API và chỉ chuyển theo quy trình Mới → Đang xử lý → Đã xử lý.'}
            </p>
          </section>
        </div>
      )}
    </>
  )
}
