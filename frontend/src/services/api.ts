
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
export type Telemetry = {
  timestamp?: string
  temperature?: number | null
  humidity?: number | null
  co2?: number | null
  light?: number | null
  [key: string]: unknown
}
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
let accessToken: string | null = null
export function setAccessToken(token: string | null) {
  accessToken = token
}
async function request<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const headers = new Headers(options.headers)

  headers.set('Content-Type', 'application/json')

  if (accessToken) {
    headers.set('Authorization', `Bearer ${accessToken}`)
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...options,
    headers,
    credentials: 'include',
  })

  if (!response.ok) {
    const message = await response.text()
    throw new Error(
      `API ${response.status}: ${message || response.statusText}`,
    )
  }

  if (response.status === 204) {
    return undefined as T
  }

  return response.json() as Promise<T>
}
export const api = {
  login(email: string, password: string) {
    return request<LoginResponse>('/api/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
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
}
