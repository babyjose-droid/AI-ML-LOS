// Collateral photos: each view the policy asks for, taken with the camera on site, stamped with GPS and time, and sent
// through the outbox. The server checks the distance from the property, duplicates and coverage.
import React, { useCallback, useEffect, useState } from 'react'
import { Text, View } from 'react-native'
import { getCached, message } from '../lib/api'
import { takePhoto } from '../lib/capture'
import { add, newId } from '../lib/outbox'
import { useSession } from '../session'
import { Btn, C, Card, Choice, Feedback, Muted, Note, Pill, Row, Screen, Spinner, inr, label, useRun, when } from '../ui'
import { OfflineBanner } from './Lists'

interface ColRow { id: number; colNo: string; type: string; description?: string; value: number; ltv?: number; applicant: string; appNo: string }
interface Detail {
  collateral: { id: number; colNo: string; type: string; status: string }
  policy: { requiredPhotos?: string[] | string; minImages?: number; gpsRadiusM?: number }
  media: { id: number; label: string; fileName: string; distanceM?: number; capturedAt?: string; lat?: number; lng?: number }[]
  evaluation?: { vision?: { photos: number; coverage?: number; missingLabels?: string[]; maxDistanceM?: number; reused?: number } }
}
const PHOTOS = ['EXTERIOR_FRONT', 'EXTERIOR_SIDE', 'INTERIOR', 'STREET_VIEW', 'BOUNDARY', 'VEHICLE_FRONT', 'VEHICLE_REAR', 'CHASSIS_NO', 'ODOMETER']

export default function CollateralScreen({ id }: { id: number }) {
  const { user, syncNow } = useSession()
  const [list, setList] = useState<ColRow[] | null>(null)
  const [sel, setSel] = useState<number>()
  const [d, setD] = useState<Detail | null>(null)
  const [savedAt, setSavedAt] = useState<number>()
  const [lbl, setLbl] = useState('')
  const r = useRun()
  const canShoot = ['ADMIN', 'OPERATIONS', 'CREDIT_OFFICER', 'FIELD_OFFICER', 'VALUER'].includes(user.role)

  useEffect(() => {
    getCached<ColRow[]>(`/api/applications/${id}/collaterals`).then(x => { setList(x.data); setSavedAt(x.savedAt); if (x.data[0]) setSel(x.data[0].id) }).catch(e => { setList([]); r.setError(message(e)) })
  }, [id]) // eslint-disable-line react-hooks/exhaustive-deps

  const load = useCallback(async () => {
    if (!sel) return
    try { const x = await getCached<Detail>('/api/collaterals/' + sel); setD(x.data) } catch (e) { r.setError(message(e)) }
  }, [sel]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { load() }, [load])

  const required = d ? (Array.isArray(d.policy.requiredPhotos) ? d.policy.requiredPhotos : String(d.policy.requiredPhotos ?? '').split(',').filter(Boolean)) : []
  const labels = required.length ? required : PHOTOS
  const chosen = lbl || labels.find(l => !d?.media.some(m => m.label === l)) || labels[0]

  async function shoot() {
    if (!sel) return
    await r.run(async () => {
      const s = await takePhoto(`${d?.collateral.colNo ?? 'col'}-${chosen.toLowerCase()}`)
      if (!s) return 'Cancelled'
      await add({ id: newId(), kind: 'photo', label: `${label(chosen)} photo for ${d?.collateral.colNo}`, method: 'POST', path: `/api/collaterals/${sel}/media`,
        fields: [{ name: 'file', file: s.file }, { name: 'label', value: chosen }, { name: 'capturedAt', value: s.takenAt },
          ...(s.pos ? [{ name: 'lat', value: String(s.pos.lat) }, { name: 'lng', value: String(s.pos.lng) }] : [])] })
      setLbl('')
      const res = await syncNow()
      await load()
      return res && res.sent.length ? `${label(chosen)} photo uploaded` : `${label(chosen)} photo saved on this phone; it will upload when you are online`
    }, x => String(x))
  }

  return (
    <Screen title="Collateral" sub="Geo-tagged photos of the security" testID="collateral">
      <OfflineBanner savedAt={savedAt} />
      <Feedback error={r.error} ok={r.ok} />
      {list === null ? <Spinner /> : list.length === 0 ? <Card><Muted>No collateral recorded on this application yet.</Muted></Card> : null}
      {list && list.length > 1 && <Choice label="Collateral" value={String(sel)} options={list.map(c => [String(c.id), `${c.colNo} · ${label(c.type)}`])} onChange={v => { setSel(Number(v)); setD(null) }} />}
      {list?.filter(c => c.id === sel).map(c => (
        <Card key={c.id}>
          <Row k="Case" v={c.colNo} />
          <Row k="Type" v={label(c.type)} />
          {c.description ? <Row k="Description" v={c.description} /> : null}
          <Row k="Value" v={inr(c.value)} />
          {c.ltv != null ? <Row k="LTV" v={Math.round(c.ltv * 100) + '%'} /> : null}
          {d ? <Row k="Status" v={<Pill s={d.collateral.status} />} /> : null}
        </Card>
      ))}
      {d?.evaluation?.vision && <Note kind={d.evaluation.vision.missingLabels?.length ? 'warn' : 'ok'}>
        {d.evaluation.vision.photos} photo(s) · coverage {Math.round((d.evaluation.vision.coverage ?? 0) * 100)}%{d.evaluation.vision.missingLabels?.length ? ' · missing ' + d.evaluation.vision.missingLabels.map(label).join(', ') : ''}
        {d.evaluation.vision.maxDistanceM != null ? ` · farthest ${d.evaluation.vision.maxDistanceM} m` : ''}</Note>}
      {d && canShoot && <Card title="Take a photo">
        <Muted>Stand at the property: the photo carries your location (within {d.policy.gpsRadiusM ?? 200} m) and the time.</Muted>
        <Choice label="View" value={chosen} options={labels.map(l => [l, label(l) + (d.media.some(m => m.label === l) ? ' ✓' : '')])} onChange={setLbl} testID="photo-label" />
        <Btn title={'Photograph: ' + label(chosen)} kind="primary" busy={r.busy} onPress={shoot} testID="shoot" />
      </Card>}
      {d && <Card title={`Photos (${d.media.length}${d.policy.minImages ? ' of at least ' + d.policy.minImages : ''})`}>
        {d.media.length === 0 ? <Muted>None yet.</Muted> : d.media.map(m => (
          <View key={m.id} style={{ paddingVertical: 6, borderBottomWidth: 1, borderBottomColor: C.line }} testID={'media-' + m.label}>
            <Text style={{ color: C.ink, fontWeight: '600' }}>{label(m.label)}</Text>
            <Text style={{ color: C.muted, fontSize: 12 }}>{when(m.capturedAt)}{m.distanceM != null ? ` · ${Math.round(m.distanceM)} m from the property` : ' · no location'}</Text>
          </View>
        ))}
      </Card>}
    </Screen>
  )
}
