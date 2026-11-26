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

export function connectHouseAlertRealtime(
  houseId: number,
  onMessage: (message: RealtimeMessage) => void,
  onStatus?: (status: 'connected' | 'disconnected' | 'error') => void,
): WebSocket {
  const wsUrl = `${WS_BASE_URL}/ws/houses/${houseId}`
  console.log('[Alert WS] Connecting:', wsUrl)

  const socket = new WebSocket(wsUrl)

  socket.onopen = () => {
    console.log('[Alert WS] Connected')
    onStatus?.('connected')
  }

  socket.onmessage = (event: MessageEvent<string>) => {
    try {
      const message = JSON.parse(event.data) as RealtimeMessage

      console.log('[Alert WS] Message received:', message.type, message.data)

      onMessage(message)
    } catch (error) {
      console.error('[Alert WS] Invalid message:', error, event.data)
    }
  }

  socket.onclose = (event) => {
    console.warn('[Alert WS] Closed:', event.code, event.reason)
    onStatus?.('disconnected')
  }

  socket.onerror = (event) => {
    console.error('[Alert WS] Error:', event)
    onStatus?.('error')
  }

  return socket
}

