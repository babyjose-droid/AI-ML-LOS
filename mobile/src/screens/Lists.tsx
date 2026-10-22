// My work, applications search, outbox and the "me" page.
import React, { useCallback, useEffect, useState } from 'react'
import { RefreshControl, ScrollView, Text, View } from 'react-native'
import { clearCache, getCached, getServer, isWeb, message } from '../lib/api'
import { jobs, remove, retry, subscribe, type Job } from '../lib/outbox'
import { ROLE_NAME, useSession } from '../session'
import { Btn, C, Card, Feedback, Input, ListItem, Muted, Note, Pill, Row, Screen, Spinner, inr, label, useNav, when } from '../ui'

export interface Task { applicationId?: number; appNo?: string; applicant?: string; product?: string; amount: number; task: string; action: string; ageHours: number; slaHours: number }
export interface AppSummary { id: number; appNo: string; applicantName: string; productCode: string; loanAmount: number; city?: string; states: Record<string, string>; updatedAt: string }

export function useCachedList<T>(path: string) {
  const [data, setData] = useState<T | null>(null)
  const [savedAt, setSavedAt] = useState<number>()
  const [error, setError] = useState<string>()
  const [loading, setLoading] = useState(false)
  const load = useCallback(async () => {
    setLoading(true)
    try { const r = await getCached<T>(path); setData(r.data); setSavedAt(r.savedAt); setError(undefined) } catch (e) { setError(message(e)) } finally { setLoading(false) }
  }, [path])
  useEffect(() => { load() }, [load])
  return { data, savedAt, error, loading, load }
}

export function OfflineBanner({ savedAt }: { savedAt?: number }) {
  const { online } = useSession()
  if (online && !savedAt) return null
  return <Note kind="warn" testID="offline">{online ? 'Showing the copy saved on this phone' : 'Offline'}{savedAt ? ` · saved ${new Date(savedAt).toLocaleString('en-IN')}` : ''}. Work you do now is kept and sent when the signal returns.</Note>
}

function sla(t: Task) {
  if (!t.slaHours) return null
  const left = t.slaHours - t.ageHours
  return <Pill s={left < 0 ? 'FAIL' : left < t.slaHours / 4 ? 'REFER' : 'PENDING'} text={left < 0 ? `${Math.round(-left)}h late` : `${Math.round(left)}h left`} />
}

export function WorkScreen() {
  const nav = useNav()
  const { user } = useSession()
  const w = useCachedList<Task[]>('/api/my-work')
  const tasks = (w.data ?? []).filter(t => t.applicationId)
  return (
    <Screen title="My work" sub={`${user.name} · ${ROLE_NAME[user.role]}`} scroll={false} testID="work">
      <ScrollView refreshControl={<RefreshControl refreshing={w.loading} onRefresh={w.load} />}>
        <OfflineBanner savedAt={w.savedAt} />
        <Feedback error={w.error} />
        {w.data === null && !w.error ? <Spinner /> : null}
        {w.data && tasks.length === 0 ? <Card><Muted>Nothing waiting for you. Pull down to refresh.</Muted></Card> : null}
        {tasks.map((t, i) => (
          <ListItem key={i} testID={'task-' + t.appNo} title={`${t.applicant} · ${t.appNo}`} sub={`${t.task}\n${t.product} · ${inr(t.amount)}`} right={sla(t)}
            onPress={() => nav.push('app', { id: t.applicationId, action: t.action })} />
        ))}
      </ScrollView>
    </Screen>
  )
}

export function AppsScreen() {
  const nav = useNav()
  const [q, setQ] = useState('')
  const [query, setQuery] = useState('')
  const l = useCachedList<AppSummary[]>('/api/applications?state=&q=' + encodeURIComponent(query))
  return (
    <Screen title="Applications" sub="Search by name, application or PAN" scroll={false} testID="apps">
      <Input label="Search" value={q} onChangeText={setQ} onSubmitEditing={() => setQuery(q)} returnKeyType="search" autoCapitalize="none" testID="search" />
      <ScrollView refreshControl={<RefreshControl refreshing={l.loading} onRefresh={l.load} />}>
        <OfflineBanner savedAt={l.savedAt} />
        <Feedback error={l.error} />
        {l.data === null && !l.error ? <Spinner /> : null}
        {(l.data ?? []).slice(0, 60).map(a => (
          <ListItem key={a.id} testID={'app-' + a.appNo} title={`${a.applicantName} · ${a.appNo}`} sub={`${a.productCode} · ${inr(a.loanAmount)}${a.city ? ' · ' + a.city : ''}`}
            right={<Pill s={a.states.DISB === 'DONE' ? 'DISBURSED' : a.states.APP} text={a.states.DISB === 'DONE' ? 'Disbursed' : label(a.states.APP)} />} onPress={() => nav.push('app', { id: a.id })} />
        ))}
      </ScrollView>
    </Screen>
  )
}

export function OutboxScreen() {
  const { online, syncNow, last } = useSession()
  const [list, setList] = useState<Job[]>([])
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState<string>()
  const load = useCallback(() => { jobs().then(setList) }, [])
  useEffect(() => { load(); return subscribe(load) }, [load])
  async function now() {
    setBusy(true); setMsg(undefined)
    const r = await syncNow()
    setBusy(false)
    if (!r) setMsg('Offline: items will be sent when the signal returns')
    else setMsg(`${r.sent.length} sent${r.failed.length ? `, ${r.failed.length} refused by the server` : ''}${r.offline ? '; the rest wait for a better signal' : ''}`)
  }
  return (
    <Screen title="Outbox" sub="Saved on this phone, sent automatically when online" testID="outbox">
      <Card>
        <Row k="Connection" v={<Pill s={online ? 'ONLINE' : 'OFFLINE'} />} />
        <Row k="Waiting to send" v={String(list.filter(j => j.status === 'WAITING').length)} testID="outbox-waiting" />
        <Row k="Refused by the server" v={String(list.filter(j => j.status === 'FAILED').length)} />
        {last ? <Muted>Last sync: {last.sent.length} sent{last.failed.length ? `, ${last.failed.length} refused` : ''}</Muted> : null}
        <Btn title="Sync now" kind="primary" busy={busy} disabled={!online || list.length === 0} onPress={now} testID="sync-now" />
        {msg ? <Note testID="sync-result">{msg}</Note> : null}
      </Card>
      {list.length === 0 ? <Card><Muted>Nothing waiting. Everything you captured has reached the server.</Muted></Card> : null}
      {list.map(j => (
        <Card key={j.id} testID={'job-' + j.kind}>
          <View style={{ flexDirection: 'row', justifyContent: 'space-between', gap: 8 }}>
            <Text style={{ flex: 1, fontWeight: '600', color: C.ink }}>{j.label}</Text>
            <Pill s={j.status} />
          </View>
          <Muted>Saved {new Date(j.createdAt).toLocaleString('en-IN')}{j.attempts ? ` · ${j.attempts} attempt(s)` : ''}{j.dependsOn ? ' · after its application' : ''}</Muted>
          {j.error ? <Note kind={j.status === 'FAILED' ? 'bad' : 'warn'}>{j.error}</Note> : null}
          {j.status === 'FAILED' ? <View style={{ flexDirection: 'row', gap: 8 }}>
            <Btn small title="Try again" onPress={() => retry(j.id).then(now)} />
            <Btn small kind="danger" title="Discard" onPress={() => remove(j.id)} />
          </View> : null}
        </Card>
      ))}
    </Screen>
  )
}

export function MeScreen() {
  const { user, signOut, waiting } = useSession()
  return (
    <Screen title={user.name} sub={ROLE_NAME[user.role]} testID="me">
      <Card>
        <Row k="Username" v={user.username} />
        <Row k="Role" v={ROLE_NAME[user.role]} />
        {user.sanctionLevel ? <Row k="Sanction level" v={'L' + user.sanctionLevel} /> : null}
        {!isWeb ? <Row k="Server" v={getServer()} /> : null}
        <Row k="Last refreshed" v={when(new Date().toISOString())} />
      </Card>
      {waiting > 0 ? <Note kind="warn">{waiting} item(s) are still waiting in the outbox. They stay on this phone and are sent the next time you sign in.</Note> : null}
      <Card>
        <Muted>Masters, products, policies, users and reports are on the web console.</Muted>
        <Btn title="Sign out" kind="danger" testID="sign-out" onPress={() => { clearCache().finally(signOut) }} />
      </Card>
    </Screen>
  )
}
