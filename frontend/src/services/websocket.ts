import { API_BASE_URL } from './api'

const WS_BASE_URL =
  import.meta.env.VITE_WS_BASE_URL || API_BASE_URL.replace(/^http/, 'ws').replace(/\/$/, '')

export type RealtimeMessage = {
  type: string
  data?: unknown
}

export function connectDeviceRealtime(
  deviceId: number,
  onMessage: (message: RealtimeMessage) => void,
  onStatus?: (status: 'connected' | 'disconnected' | 'error') => void,
  onClose?: (event: CloseEvent) => void,
): WebSocket {
  const wsUrl = `${WS_BASE_URL}/ws/devices/${deviceId}`


  const socket = new WebSocket(wsUrl)

  socket.onopen = () => {
    onStatus?.('connected')
  }

  socket.onmessage = (event: MessageEvent<string>) => {
    try {
      const message = JSON.parse(event.data) as RealtimeMessage
      onMessage(message)
    } catch {
      return
    }
  }

  socket.onclose = event => {
    onStatus?.('disconnected')
    onClose?.(event)
  }

  socket.onerror = () => {
    onStatus?.('error')
  }

  return socket
}

export function connectHouseAlertRealtime(
  houseId: number,
  onMessage: (message: RealtimeMessage) => void,
  onStatus?: (status: 'connected' | 'disconnected' | 'error') => void,
  onClose?: (event: CloseEvent) => void,
): WebSocket {
  const socket = new WebSocket(`${WS_BASE_URL}/ws/houses/${houseId}`)
  socket.onopen = () => onStatus?.('connected')
  socket.onmessage = (event: MessageEvent<string>) => {
    let message: RealtimeMessage
    try { message = JSON.parse(event.data) as RealtimeMessage } catch { return }
    if (message && typeof message.type === 'string') onMessage(message)
  }
  socket.onclose = event => {
    onClose?.(event)
    onStatus?.('disconnected')
  }
  socket.onerror = () => onStatus?.('error')
  return socket
}
