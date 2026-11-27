
const NOTIFICATION_TITLE = 'SwiftletCare'

export function isNotificationSupported(): boolean {
  return typeof window !== 'undefined' && 'Notification' in window
}

export async function requestNotificationPermission(): Promise<
  NotificationPermission | 'unsupported'
> {
  if (!isNotificationSupported()) return 'unsupported'

  if (Notification.permission === 'granted') return 'granted'
  if (Notification.permission === 'denied') return 'denied'

  return Notification.requestPermission()
}

export function showAlertNotification(
  title: string,
  message: string,
  alertId: number,
  enabled: boolean,
  onOpen: (id: number) => void,
): boolean {
  if (!enabled || !isNotificationSupported()) return false
  if (Notification.permission !== 'granted') return false

  let notification: Notification
  try { notification = new Notification(
    `${NOTIFICATION_TITLE} — ${title}`,
    {
      body: message,
      icon: '/favicon.ico',
      tag: `swiftletcare-alert-${alertId}`,
    },
  ) } catch { return false }

  notification.onclick = () => {
    window.focus()
    onOpen(alertId)
    notification.close()
  }
  return true
}
