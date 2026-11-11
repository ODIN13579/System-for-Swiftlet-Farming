import { useEffect, useState } from 'react'
import { api, type CountLog, type Device } from '../services/api'
import { connectDeviceRealtime } from '../services/websocket'

function time(value: string) {
  return new Date(value).toLocaleString('vi-VN', { timeZone: 'Asia/Ho_Chi_Minh' })
}

export default function AiCounts({ cameras }: { cameras: Device[] }) {
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [rows, setRows] = useState<CountLog[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [connected, setConnected] = useState(false)
  const cameraId = cameras.find((camera) => camera.id === selectedId)?.id
    ?? cameras[0]?.id ?? null

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
    setLoading(cameraId !== null)
    if (cameraId === null) return
    const deviceId = cameraId

    async function load() {
      if (!active) return
      if (busy) { reload = true; return }
      busy = true
      try {
        const now = Date.now()
        const result = await api.getCountLogs(
          deviceId,
          new Date(now - 30 * 86400000).toISOString(),
          new Date(now).toISOString(),
          controller.signal,
        )
        if (active) { setRows(result.items); setError('') }
      } catch (err) {
        if (active) setError(err instanceof Error ? err.message : 'Không tải được kết quả AI.')
      } finally {
        busy = false
        if (active) {
          setLoading(false)
          if (reload) { reload = false; void load() }
        }
      }
    }

    function connect() {
      if (!active) return
      try {
        socket = connectDeviceRealtime(deviceId, (message) => {
          if (active && message.type === 'ai.count.updated') void load()
        }, (status) => {
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
        })
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
    <article className="panel" style={{ padding: 20, marginTop: 20 }}>
      <div className="panel-heading">
        <div>
          <h2>Kết quả đếm chim AI</h2>
          <p>Kỳ đếm mới nhất trong 30 ngày · Giờ Việt Nam</p>
        </div>
        <span className="subtle-badge">
          {connected ? 'AI realtime đã kết nối' : 'Đọc lại mỗi 10 giây'}
        </span>
      </div>

      {cameras.length > 0 && (
        <label>
          Camera{' '}
          <select value={cameraId ?? ''} onChange={(event) => setSelectedId(Number(event.target.value))}>
            {cameras.map((camera) => (
              <option key={camera.id} value={camera.id}>
                {camera.name || camera.deviceId} (ID {camera.id})
              </option>
            ))}
          </select>
        </label>
      )}

      {cameraId === null ? (
        <p>Chưa có camera trong danh sách thiết bị của khu này.</p>
      ) : loading ? (
        <p role="status">Đang tải kết quả AI...</p>
      ) : (
        <>
          {error && <p role="alert">Không tải được dữ liệu mới: {error}</p>}
          {latest ? (
            <>
              <div className="metrics-grid" style={{ marginTop: 16 }}>
                <div className="metric-card">
                  <div className="metric-label">Lượt vào</div>
                  <div className="metric-value">{latest.entryCount}</div>
                </div>
                <div className="metric-card">
                  <div className="metric-label">Lượt ra</div>
                  <div className="metric-value">{latest.exitCount}</div>
                </div>
              </div>
              <p>Kỳ đếm: {time(latest.periodStart)} → {time(latest.periodEnd)}</p>
              <p>Loại: {latest.aggregationType} · Xử lý lúc: {time(latest.processedAt)}</p>
              <p>Đây là số liệu của kỳ trên, không phải tổng đàn hiện có hoặc tổng hôm nay.</p>
              <details>
                <summary>10 kỳ gần nhất trong khoảng tìm kiếm</summary>
                <div style={{ overflowX: 'auto' }}>
                  <table style={{ width: '100%', textAlign: 'left' }}>
                    <thead><tr><th>Bắt đầu</th><th>Kết thúc</th><th>Loại</th><th>Vào</th><th>Ra</th></tr></thead>
                    <tbody>{rows.map((row) => (
                      <tr key={row.id}>
                        <td>{time(row.periodStart)}</td><td>{time(row.periodEnd)}</td>
                        <td>{row.aggregationType}</td><td>{row.entryCount}</td><td>{row.exitCount}</td>
                      </tr>
                    ))}</tbody>
                  </table>
                </div>
              </details>
            </>
          ) : !error ? <p>Chưa có kết quả AI trong 30 ngày gần đây.</p> : null}
        </>
      )}
    </article>
  )
}
