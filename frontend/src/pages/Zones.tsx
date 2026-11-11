import { useEffect, useState, type FormEvent } from 'react'
import { Layers, RefreshCw, Plus, Pencil, X } from 'lucide-react'
import { api, type House, type Zone } from '../services/api'

type FormMode = 'create' | 'edit' | null

export default function Zones() {
  const [houses, setHouses] = useState<House[]>([])
  const [houseId, setHouseId] = useState('')
  const [zones, setZones] = useState<Zone[]>([])

  const [loadingHouses, setLoadingHouses] = useState(true)
  const [loadingZones, setLoadingZones] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const [formError, setFormError] = useState('')

  const [formMode, setFormMode] = useState<FormMode>(null)
  const [editingZone, setEditingZone] = useState<Zone | null>(null)
  const [name, setName] = useState('')
  const [floorLabel, setFloorLabel] = useState('')
  const [areaM2, setAreaM2] = useState('')
  const [active, setActive] = useState(true)
  const [refreshKey, setRefreshKey] = useState(0)

  useEffect(() => {
    let cancelled = false

    async function loadHouses() {
      setLoadingHouses(true)
      setError('')

      try {
        const result = await api.getHouses()

        if (cancelled) return

        setHouses(result.items)

        setHouseId((currentId) => {
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

  useEffect(() => {
    if (!houseId) {
      setZones([])
      setLoadingZones(false)
      return
    }

    let cancelled = false

    async function loadZones() {
      setLoadingZones(true)
      setError('')
      setZones([])

      try {
        const result = await api.getZones(Number(houseId))

        if (!cancelled) {
          setZones(result.items)
        }
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
  }, [houseId, refreshKey])

  function resetForm() {
    setFormMode(null)
    setEditingZone(null)
    setFormError('')
    setName('')
    setFloorLabel('')
    setAreaM2('')
    setActive(true)
  }

  function openCreateForm() {
    resetForm()
    setFormMode('create')
  }

  function openEditForm(zone: Zone) {
    setEditingZone(zone)
    setName(zone.name)
    setFloorLabel(zone.floorLabel ?? '')
    setAreaM2(zone.areaM2 == null ? '' : String(zone.areaM2))
    setActive(zone.active)
    setFormError('')
    setFormMode('edit')
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setFormError('')

    if (!houseId) {
      setFormError('Vui lòng chọn nhà yến.')
      return
    }

    if (!name.trim()) {
      setFormError('Vui lòng nhập tên khu vực.')
      return
    }

    const parsedArea = areaM2.trim() === '' ? null : Number(areaM2)

    if (
      parsedArea !== null &&
      (!Number.isFinite(parsedArea) || parsedArea < 0)
    ) {
      setFormError('Diện tích phải là số hợp lệ và không âm.')
      return
    }

    setSaving(true)

    try {
      if (formMode === 'create') {
        await api.createZone(Number(houseId), {
          name: name.trim(),
          floorLabel: floorLabel.trim(),
          areaM2: parsedArea,
        })
      } else if (formMode === 'edit' && editingZone) {
        const updateData = {
          name: name.trim(),
          floorLabel: floorLabel.trim(),
          active,
          ...(parsedArea !== null ? { areaM2: parsedArea } : {}),
        }

        await api.updateZone(editingZone.id, updateData)
      } else {
        return
      }

      resetForm()
      setRefreshKey((current) => current + 1)
    } catch (err) {
      setFormError(
        err instanceof Error
          ? err.message
          : 'Không thể lưu thông tin khu vực.',
      )
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="page-container zone-page">
      <div className="page-header zone-page-header">
        <div>
          <h1>Quản lý khu vực</h1>
          <p>Chọn nhà yến để xem và quản lý các khu vực trực thuộc.</p>
        </div>

        <div className="management-toolbar zone-toolbar">
          <button
            type="button"
            onClick={() => setRefreshKey((current) => current + 1)}
            disabled={!houseId || loadingZones || saving}
          >
            <RefreshCw size={16} aria-hidden="true" />
            Làm mới
          </button>

          <button
            type="button"
            onClick={openCreateForm}
            disabled={!houseId || saving}
          >
            <Plus size={16} aria-hidden="true" />
            Thêm khu vực
          </button>
        </div>
      </div>

      <div className="zone-house-selector">
        <label htmlFor="house-select">Nhà yến</label>

        <select
          id="house-select"
          value={houseId}
          disabled={loadingHouses || houses.length === 0}
          onChange={(event) => {
            setHouseId(event.target.value)
            resetForm()
          }}
        >
          {houses.length === 0 && (
            <option value="">Không có nhà yến</option>
          )}

          {houses.map((house) => (
            <option key={house.id} value={house.id}>
              {house.name}
            </option>
          ))}
        </select>
      </div>

      {formMode && (
        <section
          className="management-card zone-form-card"
          aria-labelledby="zone-form-title"
        >
          <div className="zone-form-header">
            <h2 id="zone-form-title">
              {formMode === 'create'
                ? 'Thêm khu vực mới'
                : 'Cập nhật khu vực'}
            </h2>

            <button
              type="button"
              className="zone-close-button"
              onClick={resetForm}
              disabled={saving}
            >
              <X size={16} aria-hidden="true" />
              Đóng
            </button>
          </div>

          <form className="zone-form" onSubmit={handleSubmit}>
            <div className="form-group zone-form-group">
              <label htmlFor="zone-name">Tên khu vực *</label>
              <input
                id="zone-name"
                name="name"
                value={name}
                onChange={(event) => setName(event.target.value)}
                placeholder="Ví dụ: Khu vực tầng 1"
                maxLength={150}
                autoComplete="off"
                required
              />
            </div>

            <div className="form-group zone-form-group">
              <label htmlFor="zone-floor">Tầng / vị trí</label>
              <input
                id="zone-floor"
                name="floorLabel"
                value={floorLabel}
                onChange={(event) => setFloorLabel(event.target.value)}
                placeholder="Ví dụ: Tầng 1"
              />
            </div>

            <div className="form-group zone-form-group">
              <label htmlFor="zone-area">Diện tích (m²)</label>
              <input
                id="zone-area"
                name="areaM2"
                type="number"
                min="0"
                step="0.01"
                value={areaM2}
                onChange={(event) => setAreaM2(event.target.value)}
                placeholder="Ví dụ: 120"
                inputMode="decimal"
              />

              {formMode === 'edit' && (
                <p className="zone-field-hint">
                  Để trống diện tích sẽ giữ nguyên giá trị hiện tại.
                </p>
              )}
            </div>

            {formMode === 'edit' && (
              <div className="form-group zone-form-group">
                <label className="zone-checkbox-label" htmlFor="zone-active">
                  <input
                    id="zone-active"
                    name="active"
                    type="checkbox"
                    checked={active}
                    onChange={(event) => setActive(event.target.checked)}
                  />
                  Khu vực đang hoạt động
                </label>
              </div>
            )}

            {formError && (
              <p className="zone-form-error" role="alert">
                {formError}
              </p>
            )}

            <div className="zone-form-actions">
              <button type="submit" disabled={saving}>
                {saving
                  ? 'Đang lưu...'
                  : formMode === 'create'
                    ? 'Tạo khu vực'
                    : 'Lưu thay đổi'}
              </button>

              <button
                type="button"
                className="zone-cancel-button"
                onClick={resetForm}
                disabled={saving}
              >
                Hủy
              </button>
            </div>
          </form>
        </section>
      )}

      {loadingHouses && <p>Đang tải danh sách nhà yến...</p>}
      {loadingZones && <p>Đang tải danh sách khu vực...</p>}

      {error && (
        <div className="zone-error-message" role="alert">
          <p>{error}</p>
          <button
            type="button"
            onClick={() => setRefreshKey((current) => current + 1)}
            disabled={!houseId || loadingZones}
          >
            Thử tải lại
          </button>
        </div>
      )}

      {!loadingHouses &&
        !loadingZones &&
        !error &&
        !houseId && <p>Không có nhà yến để hiển thị khu vực.</p>}

      {!loadingHouses &&
        !loadingZones &&
        !error &&
        houseId &&
        zones.length === 0 && <p>Nhà yến này chưa có khu vực.</p>}

      {!loadingZones && !error && zones.length > 0 && (
        <div className="management-grid zone-grid">
          {zones.map((zone) => (
            <article className="management-card zone-card" key={zone.id}>
              <Layers
                size={24}
                className="zone-card-icon"
                aria-hidden="true"
              />

              <h2>{zone.name}</h2>

              <p>Tầng: {zone.floorLabel || 'Chưa cập nhật'}</p>

              <p>
                Diện tích:{' '}
                {zone.areaM2 != null ? `${zone.areaM2} m²` : 'Chưa cập nhật'}
              </p>

              <p>Mã khu vực: {zone.id}</p>

              <p>
                Trạng thái:{' '}
                {zone.active ? 'Đang hoạt động' : 'Đã ngừng hoạt động'}
              </p>

              <button
                type="button"
                onClick={() => openEditForm(zone)}
                disabled={saving}
              >
                <Pencil size={16} aria-hidden="true" />
                Cập nhật
              </button>
            </article>
          ))}
        </div>
      )}
    </div>
  )
}
