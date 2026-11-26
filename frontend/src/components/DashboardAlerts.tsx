
import { useEffect, useState } from 'react'
import { AlertTriangle, ArrowRight, Image as ImageIcon } from 'lucide-react'
import {
  api,
  type AlertLevel,
  type AlertRecord,
} from '../services/api'

type SnapshotItem = {
  alert: AlertRecord
  url: string
  error?: string
}

function formatTime(value: string) {
  return new Date(value).toLocaleString('vi-VN', {
    timeZone: 'Asia/Ho_Chi_Minh',
    day: '2-digit',
    month: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function levelText(level: AlertLevel) {
  return level === 'CRITICAL' ? 'Nghiêm trọng' : 'Cảnh báo'
}

function DashboardSnapshots({ alerts }: { alerts: AlertRecord[] }) {
  const [items, setItems] = useState<SnapshotItem[]>([])
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    let cancelled = false
    const objectUrls: string[] = []

    async function loadSnapshots() {
      setLoading(true)
      setItems([])

      const latest = [...alerts]
        .filter((alert) => alert.snapshotId != null)
        .sort(
          (a, b) =>
            Date.parse(b.createdAt) - Date.parse(a.createdAt),
        )
        .slice(0, 5)

      const loaded = await Promise.all(
        latest.map(async (alert): Promise<SnapshotItem> => {
          try {
            const blob = await api.getSnapshotBlob(alert.snapshotId!)
            if (cancelled) {
              return { alert, url: '' }
            }

            const url = URL.createObjectURL(blob)
            objectUrls.push(url)
            return { alert, url }
          } catch (error) {
            return {
              alert,
              url: '',
              error:
                error instanceof Error
                  ? error.message
                  : 'Không tải được ảnh.',
            }
          }
        }),
      )

      if (!cancelled) {
        setItems(loaded)
        setLoading(false)
      }
    }

    void loadSnapshots()

    return () => {
      cancelled = true
      objectUrls.forEach((url) => URL.revokeObjectURL(url))
    }
  }, [alerts])

  return (
    <section style={{ marginTop: 20 }}>
      <div className="panel-heading">
        <div>
          <h3>5 ảnh bất thường mới nhất</h3>
          <p>Ảnh được tải qua API có xác thực</p>
        </div>
        <ImageIcon size={20} />
      </div>

      {loading && <p role="status">Đang tải ảnh bằng chứng...</p>}

      {!loading && items.length === 0 && (
        <p>Chưa có cảnh báo nào liên kết với ảnh bằng chứng.</p>
      )}

      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))',
          gap: 12,
        }}
      >
        {items.map(({ alert, url, error }) => (
          <article
            key={alert.id}
            style={{
              border: '1px solid var(--border-color, #ddd)',
              borderRadius: 10,
              padding: 10,
              minWidth: 0,
            }}
          >
            {url ? (
              <img
                src={url}
                alt={`Ảnh bất thường của cảnh báo ${alert.id}`}
                loading="lazy"
                style={{
                  width: '100%',
                  height: 130,
                  objectFit: 'cover',
                  borderRadius: 7,
                }}
              />
            ) : (
              <div
                role={error ? 'status' : undefined}
                style={{
                  height: 130,
                  display: 'grid',
                  placeItems: 'center',
                  textAlign: 'center',
                  fontSize: 12,
                  overflowWrap: 'anywhere',
                }}
              >
                {error || 'Không có ảnh'}
              </div>
            )}

            <p style={{ marginBottom: 4, fontWeight: 600 }}>
              {levelText(alert.level)}
            </p>
            <p style={{ marginBlock: 4, fontSize: 13 }}>
              {alert.message || alert.type}
            </p>
            <p style={{ marginBlock: 4, fontSize: 12, opacity: 0.75 }}>
              {formatTime(alert.createdAt)}
            </p>
          </article>
        ))}
      </div>
    </section>
  )
}

export default function DashboardAlerts({
  zoneId,
  onOpenAlerts,
}: {
  zoneId: number
  onOpenAlerts: () => void
}) {
  const [alerts, setAlerts] = useState<AlertRecord[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    let cancelled = false

    async function load() {
      setLoading(true)
      setError('')

      try {
        const houseResult = await api.getHouses(0, 100)
        const houses = houseResult.items.filter((house) => house.active)

        let matchingHouseId: number | null = null

        for (const house of houses) {
          const zoneResult = await api.getZones(house.id, 0, 100)
          if (zoneResult.items.some((zone) => zone.id === zoneId)) {
            matchingHouseId = house.id
            break
          }
        }

        if (matchingHouseId === null) {
          throw new Error(
            `Không tìm thấy nhà yến chứa khu vực ID ${zoneId}.`,
          )
        }

        const result = await api.getAlerts(matchingHouseId, {
          zoneId,
          page: 0,
          size: 100,
        })

        if (!cancelled) {
          setAlerts(
            [...result.items].sort(
              (a, b) =>
                Date.parse(b.createdAt) - Date.parse(a.createdAt),
            ),
          )
        }
      } catch (err) {
        if (!cancelled) {
          setError(
            err instanceof Error
              ? err.message
              : 'Không tải được cảnh báo từ Backend.',
          )
        }
      } finally {
        if (!cancelled) setLoading(false)
      }
    }

    void load()

    const timer = window.setInterval(() => {
      void load()
    }, 10000)

    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [zoneId])

  const unresolved = alerts.filter(
    (alert) => alert.status !== 'RESOLVED',
  )
  const critical = unresolved.filter(
    (alert) => alert.level === 'CRITICAL',
  )
  const recent = unresolved.slice(0, 5)

  return (
    <article className="panel" style={{ padding: 20, marginTop: 20 }}>
      <div className="panel-heading">
        <div>
          <h2>Cảnh báo gần đây</h2>
          <p>Cảnh báo thật từ Backend · Tự đồng bộ mỗi 10 giây</p>
        </div>

        <button
          type="button"
          className="alert-detail-button"
          onClick={onOpenAlerts}
        >
          Mở Alert Center <ArrowRight size={15} />
        </button>
      </div>

      <div className="metrics-grid" style={{ marginBottom: 16 }}>
        <div className="metric-card">
          <div className="metric-label">Chưa xử lý</div>
          <div className="metric-value">{unresolved.length}</div>
        </div>
        <div className="metric-card">
          <div className="metric-label">Nghiêm trọng</div>
          <div className="metric-value">{critical.length}</div>
        </div>
      </div>

      {loading && <p role="status">Đang tải cảnh báo...</p>}

      {error && (
        <p role="alert">
          Không tải được cảnh báo: {error}
        </p>
      )}

      {!loading && !error && recent.length === 0 && (
        <p>Hiện không có cảnh báo chưa xử lý trong khu vực này.</p>
      )}

      <div className="alert-list">
        {recent.map((alert) => (
          <div className="alert-row" key={alert.id}>
            <div
              className={`alert-symbol ${
                alert.level === 'CRITICAL' ? 'critical' : 'warning'
              }`}
            >
              <AlertTriangle size={18} />
            </div>
            <div className="alert-info">
              <strong>
                {alert.level === 'CRITICAL'
                  ? 'Nghiêm trọng'
                  : 'Cảnh báo'}{' '}
                · {alert.message || alert.type}
              </strong>
              <span>
                {alert.status === 'NEW'
                  ? 'Mới'
                  : 'Đang xử lý'}{' '}
                · {formatTime(alert.createdAt)}
              </span>
            </div>
          </div>
        ))}
      </div>

      <DashboardSnapshots alerts={alerts} />
    </article>
  )
}
