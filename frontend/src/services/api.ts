
const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080'

export type LoginResponse = {
  accessToken: string
  tokenType: string
  expiresIn: number
}

export type Device = {
  id: number
  deviceId: string
  zoneId: number
  name: string
  type: string
  enabled: boolean
  online: boolean
  lastSeenAt: string | null
}

export type Actuator = {
  id: number
  deviceId: number
  kind: string
  mode: 'AUTO' | 'MANUAL' | string
  on: boolean
  manualUntil: string | null
}

export type CommandCreated = {
  commandId: string
  status: string
  expiresAt: string
}

export type ControlCommandStatus = {
  commandId?: string
  status: string
  failureReason?: string | null
  ackAt?: string | null
}

export type House = {
  id: number
  name: string
  address: string | null
  description: string | null
  active: boolean
}

export type Zone = {
  id: number
  houseId: number
  name: string
  floorLabel: string | null
  areaM2: number | null
  active: boolean
}

export type HouseInput = {
  name: string
  address: string
  description?: string | null
  initialZone: ZoneInput
}

export type HouseUpdateInput = {
  name?: string
  address?: string
  description?: string
  active?: boolean
}

export type ZoneInput = {
  name: string
  floorLabel: string
  areaM2: number | null
}

export type ZoneUpdateInput = {
  name?: string
  floorLabel?: string
  areaM2?: number
  active?: boolean
}

export type DeviceInput = {
  deviceId: string
  name: string
  type: string
  enabled: boolean
}

export type DeviceUpdateInput = {
  name?: string
  enabled?: boolean
}

export type Telemetry = {
  timestamp?: string
  temperature?: number | null
  humidity?: number | null
  co2?: number | null
  light?: number | null
  [key: string]: unknown
}

export type ZoneConfig = {
  id: number
  zoneId: number
  humidityOn: number | null
  humidityOff: number | null
  tempMin: number | null
  tempMax: number | null
  tempHysteresis: number | null
  co2Min: number | null
  co2Max: number | null
  lightMin: number | null
  lightMax: number | null
  telemetryIntervalSec: number
  heartbeatIntervalSec: number
  version: number
  updatedAt: string
  syncStatus: string
}

export type ZoneConfigInput = Omit<
  ZoneConfig,
  'id' | 'zoneId' | 'updatedAt' | 'syncStatus'
>

export type PageInfo = {
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export type ListResponse<T> = {
  items: T[]
  page: PageInfo
}

export type CountLog = {
  id: number
  deviceId: number
  periodStart: string
  periodEnd: string
  entryCount: number
  exitCount: number
  detectCount: number
  aggregationType: 'HOURLY' | 'DAILY'
  processedAt: string
}

let accessToken: string | null = null

export function setAccessToken(token: string | null) {
  accessToken = token
}

async function request<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const headers = new Headers(options.headers)

  if (options.body != null && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }

  if (accessToken) {
    headers.set('Authorization', `Bearer ${accessToken}`)
  }

  let response: Response

  try {
    response = await fetch(`${API_BASE_URL}${path}`, {
      ...options,
      headers,
      credentials: 'include',
    })
  } catch {
    throw new Error(
      'Không thể kết nối Backend. Hãy kiểm tra server, địa chỉ API và kết nối mạng.',
    )
  }

  if (!response.ok) {
    const responseText = await response.text().catch(() => '')
    let message = responseText || response.statusText

    // Một số API trả lỗi ở dạng JSON.
    if (responseText) {
      try {
        const body: unknown = JSON.parse(responseText)

        if (typeof body === 'object' && body !== null) {
          const data = body as Record<string, unknown>
          const apiMessage =
            data.message ?? data.error ?? data.detail

          if (typeof apiMessage === 'string' && apiMessage.trim()) {
            message = apiMessage
          }
        }
      } catch {
        // Nếu không phải JSON thì dùng nội dung lỗi dạng văn bản.
      }
    }

    throw new Error(`API ${response.status}: ${message}`)
  }

  if (response.status === 204) {
    return undefined as T
  }

  const responseText = await response.text()

  if (!responseText.trim()) {
    return undefined as T
  }

  try {
    return JSON.parse(responseText) as T
  } catch {
    throw new Error(`API ${path} trả về dữ liệu không phải JSON hợp lệ.`)
  }
}

export const api = {
  getCountLogs(deviceId: number, from: string, to: string, signal?: AbortSignal) {
    const params = new URLSearchParams({ from, to, page: '0', size: '10' })
    return request<ListResponse<CountLog>>(
      `/api/v1/devices/${deviceId}/count-logs?${params}`,
      { signal },
    )
  },
  login(email: string, password: string) {
    return request<LoginResponse>('/api/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    })
  },

  getHouses(page = 0, size = 20) {
    const params = new URLSearchParams({
      page: String(page),
      size: String(size),
    })

    return request<ListResponse<House>>(
      `/api/v1/houses?${params}`,
    )
  },

  getHouse(houseId: number) {
    return request<House>(`/api/v1/houses/${houseId}`)
  },

  createHouse(data: HouseInput) {
    return request<House>('/api/v1/houses', {
      method: 'POST',
      body: JSON.stringify(data),
    })
  },

  updateHouse(houseId: number, data: HouseUpdateInput) {
    return request<House>(`/api/v1/houses/${houseId}`, {
      method: 'PATCH',
      body: JSON.stringify(data),
    })
  },

  getZones(houseId: number, page = 0, size = 20) {
    const params = new URLSearchParams({
      page: String(page),
      size: String(size),
    })

    return request<ListResponse<Zone>>(
      `/api/v1/houses/${houseId}/zones?${params}`,
    )
  },

  getZone(zoneId: number) {
    return request<Zone>(`/api/v1/zones/${zoneId}`)
  },

  createZone(houseId: number, data: ZoneInput) {
    return request<Zone>(
      `/api/v1/houses/${houseId}/zones`,
      {
        method: 'POST',
        body: JSON.stringify(data),
      },
    )
  },

  updateZone(zoneId: number, data: ZoneUpdateInput) {
    return request<Zone>(`/api/v1/zones/${zoneId}`, {
      method: 'PATCH',
      body: JSON.stringify(data),
    })
  },

  getDevices(zoneId: number, page = 0, size = 20) {
    const params = new URLSearchParams({
      page: String(page),
      size: String(size),
    })

    return request<ListResponse<Device>>(
      `/api/v1/zones/${zoneId}/devices?${params}`,
    )
  },

  getDevice(deviceId: number) {
    return request<Device>(`/api/v1/devices/${deviceId}`)
  },

  createDevice(zoneId: number, data: DeviceInput) {
    return request<Device>(
      `/api/v1/zones/${zoneId}/devices`,
      {
        method: 'POST',
        body: JSON.stringify(data),
      },
    )
  },

  updateDevice(deviceId: number, data: DeviceUpdateInput) {
    return request<Device>(`/api/v1/devices/${deviceId}`, {
      method: 'PATCH',
      body: JSON.stringify(data),
    })
  },

  getLatestTelemetry(deviceId: number) {
    return request<Telemetry>(
      `/api/v1/devices/${deviceId}/telemetry/latest`,
    )
  },

  getTelemetryHistory(
    deviceId: number,
    from: string,
    to: string,
    page = 0,
    size = 20,
  ) {
    const params = new URLSearchParams({
      from,
      to,
      page: String(page),
      size: String(size),
    })

    return request<ListResponse<Telemetry>>(
      `/api/v1/devices/${deviceId}/telemetry?${params}`,
    )
  },

  getZoneConfig(zoneId: number) {
    return request<ZoneConfig>(
      `/api/v1/zones/${zoneId}/config`,
    )
  },

  updateZoneConfig(zoneId: number, data: ZoneConfigInput) {
    return request<ZoneConfig>(
      `/api/v1/zones/${zoneId}/config`,
      {
        method: 'PUT',
        body: JSON.stringify(data),
      },
    )
  },

  getActuators(deviceId: number, page = 0, size = 20) {
    const params = new URLSearchParams({
      page: String(page),
      size: String(size),
    })

    return request<ListResponse<Actuator>>(
      `/api/v1/devices/${deviceId}/actuators?${params}`,
    )
  },

  createActuatorCommand(
    actuatorId: number,
    data:
      | { action: 'SET_MODE'; targetMode: 'AUTO' | 'MANUAL'; targetState?: never }
      | { action: 'SET_STATE'; targetState: 'ON' | 'OFF'; targetMode?: never },
  ) {
    return request<CommandCreated>(
      `/api/v1/actuators/${actuatorId}/commands`,
      {
        method: 'POST',
        body: JSON.stringify(data),
      },
    )
  },

  getCommandStatus(commandId: string) {
    return request<ControlCommandStatus>(
      `/api/v1/commands/${encodeURIComponent(commandId)}`,
    )
  },

}
