
import { useEffect, useState, type FormEvent } from 'react'
import { Home, RefreshCw, Plus, Pencil, X } from 'lucide-react'
import { api, type House } from '../services/api'

type FormMode = 'create' | 'edit' | null

export default function Houses() {
  const [houses, setHouses] = useState<House[]>([])
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const [formError, setFormError] = useState('')
  const [formMode, setFormMode] = useState<FormMode>(null)
  const [editingHouse, setEditingHouse] = useState<House | null>(null)

  const [name, setName] = useState('')
  const [address, setAddress] = useState('')
  const [description, setDescription] = useState('')
  const [active, setActive] = useState(true)

  // Khu vực đầu tiên, bắt buộc khi tạo nhà mới
  const [initialZoneName, setInitialZoneName] = useState('')
  const [floorLabel, setFloorLabel] = useState('')
  const [areaM2, setAreaM2] = useState('')

  async function loadHouses() {
    setLoading(true)
    setError('')

    try {
      const result = await api.getHouses()
      setHouses(result.items)
    } catch (err) {
      setError(
        err instanceof Error
          ? err.message
          : 'Không thể tải danh sách nhà yến.',
      )
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void loadHouses()
  }, [])

  function resetForm() {
    setFormMode(null)
    setEditingHouse(null)
    setFormError('')
    setName('')
    setAddress('')
    setDescription('')
    setActive(true)
    setInitialZoneName('')
    setFloorLabel('')
    setAreaM2('')
  }

  function openCreateForm() {
    resetForm()
    setFormMode('create')
  }

  function openEditForm(house: House) {
    setEditingHouse(house)
    setName(house.name)
    setAddress(house.address ?? '')
    setDescription(house.description ?? '')
    setActive(house.active)
    setFormError('')
    setFormMode('edit')
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setFormError('')

    if (!name.trim()) {
      setFormError('Vui lòng nhập tên nhà yến.')
      return
    }

    if (formMode === 'create' && !initialZoneName.trim()) {
      setFormError('Vui lòng nhập tên khu vực đầu tiên.')
      return
    }

    const parsedArea = areaM2.trim() ? Number(areaM2) : 0

    if (
      formMode === 'create' &&
      (!Number.isFinite(parsedArea) || parsedArea < 0)
    ) {
      setFormError('Diện tích phải là số hợp lệ và không âm.')
      return
    }

    setSaving(true)

    try {
      if (formMode === 'create') {
        await api.createHouse({
          name: name.trim(),
          address: address.trim(),
          description: description.trim(),
          initialZone: {
            name: initialZoneName.trim(),
            floorLabel: floorLabel.trim(),
            areaM2: parsedArea,
          },
        })
      } else if (formMode === 'edit' && editingHouse) {
        await api.updateHouse(editingHouse.id, {
          name: name.trim(),
          address: address.trim(),
          description: description.trim(),
          active,
        })
      }

      resetForm()
      await loadHouses()
    } catch (err) {
      setFormError(
        err instanceof Error
          ? err.message
          : 'Không thể lưu thông tin nhà yến.',
      )
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="page-container houses-page">
      <div className="page-header">
        <div>
          <h1>Quản lý nhà yến</h1>
          <p>Danh sách nhà yến mà tài khoản được phép truy cập.</p>
        </div>

        <div className="management-toolbar">
          <button onClick={() => void loadHouses()} disabled={loading || saving}>
            <RefreshCw size={16} />
            Làm mới
          </button>

          <button onClick={openCreateForm} disabled={saving}>
            <Plus size={16} />
            Tạo nhà yến
          </button>
        </div>
      </div>

      {formMode && (
        <section
            className="management-card house-form-card"
            aria-labelledby="house-form-title"
        >
          <div className="page-header">
            <h2 id="house-form-title">
              {formMode === 'create' ? 'Tạo nhà yến mới' : 'Cập nhật nhà yến'}
            </h2>

            <button
              type="button"
              onClick={resetForm}
              disabled={saving}
              aria-label="Đóng biểu mẫu"
            >
              <X size={16} />
              Đóng
            </button>
          </div>

          <form className="house-form" onSubmit={handleSubmit}>
            <div className="form-group">
              <label htmlFor="house-name">Tên nhà yến *</label>
              <input
                id="house-name"
                value={name}
                onChange={(event) => setName(event.target.value)}
                placeholder="Ví dụ: Nhà yến Bình Thạnh"
                required
                maxLength={150}
              />
            </div>

            <div className="form-group">
              <label htmlFor="house-address">Địa chỉ</label>
              <input
                id="house-address"
                value={address}
                onChange={(event) => setAddress(event.target.value)}
                placeholder="Nhập địa chỉ nhà yến"
              />
            </div>

            <div className="form-group">
              <label htmlFor="house-description">Mô tả</label>
              <textarea
                id="house-description"
                value={description}
                onChange={(event) => setDescription(event.target.value)}
                placeholder="Thông tin mô tả nhà yến"
                rows={3}
              />
            </div>

            {formMode === 'create' && (
              <>
                <h3>Khu vực đầu tiên</h3>
                <p>Backend yêu cầu tạo ít nhất một khu vực khi tạo nhà.</p>

                <div className="form-group">
                  <label htmlFor="initial-zone-name">Tên khu vực *</label>
                  <input
                    id="initial-zone-name"
                    value={initialZoneName}
                    onChange={(event) => setInitialZoneName(event.target.value)}
                    placeholder="Ví dụ: Khu vực tầng 1"
                    required
                    maxLength={150}
                  />
                </div>

                <div className="form-group">
                  <label htmlFor="initial-zone-floor">Tầng / vị trí</label>
                  <input
                    id="initial-zone-floor"
                    value={floorLabel}
                    onChange={(event) => setFloorLabel(event.target.value)}
                    placeholder="Ví dụ: Tầng 1"
                  />
                </div>

                <div className="form-group">
                  <label htmlFor="initial-zone-area">Diện tích (m²)</label>
                  <input
                    id="initial-zone-area"
                    type="number"
                    min="0"
                    step="0.01"
                    value={areaM2}
                    onChange={(event) => setAreaM2(event.target.value)}
                    placeholder="Ví dụ: 120"
                  />
                </div>
              </>
            )}

            {formMode === 'edit' && (
              <div className="form-group">
                <label>
                  <input
                    type="checkbox"
                    checked={active}
                    onChange={(event) => setActive(event.target.checked)}
                  />
                  Nhà yến đang hoạt động
                </label>
              </div>
            )}

            {formError && (
              <p role="alert" className="form-error">
                {formError}
              </p>
            )}

            <div className="management-toolbar">
              <button type="submit" disabled={saving}>
                {saving
                  ? 'Đang lưu...'
                  : formMode === 'create'
                    ? 'Tạo nhà yến'
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

      {loading && <p>Đang tải danh sách nhà yến...</p>}

      {!loading && error && (
        <div role="alert">
          <p>{error}</p>
          <button onClick={() => void loadHouses()}>Thử lại</button>
        </div>
      )}

      {!loading && !error && houses.length === 0 && (
        <p>Tài khoản hiện chưa có nhà yến nào được phép truy cập.</p>
      )}

      {!loading && !error && houses.length > 0 && (
        <div className="management-grid">
          {houses.map((house) => (
            <article className="management-card" key={house.id}>
              <Home size={24} />

              <h2>{house.name}</h2>
              <p>Địa chỉ: {house.address || 'Chưa cập nhật'}</p>
              <p>{house.description || 'Chưa có mô tả'}</p>

              <p>
                Trạng thái:{' '}
                {house.active ? 'Đang hoạt động' : 'Đã ngừng hoạt động'}
              </p>

              <p>Mã nhà yến: {house.id}</p>

              <button
                type="button"
                onClick={() => openEditForm(house)}
                disabled={saving}
              >
                <Pencil size={16} />
                Cập nhật
              </button>
            </article>
          ))}
        </div>
      )}
    </div>
  )
}
