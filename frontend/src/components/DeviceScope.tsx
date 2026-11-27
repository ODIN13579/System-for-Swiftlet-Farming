
import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import { api, type Device, type ListResponse, type Zone } from '../services/api'
import { useAlertSession } from './AlertSession'

async function allPages<T>(load: (page: number) => Promise<ListResponse<T>>, signal: AbortSignal) {
  const items: T[] = []
  for (let page = 0; !signal.aborted; page++) {
    const result = await load(page)
    items.push(...result.items)
    if (page + 1 >= result.page.totalPages) break
  }
  return items
}

function useScope(active: boolean) {
  const { houses, houseId, selectHouse, loadingHouses, houseError } = useAlertSession()
  const [zoneResult, setZoneResult] = useState<{ houseId: number; items: Zone[]; error?: string }>()
  const [deviceResult, setDeviceResult] = useState<{ zoneId: number; items: Device[]; error?: string }>()
  const [selection, setSelection] = useState<{ houseId: number; zoneId: number; deviceId?: number }>()
  const [revision, setRevision] = useState(0)
  const zones = zoneResult?.houseId === houseId ? zoneResult.items : []
  const zoneId = zones.find(zone => selection?.houseId === houseId && zone.id === selection.zoneId)?.id ?? zones[0]?.id
  const devices = deviceResult && deviceResult.zoneId === zoneId ? deviceResult.items : []
  const sensors = devices.filter(device => device.type === 'ESP32')
  const device = sensors.find(sensor => selection?.zoneId === zoneId && sensor.id === selection?.deviceId) ?? sensors[0]
  useEffect(() => {
    if (!active || houseId === '') return
    const controller = new AbortController()
    void allPages(page => api.getZones(houseId, page, 100, controller.signal), controller.signal)
      .then(items => {
        if (!controller.signal.aborted) setZoneResult({ houseId, items: items.filter(zone => zone.active) })
      }).catch(error => {
        if (!controller.signal.aborted) setZoneResult({ houseId, items: [], error: String(error) })
      })
    return () => controller.abort()
  }, [active, houseId, revision])

  useEffect(() => {
    if (!active || zoneId === undefined) return
    const controller = new AbortController()
    let timer: number | undefined
    async function refresh(id: number) {
      try {
        const items = await allPages(page => api.getDevices(id, page, 100, controller.signal), controller.signal)
        if (!controller.signal.aborted) setDeviceResult({ zoneId: id, items })
      } catch (error) {
        if (!controller.signal.aborted) setDeviceResult(previous => ({
          zoneId: id, items: previous?.zoneId === id ? previous.items : [], error: String(error),
        }))
      } finally {
        if (!controller.signal.aborted) timer = window.setTimeout(() => void refresh(id), 3000)
      }
    }
    void refresh(zoneId)
    return () => { controller.abort(); window.clearTimeout(timer) }
  }, [active, zoneId, revision])

  return {
    houses, houseId, selectHouse, zones, zoneId, devices, device, sensors,
    selectZone: (id: number) => { if (houseId !== '') setSelection({ houseId, zoneId: id }) },
    selectDevice: (id: number) => { if (houseId !== '' && zoneId !== undefined) setSelection({ houseId, zoneId, deviceId: id }) },
    reload: () => setRevision(value => value + 1),
    loading: loadingHouses || (houseId !== '' && zoneResult?.houseId !== houseId) ||
      (zoneId !== undefined && deviceResult?.zoneId !== zoneId),
    error: houseError || (zoneResult?.houseId === houseId && zoneResult.error) ||
      (deviceResult?.zoneId === zoneId && deviceResult?.error) || '',
  }
}

const Context = createContext<ReturnType<typeof useScope> | null>(null)

export function DeviceScope({ active, children }: { active: boolean; children: ReactNode }) {
  return <Context.Provider value={useScope(active)}>{children}</Context.Provider>
}

export function useDeviceScope() {
  const scope = useContext(Context)
  if (!scope) throw new Error('Missing DeviceScope')
  return scope
}

export function DeviceSelectors() {
  const scope = useDeviceScope()
  return <div className="alerts-filters">
    <select aria-label="Nhà yến" value={scope.houseId} onChange={event => scope.selectHouse(Number(event.target.value) || '')}>
      <option value="">Chọn nhà yến</option>
      {scope.houses.map(house => <option key={house.id} value={house.id}>{house.name}</option>)}
    </select>
    <select aria-label="Khu vực" value={scope.zoneId ?? ''} disabled={!scope.zones.length} onChange={event => scope.selectZone(Number(event.target.value))}>
      <option value="" disabled>Chọn khu vực</option>
      {scope.zones.map(zone => <option key={zone.id} value={zone.id}>{zone.name}</option>)}
    </select>
    <select aria-label="ESP32" value={scope.device?.id ?? ''} disabled={!scope.sensors.length} onChange={event => scope.selectDevice(Number(event.target.value))}>
      <option value="" disabled>Chọn ESP32</option>
      {scope.sensors.map(device => <option key={device.id} value={device.id}>{device.name}</option>)}
    </select>
  </div>
}
