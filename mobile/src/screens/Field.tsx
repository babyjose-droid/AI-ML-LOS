// Field officer: assigned visits (kept on the phone for offline use), map and call links, and the visit report:
// GPS check-in, the product's checklist, camera photos stamped with time and place, references and remarks.
import React, { useMemo, useState } from 'react'
import { Image, Linking, RefreshControl, ScrollView, Text, View } from 'react-native'
import { metres, position, takePhoto, type Pos, type Shot } from '../lib/capture'
import { add, newId } from '../lib/outbox'
import { useSession } from '../session'
import { Btn, C, Card, Choice, Feedback, Input, Muted, Note, Screen, Spinner, inr, label, useNav, useRun } from '../ui'
import { OfflineBanner, useCachedList } from './Lists'

interface Item { key: string; label: string; type: string; required: boolean }
export interface Assigned {
  applicationId: number; appNo: string; applicant: string; mobile: string; product: string; amount: number; business?: string; address: string
  addressLat?: number; addressLng?: number; checklist: { industry: string; items: Item[]; photos: string[] }
}
const PHOTO_LABEL: Record<string, string> = {
  front: 'Premises from the front', inside: 'Inside the premises', stock: 'Stock on the shelves', owner: 'Owner with you (selfie)',
  animals: 'Milch animals', vehicle: 'Vehicle with number plate', odometer: 'Odometer', residence: 'Residence', other: 'Other',
}

export function VisitsScreen() {
  const nav = useNav()
  const l = useCachedList<Assigned[]>('/api/field/visits')
  const { waiting } = useSession()
  return (
    <Screen title="Field visits" sub="Visit reports are kept on the phone and sent when online" scroll={false} testID="visits">
      <ScrollView refreshControl={<RefreshControl refreshing={l.loading} onRefresh={l.load} />}>
        <OfflineBanner savedAt={l.savedAt} />
        <Feedback error={l.error} />
        {waiting > 0 && <Note kind="warn" testID="pending">{waiting} item(s) waiting in the outbox</Note>}
        {l.data === null && !l.error ? <Spinner /> : null}
        {l.data && l.data.length === 0 ? <Card><Muted>No field visits assigned to you.</Muted></Card> : null}
        {(l.data ?? []).map(a => (
          <Card key={a.applicationId} testID={'visit-' + a.appNo}>
            <Text style={{ fontSize: 16, fontWeight: '700', color: C.ink }}>{a.applicant}</Text>
            <Muted>{a.appNo} · {a.product} · {inr(a.amount)}{a.business ? ' · ' + a.business : ''}</Muted>
            <Text style={{ color: C.ink, marginBottom: 8 }}>{a.address}</Text>
            <View style={{ flexDirection: 'row', gap: 8 }}>
              <Btn small style={{ flex: 1 }} title="Call" onPress={() => Linking.openURL('tel:' + a.mobile)} />
              <Btn small style={{ flex: 1 }} title="Map" onPress={() => Linking.openURL(a.addressLat != null ? `https://maps.google.com/?q=${a.addressLat},${a.addressLng}` : 'https://maps.google.com/?q=' + encodeURIComponent(a.address))} />
              <Btn small style={{ flex: 1.3 }} kind="primary" title="Start visit" testID={'start-' + a.appNo} onPress={() => nav.push('visit', { a })} />
            </View>
          </Card>
        ))}
      </ScrollView>
    </Screen>
  )
}

export function VisitForm({ a }: { a: Assigned }) {
  const nav = useNav()
  const { online, syncNow } = useSession()
  const [checkin, setCheckin] = useState<{ at: string; pos: Pos | null } | null>(null)
  const [answers, setAnswers] = useState<Record<string, unknown>>({})
  const [refs, setRefs] = useState([{ name: '', phone: '', says: '' }, { name: '', phone: '', says: '' }])
  const [remarks, setRemarks] = useState('')
  const [shots, setShots] = useState<(Shot & { kind: string })[]>([])
  const r = useRun()
  const clientId = useMemo(() => 'v-' + newId(), [])

  const dist = checkin?.pos && a.addressLat != null ? metres([checkin.pos.lat, checkin.pos.lng], [a.addressLat, a.addressLng!]) : null

  const missing = [
    ...(checkin ? [] : ['check in']),
    ...a.checklist.items.filter(i => i.required && (answers[i.key] === undefined || answers[i.key] === '')).map(i => i.label),
    ...a.checklist.photos.filter(k => !shots.some(s => s.kind === k)).map(k => 'photo: ' + (PHOTO_LABEL[k] ?? k)),
    ...(remarks.trim().length < 10 ? ['remarks (10+ characters)'] : []),
  ]

  async function doCheckin() {
    await r.run(async () => {
      const pos = await position()
      setCheckin({ at: new Date().toISOString(), pos })
      if (!pos) throw new Error('No GPS position: turn on location. You can continue; the visit will be flagged.')
    })
  }

  async function shoot(kind: string) {
    await r.run(async () => {
      const s = await takePhoto(`${a.appNo}-${kind}`)
      if (s) setShots(x => [...x.filter(y => !(y.kind === kind && kind !== 'other')), { ...s, kind }])
    })
  }

  async function save() {
    if (missing.length) { r.setError('Still needed: ' + missing.join(', ')); return }
    await r.run(async () => {
      const visit = {
        clientId, checkinAt: checkin!.at, checkinLat: checkin!.pos?.lat ?? null, checkinLng: checkin!.pos?.lng ?? null, checkinAccuracy: checkin!.pos?.accuracy ?? null,
        capturedOffline: !online, answers, references: refs.filter(x => x.name.trim()), remarks,
        photos: shots.map(s => ({ kind: s.kind, takenAt: s.takenAt, lat: s.pos?.lat ?? null, lng: s.pos?.lng ?? null })),
      }
      await add({ id: clientId, kind: 'visit', label: `Field visit: ${a.appNo} ${a.applicant}`, method: 'POST', path: `/api/field/visits/${a.applicationId}`,
        fields: [{ name: 'visit', value: JSON.stringify(visit) }, ...shots.map(s => ({ name: 'photos', file: s.file }))] })
      const res = await syncNow()
      nav.toast(res && res.sent.length ? `${a.appNo}: visit report sent` : `${a.appNo}: saved on this phone; it will be sent when you are online`)
      nav.back()
    })
  }

  const input = (i: Item) => {
    const [t, opts] = i.type.split(':')
    if (t === 'YESNO') return <Choice label={i.label + (i.required ? ' *' : '')} value={answers[i.key] === undefined ? '' : String(answers[i.key])} options={[['true', 'Yes'], ['false', 'No']]}
      onChange={v => setAnswers({ ...answers, [i.key]: v === 'true' })} testID={'q-' + i.key} />
    if (t === 'NUMBER') return <Input label={i.label + (i.required ? ' *' : '')} keyboardType="numeric" value={answers[i.key] === undefined ? '' : String(answers[i.key])}
      onChangeText={v => setAnswers({ ...answers, [i.key]: v === '' ? undefined : Number(v) })} testID={'q-' + i.key} />
    return <Choice label={i.label + (i.required ? ' *' : '')} value={String(answers[i.key] ?? '')} options={(opts ?? '').split('|').map(o => [o, label(o)])}
      onChange={v => setAnswers({ ...answers, [i.key]: v })} testID={'q-' + i.key} />
  }

  return (
    <Screen title={a.applicant} sub={`${a.appNo} · ${a.address}`} testID="visit-form">
      {!online && <Note kind="warn">Offline: the report and photos are saved on this phone and sent when you are back online.</Note>}
      <Card title="1. Check in at the premises">
        {checkin ? <Text style={{ color: C.ink }} testID="checked-in">Checked in {new Date(checkin.at).toLocaleTimeString('en-IN')}{checkin.pos ? ` · ±${Math.round(checkin.pos.accuracy ?? 0)} m` : ' · no GPS'}
          {dist != null ? <Text style={{ color: dist > 150 ? C.bad : C.ok, fontWeight: '700' }}>{` · ${Math.round(dist)} m from the business${dist > 150 ? ' (over 150 m: flagged)' : ''}`}</Text> : null}</Text>
          : <Btn title="Check in here" kind="primary" busy={r.busy} onPress={doCheckin} testID="checkin" />}
      </Card>
      <Card title="2. Checklist">{a.checklist.items.map(i => <View key={i.key}>{input(i)}</View>)}</Card>
      <Card title="3. Photos (camera only)">
        {[...a.checklist.photos, 'other'].map(k => {
          const s = shots.filter(x => x.kind === k)
          return (
            <View key={k} style={{ flexDirection: 'row', alignItems: 'center', gap: 10, marginBottom: 10 }}>
              {s[0] ? <Image source={{ uri: s[s.length - 1].file.uri }} style={{ width: 64, height: 64, borderRadius: 8, backgroundColor: '#eee' }} /> : <View style={{ width: 64, height: 64, borderRadius: 8, backgroundColor: '#eef1f3' }} />}
              <View style={{ flex: 1 }}>
                <Text style={{ color: C.ink, fontWeight: '600' }}>{PHOTO_LABEL[k] ?? label(k)}{k !== 'other' ? ' *' : ''}</Text>
                {s[0]?.pos ? <Text style={{ color: C.muted, fontSize: 12 }}>{s[0].pos.lat.toFixed(5)}, {s[0].pos.lng.toFixed(5)}</Text> : null}
              </View>
              <Btn small title={s.length && k !== 'other' ? 'Retake' : 'Take photo'} testID={'photo-' + k} onPress={() => shoot(k)} />
            </View>
          )
        })}
      </Card>
      <Card title="4. Neighbour or supplier references (optional)">
        {refs.map((x, i) => (
          <View key={i} style={{ marginBottom: 6 }}>
            <Input label={`Reference ${i + 1}: name`} value={x.name} onChangeText={t => setRefs(refs.map((y, j) => j === i ? { ...y, name: t } : y))} />
            {x.name ? <>
              <Input label={`Reference ${i + 1}: phone`} keyboardType="phone-pad" value={x.phone} onChangeText={t => setRefs(refs.map((y, j) => j === i ? { ...y, phone: t } : y))} />
              <Input label={`Reference ${i + 1}: what they said`} value={x.says} onChangeText={t => setRefs(refs.map((y, j) => j === i ? { ...y, says: t } : y))} />
            </> : null}
          </View>
        ))}
      </Card>
      <Card title="5. Your remarks *">
        <Input label="What you saw" multiline value={remarks} onChangeText={setRemarks} placeholder="Business running, stock, premises, family, anything unusual" testID="remarks" />
        <Feedback error={r.error} ok={r.ok} />
        <Btn title={online ? 'Save and send' : 'Save on this phone'} kind="primary" busy={r.busy} onPress={save} testID="save-visit" />
      </Card>
    </Screen>
  )
}

