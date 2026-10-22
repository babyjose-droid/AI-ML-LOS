// Rhythm LOS mobile: one app for every role. Each person sees the tabs and actions of their role; work captured without
// signal waits in the outbox and is sent when the phone is back online.
import { StatusBar } from 'expo-status-bar'
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { BackHandler, Platform, Pressable, StyleSheet, Text, View } from 'react-native'
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context'
import { api, getTenant, getToken, init, setToken, setUnauthorizedHandler } from './src/lib/api'
import { setOwner } from './src/lib/outbox'
import AppDetail from './src/screens/AppDetail'
import CollateralScreen from './src/screens/Collateral'
import { DecisionScreen, KfsScreen } from './src/screens/Decision'
import Documents from './src/screens/Documents'
import { VisitForm, VisitsScreen } from './src/screens/Field'
import { AppsScreen, MeScreen, OutboxScreen, WorkScreen } from './src/screens/Lists'
import Login from './src/screens/Login'
import NewApplication from './src/screens/NewApplication'
import ProductStep from './src/screens/ProductStep'
import { SessionProvider, canCreate, useSession, type User } from './src/session'
import { C, NavContext, Spinner, type Route } from './src/ui'

type Tab = { key: string; label: string; icon: string }

function tabsFor(u: User): Tab[] {
  const t: Tab[] = []
  if (u.role === 'FIELD_OFFICER') t.push({ key: 'visits', label: 'Visits', icon: '⌖' })
  t.push({ key: 'work', label: 'My work', icon: '☑' })
  if (u.role !== 'FIELD_OFFICER') t.push({ key: 'apps', label: 'Search', icon: '⌕' })
  if (canCreate(u.role)) t.push({ key: 'new', label: 'New', icon: '＋' })
  t.push({ key: 'outbox', label: 'Outbox', icon: '⇅' }, { key: 'me', label: 'Me', icon: '☺' })
  return t
}

function render(r: Route) {
  const p = r.params ?? {}
  switch (r.name) {
    case 'work': return <WorkScreen />
    case 'apps': return <AppsScreen />
    case 'new': return <NewApplication key={r.key} id={p.id} />
    case 'outbox': return <OutboxScreen />
    case 'me': return <MeScreen />
    case 'visits': return <VisitsScreen />
    case 'visit': return <VisitForm a={p.a} />
    case 'app': return <AppDetail key={r.key} id={p.id} action={p.action} />
    case 'documents': return <Documents id={p.id} />
    case 'decision': return <DecisionScreen id={p.id} />
    case 'kfs': return <KfsScreen id={p.id} />
    case 'step': return <ProductStep id={p.id} kind={p.kind} />
    case 'collateral': return <CollateralScreen id={p.id} />
    default: return <Text>Unknown screen</Text>
  }
}

let seq = 0
const route = (name: string, params?: Record<string, any>): Route => ({ name, params, key: name + '-' + ++seq })

function Shell() {
  const { user, waiting, failed, online } = useSession()
  const tabs = useMemo(() => tabsFor(user), [user])
  const [tab, setTab] = useState(tabs[0].key)
  const [stacks, setStacks] = useState<Record<string, Route[]>>(() => Object.fromEntries(tabs.map(t => [t.key, [route(t.key)]])))
  const [toast, setToast] = useState<string>()
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const stack = stacks[tab]

  const nav = useMemo(() => ({
    depth: stack.length - 1,
    push: (name: string, params?: Record<string, any>) => setStacks(s => ({ ...s, [tab]: [...s[tab], route(name, params)] })),
    back: () => setStacks(s => ({ ...s, [tab]: s[tab].length > 1 ? s[tab].slice(0, -1) : s[tab] })),
    replace: (name: string, params?: Record<string, any>) => setStacks(s => ({ ...s, [tab]: [...s[tab].slice(0, -1), route(name, params)] })),
    reset: (name: string, params?: Record<string, any>) => setStacks(s => ({ ...s, [tab]: [route(name, params)] })),
    toast: (m: string) => { setToast(m); clearTimeout(timer.current); timer.current = setTimeout(() => setToast(undefined), 6000) },
  }), [tab, stack.length])

  useEffect(() => {
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      if (stack.length > 1) { nav.back(); return true }
      return false
    })
    return () => sub.remove()
  }, [stack.length, nav])

  return (
    <NavContext.Provider value={nav}>
      <View style={{ flex: 1 }}>
        {!online && <View style={st.offline} testID="offline-bar"><Text style={st.offlineTxt}>Offline · your work is saved on this phone</Text></View>}
        {toast && <Pressable onPress={() => setToast(undefined)} style={st.toast} testID="toast" accessibilityRole="alert"><Text style={{ color: '#fff', fontSize: 14 }}>{toast}</Text></Pressable>}
        <View style={{ flex: 1 }}>{render(stack[stack.length - 1])}</View>
        <View style={st.tabs} accessibilityRole="tablist">
          {tabs.map(t => {
            const on = t.key === tab
            const badge = t.key === 'outbox' ? waiting + failed : 0
            return (
              <Pressable key={t.key} accessibilityRole="tab" accessibilityState={{ selected: on }} accessibilityLabel={t.label} testID={'tab-' + t.key} style={st.tab}
                onPress={() => on ? setStacks(s => ({ ...s, [t.key]: [s[t.key][0]] })) : setTab(t.key)}>
                <Text style={[st.icon, { color: on ? C.brand : C.muted }]}>{t.icon}</Text>
                <Text style={[st.tabTxt, { color: on ? C.brand : C.muted }]}>{t.label}</Text>
                {badge > 0 && <View style={[st.badge, failed ? { backgroundColor: C.bad } : null]} testID="outbox-badge"><Text style={{ color: '#fff', fontSize: 11, fontWeight: '700' }}>{badge}</Text></View>}
              </Pressable>
            )
          })}
        </View>
      </View>
    </NavContext.Provider>
  )
}

export default function App() {
  const [ready, setReady] = useState(false)
  const [user, setUser] = useState<User | null>(null)

  const signIn = useCallback((u: User) => { setOwner(`${getTenant() ?? ''}/${u.username}`); setUser(u) }, [])
  const signOut = useCallback(() => { setToken(null); setOwner(''); setUser(null) }, [])

  useEffect(() => {
    setUnauthorizedHandler(() => { setOwner(''); setUser(null) })
    init().then(async () => {
      if (getToken()) {
        try { signIn(await api.get<User>('/api/auth/me')) } catch { /* signed out or offline: sign in again */ }
      }
    }).finally(() => setReady(true))
  }, [signIn])

  return (
    <SafeAreaProvider>
      <SafeAreaView style={{ flex: 1, backgroundColor: C.brand }} edges={['top']}>
        <StatusBar style="light" />
        <View style={{ flex: 1, backgroundColor: C.bg }}>
          {!ready ? <Spinner /> : user ? <SessionProvider key={user.username} user={user} signOut={signOut}><Shell /></SessionProvider>
            : <NavContext.Provider value={{ depth: 0, push: () => {}, back: () => {}, replace: () => {}, reset: () => {}, toast: () => {} }}><Login onLogin={signIn} /></NavContext.Provider>}
        </View>
      </SafeAreaView>
    </SafeAreaProvider>
  )
}

const st = StyleSheet.create({
  tabs: { flexDirection: 'row', borderTopWidth: 1, borderTopColor: C.line, backgroundColor: '#fff', paddingBottom: Platform.OS === 'ios' ? 18 : 4 },
  tab: { flex: 1, alignItems: 'center', paddingVertical: 6 },
  icon: { fontSize: 20 },
  tabTxt: { fontSize: 11, marginTop: 1, fontWeight: '600' },
  badge: { position: 'absolute', top: 2, right: '24%', backgroundColor: C.warn, borderRadius: 9, minWidth: 18, height: 18, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 4 },
  offline: { backgroundColor: C.warn, paddingVertical: 4, alignItems: 'center' },
  offlineTxt: { color: '#fff', fontSize: 12, fontWeight: '600' },
  toast: { position: 'absolute', top: 70, left: 12, right: 12, zIndex: 10, backgroundColor: '#14212b', borderRadius: 10, padding: 12, elevation: 6 },
})
