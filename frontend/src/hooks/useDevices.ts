
import { useCallback, useEffect, useState } from 'react'
import { api, type Device } from '../services/api'

export function useDevices(zoneId: number | null) {
  const [devices, setDevices] = useState<Device[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const refresh = useCallback(async () => {
    if (zoneId === null) {
      setDevices([])
      setError('Chưa xác định khu vực cần tải thiết bị.')
      return
    }

    setLoading(true)
    setError(null)

    try {
      const result = await api.getDevices(zoneId)

      // Điều chỉnh theo cấu trúc JSON thực tế của Backend.
      setDevices(result.items)
    } catch (err) {
      setError(
        err instanceof Error
          ? err.message
          : 'Không thể tải danh sách thiết bị.',
      )
    } finally {
      setLoading(false)
    }
  }, [zoneId])

  useEffect(() => {
    void refresh()
  }, [refresh])

  return { devices, loading, error, refresh }
}
