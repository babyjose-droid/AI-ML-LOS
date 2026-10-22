// Phone build: push notifications for new tasks. The phone's Expo push token is registered with the server, which
// pushes each new My work task. In Expo Go (or without an EAS project id) remote push is not available, so new alerts
// found while the app is open or in the background are shown as local notifications instead.
import Constants from 'expo-constants'
import * as Device from 'expo-device'
import * as Notifications from 'expo-notifications'
import { Platform } from 'react-native'
import { api } from './api'

Notifications.setNotificationHandler({
  handleNotification: async () => ({ shouldShowBanner: true, shouldShowList: true, shouldPlaySound: true, shouldSetBadge: true }),
})

let token: string | null = null

async function allowed(): Promise<boolean> {
  if (Platform.OS === 'android') await Notifications.setNotificationChannelAsync('tasks', { name: 'New tasks', importance: Notifications.AndroidImportance.HIGH })
  const cur = await Notifications.getPermissionsAsync()
  if (cur.granted) return true
  return (await Notifications.requestPermissionsAsync()).granted
}

/** Returns the push token registered with the server, or null when push is not available on this phone. */
export async function registerPush(): Promise<string | null> {
  try {
    if (!Device.isDevice || !(await allowed())) return null
    const projectId = (Constants.expoConfig?.extra as { eas?: { projectId?: string } } | undefined)?.eas?.projectId ?? Constants.easConfig?.projectId
    if (!projectId) return null
    token = (await Notifications.getExpoPushTokenAsync({ projectId })).data
    await api.post('/api/me/push-token', { token, platform: Platform.OS })
    return token
  } catch {
    return null   // Expo Go on Android, no network: local alerts are used
  }
}

export async function unregisterPush(): Promise<void> {
  if (!token) return
  try { await api.send('DELETE', '/api/me/push-token?token=' + encodeURIComponent(token)) } catch { /* signed out anyway */ }
  token = null
}

export async function showLocal(title: string, body: string, applicationId?: number): Promise<void> {
  try {
    if (!(await allowed())) return
    await Notifications.scheduleNotificationAsync({ content: { title, body, data: applicationId ? { applicationId } : {} }, trigger: null })
  } catch { /* not shown */ }
}

/** Tapping a notification opens its application. */
export function onOpen(f: (applicationId?: number) => void): () => void {
  const sub = Notifications.addNotificationResponseReceivedListener(r => {
    const id = Number((r.notification.request.content.data as { applicationId?: number } | undefined)?.applicationId)
    f(Number.isFinite(id) && id > 0 ? id : undefined)
  })
  return () => sub.remove()
}
