import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react'
import { api, type House } from '../services/api'
import { connectHouseAlertRealtime } from '../services/websocket'
import { showAlertNotification } from '../services/browserNotifications'

type Status = 'connected' | 'disconnected' | 'error'
type Session = {
  houses: House[]
  houseId: number | ''
  selectHouse: (id: number | '') => void
  loadingHouses: boolean
  houseError: string
  revision: number
  status: Status
}
const AlertContext = createContext<Session | null>(null)

export function useAlertSession() {
  const value = useContext(AlertContext)
  if (!value) throw new Error('Missing AlertSession')
  return value
}

export function AlertSession({ children, onOpenAlert }: {
  children: ReactNode
  onOpenAlert: (id: number) => void
}) {
  const [houses, setHouses] = useState<House[]>([])
  const [houseId, selectHouse] = useState<number | ''>('')
  const [loadingHouses, setLoading] = useState(true)
  const [houseError, setHouseError] = useState('')
  const [revision, setRevision] = useState(0)
  const [status, setStatus] = useState<Status>('disconnected')
  const notified = useRef(new Set<number>())
  const onOpen = useRef(onOpenAlert)

  useEffect(() => { onOpen.current = onOpenAlert }, [onOpenAlert])
  useEffect(() => {
    const abort = new AbortController()
    async function load() {
      try {
        const items: House[] = []
        for (let page = 0; !abort.signal.aborted; page++) {
          const result = await api.getHouses(page, 100, abort.signal)
          items.push(...result.items.filter(house => house.active))
          if (page + 1 >= result.page.totalPages) break
        }
        if (abort.signal.aborted) return
        setHouses(items)
        selectHouse(items[0]?.id ?? '')
      } catch (error) {
        if (!abort.signal.aborted) setHouseError(error instanceof Error ? error.message : 'Không tải được nhà yến.')
      } finally {
        if (!abort.signal.aborted) setLoading(false)
      }
    }
    void load()
    return () => abort.abort()
  }, [])

  useEffect(() => {
    if (houseId === '' || !houses.some(house => house.id === houseId)) return
    let disposed = false
    let socket: WebSocket | undefined
    let timer: number | undefined
    let delay = 1000
    function retry() {
      if (disposed || timer !== undefined) return
      timer = window.setTimeout(() => {
        timer = undefined
        connect()
      }, delay)
      delay = Math.min(delay * 2, 15000)
    }
    function connect() {
      if (disposed) return
      try {
        socket = connectHouseAlertRealtime(Number(houseId), message => {
          if (disposed || !['alert.created', 'alert.updated'].includes(message.type)) return
          const alert = message.data as {id?: number; houseId?: number; type?: string; level?: string; message?: string} | undefined
          if (!alert || alert.houseId !== houseId || !Number.isSafeInteger(alert.id) || !alert.id || alert.id < 1) return
          setRevision(value => value + 1)
          if (message.type === 'alert.created' && ['WARNING', 'CRITICAL'].includes(alert.level ?? '') &&
              !notified.current.has(alert.id) &&
              showAlertNotification(alert.type ?? 'Cảnh báo', alert.message ?? '', alert.id,
                localStorage.getItem('swiftletcare-notifications-enabled') === 'true',
                id => onOpen.current(id))) {
            notified.current.add(alert.id)
          }
        }, next => {
          if (disposed) return
          setStatus(next)
          if (next === 'connected') {
            delay = 1000
            setRevision(value => value + 1)
          } else if (next === 'disconnected') {
            retry()
          }
        }, event => {
          if (event.code === 1008) {
            disposed = true
            setStatus('error')
          }
        })
      } catch {
        setStatus('error')
        retry()
      }
    }
    connect()
    const poll = window.setInterval(() => setRevision(value => value + 1), 10000)
    return () => {
      disposed = true
      if (timer !== undefined) window.clearTimeout(timer)
      window.clearInterval(poll)
      socket?.close()
      setStatus('disconnected')
    }
  }, [houseId, houses])

  return <AlertContext.Provider value={{
    houses, houseId, selectHouse, loadingHouses, houseError, revision, status,
  }}>{children}</AlertContext.Provider>
}
