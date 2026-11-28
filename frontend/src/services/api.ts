
export const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080'

export type LoginResponse = {
  accessToken: string
  tokenType: string
  expiresIn: number
}

export type Account = {
  id: number
  email: string
  fullName: string | null
  phone: string | null
  role: 'OWNER' | 'ADMIN' | string
  active: boolean
}

export type CreateAccountInput = {
  email: string
  password: string
  role: 'OWNER' | 'ADMIN'
  fullName?: string
  phone?: string
  houseId?: number
  houseRole?: 'OWNER' | 'ADMIN' | 'VIEWER'
}

export type UpdateProfileInput = {
  fullName?: string | null
  phone?: string | null
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
  stateReceivedAt?: string | null
  stateFresh?: boolean
  safetyOverride?: boolean | null
  canControl?: boolean
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
export type AlertStatus = 'NEW' | 'IN_PROGRESS' | 'RESOLVED'
export type AlertLevel = 'WARNING' | 'CRITICAL'

export type AlertRecord = {
  id: number
  houseId: number
  zoneId: number
  deviceId: number | null
  anomalyEventId: number | null
  snapshotId: number | null
  type: string
  level: AlertLevel
  status: AlertStatus
  message: string
  createdAt: string
  handledAt: string | null
  clearedAt: string | null
}

export type AlertHistory = {
  id: number
  changedBy: number | null
  fromStatus: AlertStatus | null
  toStatus: AlertStatus
  changedAt: string
  note: string | null
}
let accessToken: string | null = null

export function setAccessToken(token: string | null) {
  accessToken = token
}

export class ApiError extends Error {
  readonly status: number
  readonly code?: string

  constructor(status: number, code: string | undefined, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

export function endSession() {
  accessToken = null
  window.dispatchEvent(new Event('swiftletcare:session-ended'))
}

export type EnvironmentPoint = {
  timestamp: string
  sampleCount: number
} & Partial<Record<`${'avg' | 'min' | 'max'}${'Temperature' | 'Humidity' | 'Co2' | 'Light'}`, number | null>>

async function request<T>(
  path: string,
  options: RequestInit = {},
  blob = false,
): Promise<T> {
  const headers = new Headers(options.headers)

  if (options.body != null && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }

  const token = accessToken
  if (token) {
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
    if (options.signal?.aborted) throw options.signal.reason
    throw new Error(
      'Không thể kết nối Backend. Hãy kiểm tra server, địa chỉ API và kết nối mạng.',
    )
  }

  if (!response.ok) {
    if (response.status === 401 && token && accessToken === token) {
      endSession()
    }
    const responseText = await response.text().catch(() => '')
    let code: string | undefined
    let message = responseText || response.statusText

    // Một số API trả lỗi ở dạng JSON.
    if (responseText) {
      try {
        const body: unknown = JSON.parse(responseText)

        if (typeof body === 'object' && body !== null) {
          const data = body as Record<string, unknown>
          code = typeof data.code === 'string' ? data.code : undefined
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

    throw new ApiError(response.status, code, `API ${response.status}: ${message}`)
  }

  if (blob) return (await response.blob()) as T

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

  getHouses(page = 0, size = 20, signal?: AbortSignal) {
    const params = new URLSearchParams({
      page: String(page),
      size: String(size),
    })

    return request<ListResponse<House>>(
      `/api/v1/houses?${params}`,
      { signal },
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

  getZones(houseId: number, page = 0, size = 20, signal?: AbortSignal) {
    const params = new URLSearchParams({
      page: String(page),
      size: String(size),
    })

    return request<ListResponse<Zone>>(
      `/api/v1/houses/${houseId}/zones?${params}`,
      { signal },
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

  getDevices(zoneId: number, page = 0, size = 20, signal?: AbortSignal) {
    const params = new URLSearchParams({
      page: String(page),
      size: String(size),
    })

    return request<ListResponse<Device>>(
      `/api/v1/zones/${zoneId}/devices?${params}`,
      { signal },
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

  getLatestTelemetry(deviceId: number, signal?: AbortSignal) {
    return request<Telemetry>(
      `/api/v1/devices/${deviceId}/telemetry/latest`, { signal },
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

  getZoneConfig(zoneId: number, signal?: AbortSignal) {
    return request<ZoneConfig>(
      `/api/v1/zones/${zoneId}/config`,
      { signal },
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

  getActuators(deviceId: number, page = 0, size = 20, signal?: AbortSignal) {
    const params = new URLSearchParams({
      page: String(page),
      size: String(size),
    })

    return request<ListResponse<Actuator>>(
      `/api/v1/devices/${deviceId}/actuators?${params}`,
      { signal },
    )
  },

  createActuatorCommand(
    actuatorId: number,
    data:
      | { action: 'SET_MODE'; targetMode: 'AUTO' | 'MANUAL'; targetState?: never }
      | { action: 'SET_STATE'; targetState: boolean; targetMode?: never },
    signal?: AbortSignal,
  ) {
    return request<CommandCreated>(
      `/api/v1/actuators/${actuatorId}/commands`,
      {
        method: 'POST',
        signal,
        body: JSON.stringify(data),
      },
    )
  },

  getCommandStatus(commandId: string, signal?: AbortSignal) {
    return request<ControlCommandStatus>(
      `/api/v1/commands/${encodeURIComponent(commandId)}`,
      { signal },
    )
  },

  getMe() {
    return request<Account>('/api/v1/me')
  },

  updateMe(data: UpdateProfileInput) {
    return request<Account>('/api/v1/me', {
      method: 'PATCH',
      body: JSON.stringify(data),
    })
  },

  changePassword(currentPassword: string, newPassword: string) {
    return request<void>('/api/v1/me/change-password', {
      method: 'POST',
      body: JSON.stringify({ currentPassword, newPassword }),
    })
  },

  logout() {
    return request<void>('/api/v1/auth/logout', {
      method: 'POST',
    })
  },

  getAccounts(page = 0, size = 20) {
    const params = new URLSearchParams({
      page: String(page),
      size: String(size),
    })

    return request<ListResponse<Account>>(
      `/api/v1/admin/users?${params}`,
    )
  },

  createAccount(data: CreateAccountInput) {
    return request<Account>('/api/v1/admin/users', {
      method: 'POST',
      body: JSON.stringify(data),
    })
  },

  setAccountActive(id: number, active: boolean) {
    return request<Account>(`/api/v1/admin/users/${id}/active`, {
      method: 'PATCH',
      body: JSON.stringify({ active }),
    })
  },

  getAlerts(
    houseId: number,
    filters: {
      zoneId?: number
      status?: AlertStatus
      level?: AlertLevel
      page?: number
      size?: number
    } = {},
    signal?: AbortSignal,
  ) {
    const params = new URLSearchParams({
      houseId: String(houseId),
      page: String(filters.page ?? 0),
      size: String(filters.size ?? 100),
    })

    if (filters.zoneId !== undefined) {
      params.set('zoneId', String(filters.zoneId))
    }
    if (filters.status) params.set('status', filters.status)
    if (filters.level) params.set('level', filters.level)

    return request<ListResponse<AlertRecord>>(
      `/api/v1/alerts?${params}`,
      { signal },
    )
  },

  getAlert(id: number, signal?: AbortSignal) {
    return request<AlertRecord>(
      `/api/v1/alerts/${id}`,
      { signal },
    )
  },

  getAlertHistory(id: number, signal?: AbortSignal) {
    return request<AlertHistory[]>(
      `/api/v1/alerts/${id}/history`,
      { signal },
    )
  },

  updateAlertStatus(
    id: number,
    status: AlertStatus,
    note?: string,
  ) {
    return request<AlertRecord>(
      `/api/v1/alerts/${id}/status`,
      {
        method: 'PATCH',
        body: JSON.stringify({ status, note }),
      },
    )
  },

  getSnapshotBlob(id: number, signal?: AbortSignal) {
    return request<Blob>(`/api/snapshots/${id}`, { signal }, true)
  },

  getEnvironmentReport(params: URLSearchParams, signal?: AbortSignal) {
    return request<{ points: EnvironmentPoint[] }>(`/api/v1/reports/environment?${params}`, { signal })
  },

  getEnvironmentCsv(params: URLSearchParams) {
    return request<Blob>(`/api/v1/reports/environment.csv?${params}`, {}, true)
  },

}
