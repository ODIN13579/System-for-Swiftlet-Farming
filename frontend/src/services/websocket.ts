
const WS_BASE_URL =
  import.meta.env.VITE_WS_BASE_URL || 'ws://localhost:8080'

export type RealtimeMessage = {
  type: string
  data?: unknown
}

export function connectDeviceRealtime(
  deviceId: number,
  onMessage: (message: RealtimeMessage) => void,
  onStatus?: (status: 'connected' | 'disconnected' | 'error') => void,
): WebSocket {
  const wsUrl = `${WS_BASE_URL}/ws/devices/${deviceId}`

    console.log('WebSocket URL:', wsUrl)

    const socket = new WebSocket(wsUrl)

  socket.onopen = () => {
    onStatus?.('connected')
    console.log(`WebSocket connected to device ${deviceId}`)
  }

  socket.onmessage = (event: MessageEvent<string>) => {
    try {
      const message = JSON.parse(event.data) as RealtimeMessage
      onMessage(message)
    } catch (error) {
      console.error('Invalid WebSocket message:', error)
    }
  }

  socket.onclose = () => {
    onStatus?.('disconnected')
    console.log(`WebSocket disconnected from device ${deviceId}`)
  }

  socket.onerror = () => {
    onStatus?.('error')
    console.error(`WebSocket error for device ${deviceId}`)
  }

  return socket
}
