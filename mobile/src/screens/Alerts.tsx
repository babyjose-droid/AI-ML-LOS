// Alerts: the officer's new tasks (also pushed to the phone). Tapping one opens the application.
import React, { useEffect } from 'react'
import { RefreshControl, ScrollView, Text, View } from 'react-native'
import { useSession } from '../session'
import { Btn, C, Card, ListItem, Muted, Note, Screen, label, useNav, when } from '../ui'

export default function AlertsScreen() {
  const nav = useNav()
  const { alerts, refreshAlerts, readAlerts, pushToken } = useSession()
  useEffect(() => { refreshAlerts() }, [refreshAlerts])
  const list = alerts?.alerts ?? []
  const unread = list.filter(a => !a.readAt)
  return (
    <Screen title="Alerts" sub="New tasks for you" scroll={false} testID="alerts">
      <ScrollView refreshControl={<RefreshControl refreshing={false} onRefresh={refreshAlerts} />}>
        {pushToken ? <Note kind="ok">Push notifications are on for this phone.</Note>
          : <Muted>New tasks show here and as notifications while the app is open or in the background{alerts?.devices ? '' : '. Install the Rhythm app build for push notifications when it is closed'}.</Muted>}
        {unread.length > 0 && <Btn small title="Mark all as read" testID="read-all" onPress={() => readAlerts()} />}
        {list.length === 0 ? <Card><Muted>No alerts. New tasks appear here as they reach you.</Muted></Card> : null}
        {list.map(a => (
          <View key={a.id} style={{ opacity: a.readAt ? 0.6 : 1 }}>
            <ListItem testID={'alert-' + a.action} title={(a.readAt ? '' : '● ') + a.title} sub={`${a.body}\n${when(a.createdAt)} · ${label(a.action)}`}
              onPress={() => { if (!a.readAt) readAlerts([a.id]); if (a.applicationId) nav.push('app', { id: a.applicationId, action: a.action }) }} />
          </View>
        ))}
        <Text style={{ color: C.muted, fontSize: 12, marginTop: 6 }}>Alerts for tasks that are done or have moved on are cleared.</Text>
      </ScrollView>
    </Screen>
  )
}
