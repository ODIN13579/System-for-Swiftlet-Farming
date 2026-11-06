
import { useEffect, useMemo, useState } from 'react'
import {
  Wifi,
  Thermometer,
  Fan,
  Droplets,
  Camera,
  Search,
  RefreshCw,
  Cpu,
} from 'lucide-react'
import { api, type Device } from '../services/api'

const zoneIdValue = import.meta.env.VITE_ZONE_ID
const zoneId = zoneIdValue ? Number(zoneIdValue) : null

function getDeviceIcon(type: string) {
  const value = type.toLowerCase()

  if (value.includes('camera')) return Camera
  if (value.includes('fan') || value.includes('quạt')) return Fan
  if (value.includes('mist') || value.includes('phun')) return Droplets
  if (value.includes('sensor') || value.includes('esp32')) {
    return Thermometer
  }

  return Cpu
}

export default function Devices() {
  const [devices, setDevices] = useState<Device[]>([])
  const [search, setSearch] = useState('')
  const [filter, setFilter] = useState('all')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [totalElements, setTotalElements] = useState(0)

  async function loadDevices() {
    console.log('[Devices] Component đang tải')
    console.log('[Devices] API URL:', import.meta.env.VITE_API_BASE_URL)
    console.log('[Devices] Zone ID:', zoneId)

    if (zoneId === null || !Number.isSafeInteger(zoneId) || zoneId <= 0) {
      setError('Vui lòng cấu hình VITE_ZONE_ID bằng ID khu vực hợp lệ.')
      setDevices([])
      setTotalElements(0)
      return
    }

    setLoading(true)
    setError(null)

    try {
      console.log('[Devices] Đang gọi API danh sách thiết bị...')
      const result = await api.getDevices(zoneId)
      console.log('[Devices] API trả về:', result)

      setDevices(result.items)
      setTotalElements(result.page.totalElements)
    } catch (err) {
      console.error('[Devices] Lỗi API:', err)

      setError(
        err instanceof Error
          ? err.message
          : 'Không thể tải danh sách thiết bị.',
      )
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void loadDevices()
  }, [])

  const filteredDevices = useMemo(() => {
    return devices.filter((device) => {
      const matchesSearch =
        device.name.toLowerCase().includes(search.toLowerCase()) ||
        device.deviceId.toLowerCase().includes(search.toLowerCase()) ||
        String(device.zoneId).includes(search)

      const matchesStatus =
        filter === 'all' ||
        (filter === 'online' && device.online) ||
        (filter === 'offline' && !device.online)

      return matchesSearch && matchesStatus
    })
  }, [devices, search, filter])

  const onlineCount = devices.filter((device) => device.online).length
  const offlineCount = devices.length - onlineCount

  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">QUẢN LÝ NHÀ YẾN</div>
          <h1>Thiết bị</h1>
          <p>Theo dõi danh sách và tình trạng kết nối thiết bị.</p>
        </div>

        <div className="live-badge">
          <span className="status-dot" />
          {loading ? 'Đang tải dữ liệu' : 'Dữ liệu Backend'}
        </div>
      </div>

      {error && (
        <div role="alert" className="panel" style={{ padding: 16 }}>
          <p>{error}</p>
          <button className="devices-refresh" onClick={() => void loadDevices()}>
            <RefreshCw size={15} />
            Thử lại
          </button>
        </div>
      )}

      <div className="device-summary-grid">
        <article className="metric-card">
          <div className="metric-label">Tổng thiết bị trong trang</div>
          <div className="metric-value">{devices.length}</div>
          <div className="metric-note">
            Tổng số trong khu vực: {totalElements}
          </div>
        </article>

        <article className="metric-card">
          <div className="metric-label">Đang trực tuyến</div>
          <div className="metric-value">{onlineCount}</div>
          <div className="metric-note">Theo trạng thái Backend</div>
        </article>

        <article className="metric-card">
          <div className="metric-label">Mất kết nối</div>
          <div className="metric-value">{offlineCount}</div>
          <div className="metric-note">Theo trạng thái Backend</div>
        </article>
      </div>

      <section className="panel devices-panel">
        <div className="devices-toolbar">
          <div>
            <h2>Danh sách thiết bị</h2>
            <p>Thông tin thiết bị theo từng khu vực</p>
          </div>

          <button
            className="devices-refresh"
            onClick={() => {
              setSearch('')
              setFilter('all')
              void loadDevices()
            }}
            disabled={loading}
          >
            <RefreshCw size={15} />
            Làm mới
          </button>
        </div>

        <div className="devices-filters">
          <div className="devices-search">
            <Search size={17} />
            <input
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="Tìm tên, mã thiết bị hoặc ID khu vực..."
              aria-label="Tìm thiết bị"
            />
          </div>

          <select
            value={filter}
            onChange={(event) => setFilter(event.target.value)}
            aria-label="Lọc theo trạng thái"
          >
            <option value="all">Tất cả trạng thái</option>
            <option value="online">Online</option>
            <option value="offline">Offline</option>
          </select>
        </div>

        <div className="devices-table-wrap">
          <table className="devices-table">
            <thead>
              <tr>
                <th>Thiết bị</th>
                <th>Khu vực ID</th>
                <th>Trạng thái</th>
                <th>Cập nhật gần nhất</th>
              </tr>
            </thead>

            <tbody>
              {filteredDevices.map((device) => {
                const Icon = getDeviceIcon(
                  `${device.type} ${device.name}`,
                )

                return (
                  <tr key={device.id}>
                    <td>
                      <div className="device-table-name">
                        <div
                          className={`device-symbol ${
                            device.online ? 'device-online' : ''
                          }`}
                        >
                          <Icon size={18} />
                        </div>

                        <div>
                          <strong>{device.name}</strong>
                          <span>
                            {device.type} · {device.deviceId}
                          </span>
                        </div>
                      </div>
                    </td>

                    <td>{device.zoneId}</td>

                    <td>
                      <span
                        className={`device-status-pill ${
                          device.online ? 'is-online' : 'is-offline'
                        }`}
                      >
                        <span />
                        {device.online ? 'Online' : 'Offline'}
                      </span>
                    </td>

                    <td>
                      {device.lastSeenAt
                        ? new Date(device.lastSeenAt).toLocaleString('vi-VN')
                        : 'Chưa có dữ liệu'}
                    </td>
                  </tr>
                )
              })}

              {!loading && filteredDevices.length === 0 && (
                <tr>
                  <td colSpan={4} className="devices-empty">
                    {error
                      ? 'Không tải được dữ liệu.'
                      : 'Không tìm thấy thiết bị phù hợp.'}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>

        <div className="devices-footnote">
          <Wifi size={15} />
          Trạng thái lấy từ Backend; danh sách hiện tải tối đa 20 thiết bị.
        </div>
      </section>
    </>
  )
}
