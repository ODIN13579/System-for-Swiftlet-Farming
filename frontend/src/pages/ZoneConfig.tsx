
import { useCallback, useEffect, useState } from 'react'
import {
  api,
  type House,
  type Zone,
  type ZoneConfig as ZoneConfigType,
  type ZoneConfigInput,
} from '../services/api'

type FormValues = {
  humidityOn: string
  humidityOff: string
  tempMin: string
  tempMax: string
  tempHysteresis: string
  co2Min: string
  co2Max: string
  lightMin: string
  lightMax: string
  telemetryIntervalSec: string
  heartbeatIntervalSec: string
}

const emptyForm: FormValues = {
  humidityOn: '',
  humidityOff: '',
  tempMin: '',
  tempMax: '',
  tempHysteresis: '',
  co2Min: '',
  co2Max: '',
  lightMin: '',
  lightMax: '',
  telemetryIntervalSec: '5',
  heartbeatIntervalSec: '2',
}

function toForm(config: ZoneConfigType): FormValues {
  return {
    humidityOn: String(config.humidityOn ?? ''),
    humidityOff: String(config.humidityOff ?? ''),
    tempMin: String(config.tempMin ?? ''),
    tempMax: String(config.tempMax ?? ''),
    tempHysteresis: String(config.tempHysteresis ?? ''),
    co2Min: String(config.co2Min ?? ''),
    co2Max: String(config.co2Max ?? ''),
    lightMin: String(config.lightMin ?? ''),
    lightMax: String(config.lightMax ?? ''),
    telemetryIntervalSec: String(config.telemetryIntervalSec),
    heartbeatIntervalSec: String(config.heartbeatIntervalSec),
  }
}

function toNumber(value: string): number | null {
  return value.trim() === '' ? null : Number(value)
}

function toInput(
  form: FormValues,
  version: number,
): ZoneConfigInput {
  return {
    humidityOn: toNumber(form.humidityOn),
    humidityOff: toNumber(form.humidityOff),
    tempMin: toNumber(form.tempMin),
    tempMax: toNumber(form.tempMax),
    tempHysteresis: toNumber(form.tempHysteresis),
    co2Min: toNumber(form.co2Min),
    co2Max: toNumber(form.co2Max),
    lightMin: toNumber(form.lightMin),
    lightMax: toNumber(form.lightMax),
    telemetryIntervalSec: Number(form.telemetryIntervalSec),
    heartbeatIntervalSec: Number(form.heartbeatIntervalSec),
    version,
  }
}

type FieldProps = {
  label: string
  value: string
  unit: string
  min?: number
  max?: number
  step?: number
  disabled: boolean
  onChange: (value: string) => void
}

function NumberField({
  label,
  value,
  unit,
  min,
  max,
  step = 1,
  disabled,
  onChange,
}: FieldProps) {
  return (
    <label className="zone-config-field">
      <span>{label}</span>
      <div className="zone-config-input-wrap">
        <input
          type="number"
          value={value}
          min={min}
          max={max}
          step={step}
          disabled={disabled}
          onChange={(event) => onChange(event.target.value)}
        />
        <span>{unit}</span>
      </div>
    </label>
  )
}
function getSyncStatusLabel(status?: string | null): string {
  switch (status?.toUpperCase()) {
    case 'PENDING':
      return 'Đang chờ thiết bị xác nhận'
    case 'APPLIED':
      return 'Thiết bị đã áp dụng'
    case 'REJECTED':
      return 'Thiết bị từ chối cấu hình'
    case 'STALE':
      return 'Cấu hình thiết bị chưa cập nhật'
    case 'UNKNOWN':
    default:
      return 'Chưa xác định trạng thái áp dụng'
  }
}

function getSyncStatusDescription(status: string, version: number): string {
  switch (status) {
    case 'APPLIED':
      return `Thiết bị đã xác nhận áp dụng cấu hình phiên bản ${version}.`
    case 'PENDING':
      return 'Đang chờ thiết bị xác nhận áp dụng phiên bản này. Bấm Tải lại để kiểm tra.'
    case 'REJECTED':
      return 'Thiết bị đã phản hồi nhưng từ chối áp dụng phiên bản này.'
    case 'STALE':
      return 'Thiết bị báo phiên bản cấu hình này đã cũ.'
    default:
      return 'Chưa có xác nhận thiết bị đã áp dụng phiên bản này.'
  }
}

export default function ZoneConfig() {
  const [houses, setHouses] = useState<House[]>([])
  const [zones, setZones] = useState<Zone[]>([])
  const [selectedHouseId, setSelectedHouseId] = useState('')
  const [selectedZoneId, setSelectedZoneId] = useState('')
  const [loadingHouses, setLoadingHouses] = useState(true)
  const [loadingZones, setLoadingZones] = useState(false)
  const [config, setConfig] = useState<ZoneConfigType | null>(null)
  const [form, setForm] = useState<FormValues>(emptyForm)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')

  useEffect(() => {
    let cancelled = false

    async function loadHouses() {
      setLoadingHouses(true)

      try {
        const result = await api.getHouses()

        if (cancelled) return

        setHouses(result.items)

        if (result.items.length > 0) {
          setSelectedHouseId(String(result.items[0].id))
        } else {
          setSelectedHouseId('')
          setSelectedZoneId('')
        }
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
    let cancelled = false

    async function loadZones() {
      setSelectedZoneId('')
      setZones([])
      setConfig(null)

      if (!selectedHouseId) {
        setLoadingZones(false)
        return
      }

      setLoadingZones(true)

      try {
        const result = await api.getZones(Number(selectedHouseId))

        if (cancelled) return

        setZones(result.items)

        if (result.items.length > 0) {
          setSelectedZoneId(String(result.items[0].id))
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
  }, [selectedHouseId])
  const loadConfig = useCallback(async () => {
    if (!selectedZoneId) {
      setConfig(null)
      setLoading(false)
      return
    }

    setLoading(true)
    setError('')
    setMessage('')

    try {
      const data = await api.getZoneConfig(Number(selectedZoneId))
      setConfig(data)
      setForm(toForm(data))
    } catch (err) {
      setConfig(null)
      setError(
        err instanceof Error
          ? err.message
          : 'Không thể tải cấu hình khu vực.',
      )
    } finally {
      setLoading(false)
    }
  }, [selectedZoneId])

  useEffect(() => {
    void loadConfig()
  }, [loadConfig])

  function changeField(key: keyof FormValues, value: string) {
    setForm((current) => ({ ...current, [key]: value }))
    setMessage('')
  }

  function validate(): string | null {
    const values = {
      humidityOn: toNumber(form.humidityOn),
      humidityOff: toNumber(form.humidityOff),
      tempMin: toNumber(form.tempMin),
      tempMax: toNumber(form.tempMax),
      tempHysteresis: toNumber(form.tempHysteresis),
      co2Min: toNumber(form.co2Min),
      co2Max: toNumber(form.co2Max),
      lightMin: toNumber(form.lightMin),
      lightMax: toNumber(form.lightMax),
      telemetry: toNumber(form.telemetryIntervalSec),
      heartbeat: toNumber(form.heartbeatIntervalSec),
    }

    // Kiểm tra giá trị số hữu hạn.
    for (const [key, value] of Object.entries(values)) {
      if (value !== null && !Number.isFinite(value)) {
        return `Trường ${key} phải là một số hữu hạn.`
      }
    }

    const {
      humidityOn,
      humidityOff,
      tempMin,
      tempMax,
      tempHysteresis,
      co2Min,
      co2Max,
      lightMin,
      lightMax,
      telemetry,
      heartbeat,
    } = values

    // Độ ẩm: 0–100%.
    for (const value of [humidityOn, humidityOff]) {
      if (value !== null && (value < 0 || value > 100)) {
        return 'Độ ẩm phải nằm trong khoảng 0–100%.'
      }
    }

    if (
      humidityOn !== null &&
      humidityOff !== null &&
      humidityOn >= humidityOff
    ) {
      return 'Độ ẩm bật phải nhỏ hơn độ ẩm tắt.'
    }

    // Nhiệt độ: -10–60°C.
    for (const value of [tempMin, tempMax]) {
      if (value !== null && (value < -10 || value > 60)) {
        return 'Nhiệt độ phải nằm trong khoảng -10–60°C.'
      }
    }

    if (
      tempMin !== null &&
      tempMax !== null &&
      tempMin >= tempMax
    ) {
      return 'Nhiệt độ tối thiểu phải nhỏ hơn nhiệt độ tối đa.'
    }

    // Độ trễ nhiệt độ: nếu có nhập thì phải lớn hơn 0.
    if (tempHysteresis !== null && tempHysteresis <= 0) {
      return 'Độ trễ nhiệt độ phải lớn hơn 0 nếu được nhập.'
    }

    // CO₂: 0–5000 ppm.
    for (const value of [co2Min, co2Max]) {
      if (value !== null && (value < 0 || value > 5000)) {
        return 'CO₂ phải nằm trong khoảng 0–5000 ppm.'
      }
    }

    if (
      co2Min !== null &&
      co2Max !== null &&
      co2Min > co2Max
    ) {
      return 'CO₂ tối thiểu không được lớn hơn CO₂ tối đa.'
    }

    // Ánh sáng: 0–100000 lux.
    for (const value of [lightMin, lightMax]) {
      if (value !== null && (value < 0 || value > 100000)) {
        return 'Cường độ sáng phải nằm trong khoảng 0–100000 lux.'
      }
    }

    if (
      lightMin !== null &&
      lightMax !== null &&
      lightMin > lightMax
    ) {
      return 'Ánh sáng tối thiểu không được lớn hơn ánh sáng tối đa.'
    }

    // Chu kỳ telemetry: số nguyên từ 1 đến 60 giây.
    if (
      telemetry === null ||
      !Number.isInteger(telemetry) ||
      telemetry < 1 ||
      telemetry > 60
    ) {
      return 'Chu kỳ gửi telemetry phải là số nguyên từ 1 đến 60 giây.'
    }

    // Backend yêu cầu heartbeat bằng đúng 2 giây.
    if (heartbeat !== 2) {
      return 'Chu kỳ heartbeat phải bằng 2 giây.'
    }

    return null
  }

  async function saveConfig(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError('')
    setMessage('')

    if (!config) return

    const validationError = validate()
    if (validationError) {
      setError(validationError)
      return
    }

    setSaving(true)

    try {
      const updated = await api.updateZoneConfig(
        Number(selectedZoneId),
        toInput(form, config.version),
      )
      setConfig(updated)
      setForm(toForm(updated))
      setMessage('Đã lưu cấu hình.')
    } catch (err) {
      const errorMessage =
        err instanceof Error ? err.message : ''

      if (
        errorMessage.includes('VERSION_CONFLICT') ||
        errorMessage.includes('409')
      ) {
        setError(
          'Cấu hình đã thay đổi trên máy chủ. Hãy nhấn "Tải lại" ' +
          'để lấy phiên bản mới nhất trước khi chỉnh sửa tiếp.',
        )
      } else {
        setError(errorMessage || 'Không thể lưu cấu hình.')
      }
    } finally {
      setSaving(false)
    }
  }
  if (loadingHouses) {
    return <div className="zone-config-page">Đang tải danh sách nhà yến...</div>
  }

  if (!loadingHouses && houses.length === 0) {
    return (
      <div className="zone-config-page">
        <h2>Cấu hình môi trường</h2>
        <p>Chưa có nhà yến nào được cấp quyền truy cập.</p>
      </div>
    )
  }

  if (selectedHouseId && !loadingZones && zones.length === 0) {
    return (
      <div className="zone-config-page">
        <h2>Cấu hình môi trường</h2>
        <p>Nhà yến này chưa có khu vực để cấu hình.</p>
      </div>
    )
  }
  if (loading) {
    return <div className="zone-config-page">Đang tải cấu hình khu vực...</div>
  }

  if (error && !config) {
    return (
      <div className="zone-config-page">
        <h2>Cấu hình môi trường</h2>
        <p className="zone-config-error">{error}</p>
        <button type="button" onClick={() => void loadConfig()}>
          Thử tải lại
        </button>
      </div>
    )
  }

  if (!config) {
    return <div className="zone-config-page">Không có dữ liệu cấu hình.</div>
  }

  const syncStatus = config.syncStatus?.trim().toUpperCase() || 'UNKNOWN'

  return (
    <div className="zone-config-page">
      <div className="zone-config-heading">
        <div className="zone-config-card">
          <h3>Chọn nhà yến và khu vực</h3>

          <div className="zone-config-grid">
            <label className="zone-config-field">
              <span>Nhà yến</span>
              <select
                value={selectedHouseId}
                disabled={loadingHouses || houses.length === 0}
                onChange={(event) => {
                  setSelectedHouseId(event.target.value)
                  setError('')
                  setMessage('')
                }}
              >
                {houses.length === 0 && (
                  <option value="">Không có nhà yến</option>
                )}

                {houses.map((house) => (
                  <option key={house.id} value={String(house.id)}>
                    {house.name}
                  </option>
                ))}
              </select>
            </label>

            <label className="zone-config-field">
              <span>Khu vực</span>
              <select
                value={selectedZoneId}
                disabled={loadingZones || zones.length === 0}
                onChange={(event) => {
                  setSelectedZoneId(event.target.value)
                  setError('')
                  setMessage('')
                }}
              >
                {loadingZones && <option value="">Đang tải khu vực...</option>}

                {!loadingZones && zones.length === 0 && (
                  <option value="">Không có khu vực</option>
                )}

                {zones.map((zone) => (
                  <option key={zone.id} value={String(zone.id)}>
                    {zone.name}
                  </option>
                ))}
              </select>
            </label>
          </div>
        </div>
        <div>
          <h2>Cấu hình môi trường</h2>
          <p>
            {zones.find((zone) => zone.id === Number(selectedZoneId))?.name
              ?? `Khu vực ID: ${selectedZoneId}`}
            {' · '}Phiên bản: {config.version}
          </p>
        </div>
        <button type="button" onClick={() => void loadConfig()} disabled={saving}>
          Tải lại
        </button>
      </div>

      <div
        className={`zone-config-notice${syncStatus === 'APPLIED' ? ' zone-config-notice--applied' : ''}`}
        role="status"
      >
        <strong>
          Trạng thái thiết bị: {getSyncStatusLabel(syncStatus)}
        </strong>
        <small>Mã trạng thái: {syncStatus}</small>
        <p>{getSyncStatusDescription(syncStatus, config.version)}</p>
      </div>

      {error && <p className="zone-config-error" role="alert">{error}</p>}
      {message && <p className="zone-config-success" role="status">{message}</p>}

      <form onSubmit={saveConfig}>
        <section className="zone-config-card">
          <h3>Độ ẩm</h3>
          <div className="zone-config-grid">
            <NumberField
              label="Độ ẩm bật thiết bị"
              value={form.humidityOn}
              unit="%"
              min={0}
              max={100}
              disabled={saving}
              onChange={(v) => changeField('humidityOn', v)}
            />
            <NumberField
              label="Độ ẩm tắt thiết bị"
              value={form.humidityOff}
              unit="%"
              min={0}
              max={100}
              disabled={saving}
              onChange={(v) => changeField('humidityOff', v)}
            />
          </div>
          <small>Độ ẩm bật phải thấp hơn độ ẩm tắt.</small>
        </section>

        <section className="zone-config-card">
          <h3>Nhiệt độ</h3>
          <div className="zone-config-grid">
            <NumberField
              label="Nhiệt độ tối thiểu"
              value={form.tempMin}
              unit="°C"
              min={-10}
              max={60}
              step={0.1}
              disabled={saving}
              onChange={(v) => changeField('tempMin', v)}
            />
            <NumberField
              label="Nhiệt độ tối đa"
              value={form.tempMax}
              unit="°C"
              min={-10}
              max={60}
              step={0.1}
              disabled={saving}
              onChange={(v) => changeField('tempMax', v)}
            />
            <NumberField
              label="Độ trễ nhiệt độ"
              value={form.tempHysteresis}
              unit="°C"
              min={0.1}
              step={0.1}
              disabled={saving}
              onChange={(v) => changeField('tempHysteresis', v)}
            />
          </div>
        </section>

        <section className="zone-config-card">
          <h3>Chất lượng không khí (CO₂)</h3>
          <div className="zone-config-grid">
            <NumberField
              label="CO₂ tối thiểu"
              value={form.co2Min}
              unit="ppm"
              min={0}
              max={5000}
              disabled={saving}
              onChange={(v) => changeField('co2Min', v)}
            />
            <NumberField
              label="CO₂ tối đa"
              value={form.co2Max}
              unit="ppm"
              min={0}
              max={5000}
              disabled={saving}
              onChange={(v) => changeField('co2Max', v)}
            />
          </div>
        </section>

        <section className="zone-config-card">
          <h3>Cường độ ánh sáng</h3>
          <div className="zone-config-grid">
            <NumberField
              label="Ánh sáng tối thiểu"
              value={form.lightMin}
              unit="lux"
              min={0}
              max={100000}
              disabled={saving}
              onChange={(v) => changeField('lightMin', v)}
            />
            <NumberField
              label="Ánh sáng tối đa"
              value={form.lightMax}
              unit="lux"
              min={0}
              max={100000}
              disabled={saving}
              onChange={(v) => changeField('lightMax', v)}
            />
          </div>
        </section>

        <section className="zone-config-card">
          <h3>Chu kỳ truyền dữ liệu</h3>
          <div className="zone-config-grid">
            <NumberField
              label="Chu kỳ gửi telemetry"
              value={form.telemetryIntervalSec}
              unit="giây"
              min={1}
              max={60}
              disabled={saving}
              onChange={(v) => changeField('telemetryIntervalSec', v)}
            />
            <NumberField
              label="Chu kỳ heartbeat"
              value={form.heartbeatIntervalSec}
              unit="giây"
              min={2}
              max={2}
              disabled={saving}
              onChange={(v) => changeField('heartbeatIntervalSec', v)}
            />
          </div>
        </section>

        <div className="zone-config-actions">
          <button type="button" onClick={() => setForm(toForm(config))} disabled={saving}>
            Hoàn tác
          </button>
          <button type="submit" disabled={saving}>
            {saving ? 'Đang lưu...' : 'Lưu cấu hình'}
          </button>
        </div>
      </form>

      <p className="zone-config-meta">
        Cập nhật lần cuối: {new Date(config.updatedAt).toLocaleString('vi-VN')}
      </p>
    </div>
  )
}
