
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
): void {
  if (!enabled || !isNotificationSupported()) return
  if (Notification.permission !== 'granted') return

  const notification = new Notification(
    `${NOTIFICATION_TITLE} — ${title}`,
    {
      body: message,
      icon: '/favicon.ico',
      tag: `swiftletcare-alert-${alertId}`,
    },
  )

  notification.onclick = () => {
    window.focus()
    window.location.href = `/alerts?alertId=${alertId}`
    notification.close()
  }
}
