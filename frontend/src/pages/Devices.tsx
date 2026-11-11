
import { useEffect, useMemo, useState, type FormEvent } from 'react'
import {
  Wifi,
  Thermometer,
  Fan,
  Droplets,
  Camera,
  Search,
  RefreshCw,
  Cpu,
  Home,
  Layers,
  Plus,
  Pencil,
  X,
} from 'lucide-react'
import {
  api,
  type Device,
  type House,
  type Zone,
} from '../services/api'

type DeviceFormMode = 'create' | 'edit' | null

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
  const [houses, setHouses] = useState<House[]>([])
  const [zones, setZones] = useState<Zone[]>([])
  const [devices, setDevices] = useState<Device[]>([])

  const [selectedHouseId, setSelectedHouseId] = useState('')
  const [selectedZoneId, setSelectedZoneId] = useState('')

  const [search, setSearch] = useState('')
  const [filter, setFilter] = useState('all')

  const [loadingHouses, setLoadingHouses] = useState(true)
  const [loadingZones, setLoadingZones] = useState(false)
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)

  const [error, setError] = useState<string | null>(null)
  const [formError, setFormError] = useState('')
  const [totalElements, setTotalElements] = useState(0)
  const [refreshKey, setRefreshKey] = useState(0)

  const [formMode, setFormMode] = useState<DeviceFormMode>(null)
  const [editingDevice, setEditingDevice] = useState<Device | null>(null)

  const [deviceId, setDeviceId] = useState('')
  const [deviceName, setDeviceName] = useState('')
  const [deviceType, setDeviceType] = useState<'ESP32' | 'CAMERA'>('ESP32')
  const [enabled, setEnabled] = useState(true)

  // Tải các nhà yến mà tài khoản được phép truy cập.
  useEffect(() => {
    let cancelled = false

    async function loadHouses() {
      setLoadingHouses(true)
      setError(null)

      try {
        const result = await api.getHouses()

        if (cancelled) return

        setHouses(result.items)

        setSelectedHouseId((currentId) => {
          const stillAvailable = result.items.some(
            (house) => String(house.id) === currentId,
          )

          if (stillAvailable) return currentId

          return result.items.length > 0
            ? String(result.items[0].id)
            : ''
        })
      } catch (err) {
        if (!cancelled) {
          setError(
            err instanceof Error
              ? err.message
              : 'Không thể tải danh sách nhà yến.',
          )
        }
      } finally {
        if (!cancelled) setLoadingHouses(false)
      }
    }

    void loadHouses()

    return () => {
      cancelled = true
    }
  }, [])

  // Tải khu vực theo nhà yến đang chọn.
  useEffect(() => {
    let cancelled = false

    setZones([])
    setSelectedZoneId('')
    setDevices([])
    setTotalElements(0)

    if (!selectedHouseId) {
      setLoadingZones(false)
      return
    }

    async function loadZones() {
      setLoadingZones(true)
      setError(null)

      try {
        const result = await api.getZones(Number(selectedHouseId))

        if (cancelled) return

        setZones(result.items)

        setSelectedZoneId(
          result.items.length > 0 ? String(result.items[0].id) : '',
        )
      } catch (err) {
        if (!cancelled) {
          setError(
            err instanceof Error
              ? err.message
              : 'Không thể tải danh sách khu vực.',
          )
        }
      } finally {
        if (!cancelled) setLoadingZones(false)
      }
    }

    void loadZones()

    return () => {
      cancelled = true
    }
  }, [selectedHouseId])

  // Tải thiết bị theo khu vực. refreshKey giúp làm mới bằng cùng một effect.
  useEffect(() => {
    let cancelled = false

    if (!selectedZoneId) {
      setDevices([])
      setTotalElements(0)
      setLoading(false)
      return
    }

    async function loadDevices() {
      setLoading(true)
      setError(null)

      try {
        const result = await api.getDevices(Number(selectedZoneId))

        if (cancelled) return

        setDevices(result.items)
        setTotalElements(result.page.totalElements)
      } catch (err) {
        if (cancelled) return

        setDevices([])
        setTotalElements(0)
        setError(
          err instanceof Error
            ? err.message
            : 'Không thể tải danh sách thiết bị.',
        )
      } finally {
        if (!cancelled) setLoading(false)
      }
    }

    void loadDevices()

    return () => {
      cancelled = true
    }
  }, [selectedZoneId, refreshKey])

  const filteredDevices = useMemo(() => {
    const keyword = search.trim().toLowerCase()

    return devices.filter((device) => {
      const matchesSearch =
        device.name.toLowerCase().includes(keyword) ||
        device.deviceId.toLowerCase().includes(keyword) ||
        String(device.zoneId).includes(keyword)

      const matchesStatus =
        filter === 'all' ||
        (filter === 'online' && device.online) ||
        (filter === 'offline' && !device.online)

      return matchesSearch && matchesStatus
    })
  }, [devices, search, filter])

  const onlineCount = devices.filter((device) => device.online).length
  const offlineCount = devices.length - onlineCount

  function refreshDevices() {
    if (!selectedZoneId || loading) return

    setSearch('')
    setFilter('all')
    setRefreshKey((current) => current + 1)
  }

  function resetForm() {
    setFormMode(null)
    setEditingDevice(null)
    setFormError('')
    setDeviceId('')
    setDeviceName('')
    setDeviceType('ESP32')
    setEnabled(true)
  }

  function openCreateForm() {
    if (!selectedZoneId) {
      setError('Vui lòng chọn nhà yến và khu vực trước.')
      return
    }

    setError(null)
    resetForm()
    setFormMode('create')
  }

  function openEditForm(device: Device) {
    setError(null)
    setEditingDevice(device)
    setDeviceId(device.deviceId)
    setDeviceName(device.name)
    setEnabled(device.enabled)
    setFormError('')
    setFormMode('edit')
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setFormError('')

    if (!deviceName.trim()) {
      setFormError('Vui lòng nhập tên thiết bị.')
      return
    }

    if (formMode === 'create' && !deviceId.trim()) {
      setFormError('Vui lòng nhập mã thiết bị.')
      return
    }

    if (formMode === 'create' && !selectedZoneId) {
      setFormError('Vui lòng chọn khu vực.')
      return
    }

    setSaving(true)

    try {
      if (formMode === 'create') {
        await api.createDevice(Number(selectedZoneId), {
          deviceId: deviceId.trim(),
          name: deviceName.trim(),
          type: deviceType,
          enabled,
        })
      } else if (formMode === 'edit' && editingDevice) {
        await api.updateDevice(editingDevice.id, {
          name: deviceName.trim(),
          enabled,
        })
      } else {
        return
      }

      resetForm()
      setRefreshKey((current) => current + 1)
    } catch (err) {
      setFormError(
        err instanceof Error
          ? err.message
          : 'Không thể lưu thông tin thiết bị.',
      )
    } finally {
      setSaving(false)
    }
  }

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

      <section className="panel" style={{ padding: 16, marginBottom: 20 }}>
        <div className="devices-filters">
          <div>
            <label
              htmlFor="devices-house-select"
              style={{ display: 'block', marginBottom: 8 }}
            >
              <Home size={15} style={{ verticalAlign: 'middle' }} /> Nhà yến
            </label>

            <select
              id="devices-house-select"
              value={selectedHouseId}
              disabled={loadingHouses || houses.length === 0}
              onChange={(event) => {
                setSelectedHouseId(event.target.value)
                setSearch('')
                setFilter('all')
                resetForm()
              }}
            >
              {loadingHouses && (
                <option value="">Đang tải nhà yến...</option>
              )}

              {!loadingHouses && houses.length === 0 && (
                <option value="">Không có nhà yến được phép truy cập</option>
              )}

              {houses.map((house) => (
                <option key={house.id} value={house.id}>
                  {house.name}
                </option>
              ))}
            </select>
          </div>

          <div>
            <label
              htmlFor="devices-zone-select"
              style={{ display: 'block', marginBottom: 8 }}
            >
              <Layers size={15} style={{ verticalAlign: 'middle' }} /> Khu vực
            </label>

            <select
              id="devices-zone-select"
              value={selectedZoneId}
              disabled={loadingZones || !selectedHouseId || zones.length === 0}
              onChange={(event) => {
                setSelectedZoneId(event.target.value)
                setSearch('')
                setFilter('all')
                resetForm()
              }}
            >
              {loadingZones && (
                <option value="">Đang tải khu vực...</option>
              )}

              {!loadingZones && zones.length === 0 && (
                <option value="">Không có khu vực</option>
              )}

              {zones.map((zone) => (
                <option key={zone.id} value={zone.id}>
                  {zone.name} (ID: {zone.id})
                </option>
              ))}
            </select>
          </div>
        </div>
      </section>

      {formMode && (
        <section className="panel device-form-panel">
          <div className="devices-toolbar">
            <div>
              <h2>
                {formMode === 'create'
                  ? 'Thêm thiết bị mới'
                  : 'Cập nhật thiết bị'}
              </h2>
              <p>
                {formMode === 'create'
                  ? 'Khai báo thiết bị trong khu vực đang chọn.'
                  : `Mã thiết bị: ${editingDevice?.deviceId}`}
              </p>
            </div>

            <button
              type="button"
              className="devices-refresh"
              onClick={resetForm}
              disabled={saving}
            >
              <X size={15} />
              Đóng
            </button>
          </div>

          <form className="device-form" onSubmit={handleSubmit}>
            {formMode === 'create' && (
              <>
                <div className="form-group">
                  <label htmlFor="device-id">Mã thiết bị *</label>
                  <input
                    id="device-id"
                    value={deviceId}
                    onChange={(event) => setDeviceId(event.target.value)}
                    placeholder="Ví dụ: ESP32_SWIFTLET_01"
                    required
                    maxLength={100}
                  />
                </div>

                <div className="form-group">
                  <label htmlFor="device-type">Loại thiết bị *</label>
                  <select
                    id="device-type"
                    value={deviceType}
                    onChange={(event) =>
                      setDeviceType(event.target.value as 'ESP32' | 'CAMERA')
                    }
                  >
                    <option value="ESP32">ESP32</option>
                    <option value="CAMERA">Camera</option>
                  </select>
                </div>
              </>
            )}

            <div className="form-group">
              <label htmlFor="device-name">Tên thiết bị *</label>
              <input
                id="device-name"
                value={deviceName}
                onChange={(event) => setDeviceName(event.target.value)}
                placeholder="Ví dụ: Bộ điều khiển tầng 1"
                required
                maxLength={150}
              />
            </div>

            <div className="form-group">
              <label>
                <input
                  type="checkbox"
                  checked={enabled}
                  onChange={(event) => setEnabled(event.target.checked)}
                />
                Thiết bị được bật
              </label>
              <p>
                Đây là trạng thái bật/tắt được lưu trên backend, không phải
                trạng thái kết nối Online/Offline.
              </p>
            </div>

            {formError && <p role="alert">{formError}</p>}

            <div className="management-toolbar">
              <button type="submit" disabled={saving}>
                {saving
                  ? 'Đang lưu...'
                  : formMode === 'create'
                    ? 'Tạo thiết bị'
                    : 'Lưu thay đổi'}
              </button>

              <button
                type="button"
                onClick={resetForm}
                disabled={saving}
              >
                Hủy
              </button>
            </div>
          </form>
        </section>
      )}

      {error && (
        <div role="alert" className="panel" style={{ padding: 16, marginBottom: 20 }}>
          <p>{error}</p>
          <button
            type="button"
            className="devices-refresh"
            onClick={() => setRefreshKey((current) => current + 1)}
            disabled={!selectedZoneId || loading}
          >
            <RefreshCw size={15} />
            Thử tải lại
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
          <div className="metric-note">Theo trạng thái backend</div>
        </article>

        <article className="metric-card">
          <div className="metric-label">Mất kết nối</div>
          <div className="metric-value">{offlineCount}</div>
          <div className="metric-note">Theo trạng thái backend</div>
        </article>
      </div>

      <section className="panel devices-panel">
        <div className="devices-toolbar">
          <div>
            <h2>Danh sách thiết bị</h2>
            <p>
              {zones.find((zone) => String(zone.id) === selectedZoneId)?.name
                ?? 'Chưa chọn khu vực'}
            </p>
          </div>

          <div className="management-toolbar">
            <button
              type="button"
              className="devices-refresh"
              onClick={refreshDevices}
              disabled={loading || !selectedZoneId}
            >
              <RefreshCw size={15} />
              Làm mới
            </button>

            <button
              type="button"
              onClick={openCreateForm}
              disabled={!selectedZoneId || saving}
            >
              <Plus size={15} />
              Thêm thiết bị
            </button>
          </div>
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
                <th>Trạng thái kết nối</th>
                <th>Trạng thái thiết bị</th>
                <th>Cập nhật gần nhất</th>
                <th>Thao tác</th>
              </tr>
            </thead>

            <tbody>
              {loading && (
                <tr>
                  <td colSpan={6} className="devices-empty">
                    Đang tải thiết bị...
                  </td>
                </tr>
              )}

              {!loading && filteredDevices.map((device) => {
                const Icon = getDeviceIcon(`${device.type} ${device.name}`)

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

                    <td>{device.enabled ? 'Đang bật' : 'Đã tắt'}</td>

                    <td>
                      {device.lastSeenAt
                        ? new Date(device.lastSeenAt).toLocaleString('vi-VN')
                        : 'Chưa có dữ liệu'}
                    </td>

                    <td>
                      <button
                        type="button"
                        onClick={() => openEditForm(device)}
                        disabled={saving}
                      >
                        <Pencil size={15} />
                        Sửa
                      </button>
                    </td>
                  </tr>
                )
              })}

              {!loading && filteredDevices.length === 0 && (
                <tr>
                  <td colSpan={6} className="devices-empty">
                    {error
                      ? 'Không tải được dữ liệu.'
                      : !selectedZoneId
                        ? 'Vui lòng chọn nhà yến và khu vực.'
                        : devices.length === 0
                          ? 'Khu vực này chưa có thiết bị.'
                          : 'Không tìm thấy thiết bị phù hợp.'}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>

        <div className="devices-footnote">
          <Wifi size={15} />
          Trạng thái Online/Offline lấy từ backend. Danh sách hiện tải tối đa
          20 thiết bị mỗi lần.
        </div>
      </section>
    </>
  )
}
