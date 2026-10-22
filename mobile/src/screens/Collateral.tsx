// Collateral on the phone: declare it (property, vehicle, gold…), its documents, the gold appraisal in the branch
// (weight, stones, purity at the day's rate), the empanelled valuer's report, geo-tagged photos, and Collateral AI.
import React, { useCallback, useEffect, useState } from 'react'
import { Text, View } from 'react-native'
import { api, getCached, message } from '../lib/api'
import { position, takePhoto } from '../lib/capture'
import type { LocalFile } from '../lib/files'
import { add, newId } from '../lib/outbox'
import { useSession } from '../session'
import { Btn, C, Card, Choice, Feedback, Input, Muted, Note, Pill, Row, Screen, Spinner, inr, label, useRun } from '../ui'
import { useApp } from './AppDetail'
import { Capture, Picked } from './Documents'
import { OfflineBanner } from './Lists'

interface ColRow { id: number; colNo: string; type: string; description?: string; value: number; ltv?: number; maxExposure?: number; status?: string }
interface Detail {
  collateral: { id: number; colNo: string; type: string; status: string; description?: string }
  policy: { requiredPhotos?: string; requiredDocs?: string; minImages?: number; gpsRadiusM?: number }
  documents: { document: { id: number; docType: string; fileName: string; status?: string; readConfidence?: number } }[]
  media: { id: number; label: string; fileName: string; distanceM?: number; capturedAt?: string }[]
  valuations: { id: number; source: string; valuer?: string; reportDate: string; marketValue: number; realisableValue?: number; method?: string; approved: boolean }[]
  evaluation?: { status: string; riskScore?: number; summary?: string; ltv?: { valueUsed?: number; eligibleValue?: number; maxLtv?: number; maxExposure?: number; requested?: number; recommended?: number; ltvRequested?: number }
    vision?: { photos?: number; coverage?: number; missingLabels?: string[]; maxDistanceM?: number } }
}
type Item = { description: string; grossWeight: string; stoneWeight: string; purityKarat: string }
const list = (s?: string) => String(s ?? '').split(',').map(x => x.trim()).filter(Boolean)
const pct = (v?: number) => v == null ? '—' : Math.round(v * 100) + '%'

export default function CollateralScreen({ id }: { id: number }) {
  const { user, syncNow } = useSession()
  const { v } = useApp(id)
  const [rows, setRows] = useState<ColRow[] | null>(null)
  const [sel, setSel] = useState<number>()
  const [d, setD] = useState<Detail | null>(null)
  const [savedAt, setSavedAt] = useState<number>()
  const [declaring, setDeclaring] = useState(false)
  const r = useRun()
  const types = list((v?.product as unknown as { collateralTypes?: string })?.collateralTypes)

  const loadList = useCallback(async (pick?: number) => {
    try {
      const x = await getCached<ColRow[]>(`/api/applications/${id}/collaterals`)
      setRows(x.data); setSavedAt(x.savedAt)
      setSel(s => pick ?? s ?? x.data[0]?.id)
    } catch (e) { setRows([]); r.setError(message(e)) }
  }, [id]) // eslint-disable-line react-hooks/exhaustive-deps
  const load = useCallback(async () => {
    if (!sel) return
    try { setD((await getCached<Detail>('/api/collaterals/' + sel)).data) } catch (e) { r.setError(message(e)) }
  }, [sel]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { loadList() }, [loadList])
  useEffect(() => { load() }, [load])
  const refresh = () => { load(); loadList() }

  const canDeclare = ['ADMIN', 'SALES', 'OPERATIONS', 'CREDIT_OFFICER', 'CREDIT_MANAGER'].includes(user.role) && types.length > 0
  const gold = d?.collateral.type === 'GOLD'
  const canPhoto = ['ADMIN', 'OPERATIONS', 'CREDIT_OFFICER', 'FIELD_OFFICER', 'VALUER'].includes(user.role)
  const canDocs = ['ADMIN', 'SALES', 'OPERATIONS', 'CREDIT_OFFICER', 'CREDIT_MANAGER', 'FIELD_OFFICER', 'LEGAL_REVIEWER'].includes(user.role)
  const canValue = gold ? ['ADMIN', 'OPERATIONS', 'CREDIT_OFFICER', 'CREDIT_MANAGER'].includes(user.role) : ['ADMIN', 'VALUER'].includes(user.role)

  async function shoot(lbl: string) {
    await r.run(async () => {
      const s = await takePhoto(`${d?.collateral.colNo ?? 'col'}-${lbl.toLowerCase()}`)
      if (!s) return 'Cancelled'
      await add({ id: newId(), kind: 'photo', label: `${label(lbl)} photo for ${d?.collateral.colNo}`, method: 'POST', path: `/api/collaterals/${sel}/media`,
        fields: [{ name: 'file', file: s.file }, { name: 'label', value: lbl }, { name: 'capturedAt', value: s.takenAt },
          ...(s.pos ? [{ name: 'lat', value: String(s.pos.lat) }, { name: 'lng', value: String(s.pos.lng) }] : [])] })
      const res = await syncNow()
      refresh()
      return res && res.sent.length ? `${label(lbl)} photo uploaded` : `${label(lbl)} photo saved on this phone; it will upload when you are online`
    }, x => String(x))
  }

  return (
    <Screen title="Collateral" sub={v ? `${v.application.applicantName} · ${v.application.appNo}` : undefined} testID="collateral">
      <OfflineBanner savedAt={savedAt} />
      <Feedback error={r.error} ok={r.ok} />
      {(user.role === 'OPERATIONS' || user.role === 'ADMIN') && types.includes('GOLD') ? <GoldRate /> : null}
      {rows === null ? <Spinner /> : rows.length === 0 && !declaring ? <Card><Muted>{types.length ? 'No collateral declared yet.' : 'This product takes no collateral.'}</Muted></Card> : null}
      {canDeclare && !declaring && <Btn title="Declare collateral" testID="declare" onPress={() => setDeclaring(true)} />}
      {declaring && <Declare id={id} types={types} onClose={() => setDeclaring(false)} onDone={(cid, m) => { setDeclaring(false); r.setOk(m); setD(null); loadList(cid) }} />}
      {rows && rows.length > 1 && <Choice label="Collateral" value={String(sel)} options={rows.map(c => [String(c.id), `${c.colNo} · ${label(c.type)}`])} onChange={x => { setSel(Number(x)); setD(null) }} />}
      {sel && !d && rows && rows.length > 0 ? <Spinner /> : null}
      {d && <>
        <Card testID="collateral-case">
          <Row k="Case" v={d.collateral.colNo} />
          <Row k="Type" v={label(d.collateral.type)} />
          {d.collateral.description ? <Row k="Description" v={d.collateral.description} /> : null}
          <Row k="Collateral AI" v={<Pill s={d.evaluation?.status ?? 'PENDING'} />} testID="collateral-ai" />
          {d.evaluation?.ltv?.valueUsed != null ? <Row k="Value used" v={inr(d.evaluation.ltv.valueUsed)} /> : null}
          {d.evaluation?.ltv?.maxExposure != null ? <Row k="Maximum exposure" v={`${inr(d.evaluation.ltv.maxExposure)} (LTV ${pct(d.evaluation.ltv.maxLtv)})`} testID="max-exposure" /> : null}
          {d.evaluation?.ltv?.requested != null ? <Row k="Requested" v={`${inr(d.evaluation.ltv.requested)}${d.evaluation.ltv.ltvRequested != null ? ` (LTV ${pct(d.evaluation.ltv.ltvRequested)})` : ''}`} /> : null}
          {d.evaluation?.summary ? <Muted>{d.evaluation.summary}</Muted> : null}
          <Btn small title="Run Collateral AI again" busy={r.busy} testID="rerun-ai" onPress={() => r.run(() => api.post(`/api/collaterals/${d.collateral.id}/evaluate`), 'Collateral AI run again').then(refresh)} />
        </Card>
        {gold && canValue && <GoldAppraisal cid={d.collateral.id} onDone={m => { r.setOk(m); refresh() }} />}
        {!gold && canValue && <Valuation cid={d.collateral.id} onDone={m => { r.setOk(m); refresh() }} />}
        {d.valuations.length > 0 && <Card title="Valuations">
          {d.valuations.map(x => <Row key={x.id} k={`VAL-${x.id} · ${label(x.source)}${x.valuer ? ' · ' + x.valuer : ''}`} v={`${inr(x.marketValue)}${x.approved ? '' : ' (replaced)'}`} />)}
        </Card>}
        {!gold && canDocs && list(d.policy.requiredDocs).length > 0 && <ColDocs cid={d.collateral.id} types={list(d.policy.requiredDocs)} onDone={m => { r.setOk(m); refresh() }} />}
        {d.documents.length > 0 && <Card title="Documents">{d.documents.map(x => <Row key={x.document.id} k={label(x.document.docType)} v={`${x.document.fileName}${x.document.readConfidence != null ? ` · ${pct(x.document.readConfidence)}` : ''}`} />)}</Card>}
        {canPhoto && <Card title="Photos" testID="photos">
          {d.evaluation?.vision?.photos != null ? <Muted>{d.evaluation.vision.photos} photo(s) · coverage {pct(d.evaluation.vision.coverage)}{d.evaluation.vision.missingLabels?.length ? ' · missing ' + d.evaluation.vision.missingLabels.map(label).join(', ') : ''}</Muted> : null}
          <Muted>Stand at the {gold ? 'branch counter' : 'property'}: the photo carries your location{d.policy.gpsRadiusM ? ` (within ${d.policy.gpsRadiusM} m)` : ''} and the time.</Muted>
          {[...list(d.policy.requiredPhotos), 'OTHER'].map(l => {
            const got = d.media.filter(m => m.label === l)
            return (
              <View key={l} style={{ flexDirection: 'row', alignItems: 'center', gap: 8, marginBottom: 6 }} testID={'media-' + l}>
                <Text style={{ flex: 1, color: C.ink }}>{label(l)}{got.length ? ` ✓${got[0].distanceM != null ? ` · ${Math.round(got[0].distanceM)} m` : ''}` : ''}</Text>
                <Btn small title={got.length ? 'Again' : 'Photograph'} testID={'shoot-' + l} busy={r.busy} onPress={() => shoot(l)} />
              </View>
            )
          })}
        </Card>}
      </>}
    </Screen>
  )
}

function Declare({ id, types, onClose, onDone }: { id: number; types: string[]; onClose: () => void; onDone: (cid: number, m: string) => void }) {
  const [f, setF] = useState<Record<string, string>>({ type: types[0] })
  const r = useRun()
  const t = f.type
  const set = (k: string) => (x: string) => setF({ ...f, [k]: x })
  const txt = (k: string, l: string, extra: Record<string, unknown> = {}) => <Input label={l} value={f[k] ?? ''} onChangeText={set(k)} testID={'col-' + k} {...extra} />
  async function save() {
    const body: Record<string, unknown> = {}
    for (const [k, x] of Object.entries(f)) if (x !== '') body[k] = ['lat', 'lng', 'areaSqft', 'newPrice', 'manufactureYear'].includes(k) ? Number(x) : x
    const c = await r.run(() => api.post<{ id: number }>(`/api/applications/${id}/collaterals`, body))
    if (c && typeof c === 'object') onDone((c as { id: number }).id, `${label(t)} declared: the case is open`)
  }
  return (
    <Card title="Declare collateral" testID="declare-form">
      <Feedback error={r.error} ok={r.ok} />
      <Choice label="Type" value={t} options={types.map(x => [x, label(x)])} onChange={x => setF({ type: x })} testID="col-type" />
      {txt('description', t === 'GOLD' ? 'Items (e.g. chain, bangles, ring)' : 'Description')}
      {t !== 'GOLD' && <>
        {txt('ownerName', 'Owner as declared')}
        {txt('address', 'Address', { multiline: true })}
        {txt('pincode', 'PIN code', { keyboardType: 'number-pad', maxLength: 6 })}
        <Btn small title={f.lat ? `Location saved (${f.lat}, ${f.lng})` : "I'm at the property: save its location"} testID="col-locate"
          onPress={() => r.run(async () => { const p = await position(); if (!p) throw new Error('No GPS position: turn on location'); setF(x => ({ ...x, lat: p.lat.toFixed(6), lng: p.lng.toFixed(6) })) }, 'Location saved')} />
      </>}
      {['RESIDENTIAL', 'SHOP', 'PLOT'].includes(t) && <>{txt('surveyNo', 'Survey / CTS no.')}{txt('municipalId', 'Municipal ID')}{txt('areaSqft', 'Area (sq ft)', { keyboardType: 'numeric' })}</>}
      {t === 'VEHICLE' && <>{txt('registrationNo', 'Registration no.', { autoCapitalize: 'characters' })}{txt('makeModel', 'Make and model')}{txt('manufactureYear', 'Year of manufacture', { keyboardType: 'numeric' })}{txt('newPrice', 'New price (₹)', { keyboardType: 'numeric' })}</>}
      <Btn title="Declare and open the case" kind="primary" busy={r.busy} testID="declare-save" onPress={save} />
      <Btn title="Cancel" onPress={onClose} />
    </Card>
  )
}

function GoldAppraisal({ cid, onDone }: { cid: number; onDone: (m: string) => void }) {
  const [items, setItems] = useState<Item[]>([{ description: '', grossWeight: '', stoneWeight: '0', purityKarat: '22' }])
  const r = useRun()
  const set = (i: number, k: keyof Item) => (x: string) => setItems(items.map((y, j) => j === i ? { ...y, [k]: x } : y))
  const net = items.reduce((s, x) => s + Math.max(0, Number(x.grossWeight || 0) - Number(x.stoneWeight || 0)), 0)
  return (
    <Card title="Gold appraisal" testID="gold-appraisal">
      <Feedback error={r.error} />
      <Muted>Weigh and test each item. Valued on net gold weight only (no stones or making charges) at the day's 22 carat rate; LTV within the RBI caps.</Muted>
      {items.map((x, i) => (
        <View key={i} style={{ borderTopWidth: i ? 1 : 0, borderTopColor: C.line, paddingTop: i ? 8 : 0 }}>
          <Input label={`Item ${i + 1}`} value={x.description} onChangeText={set(i, 'description')} testID={`gold-item-${i + 1}`} />
          <View style={{ flexDirection: 'row', gap: 8 }}>
            <View style={{ flex: 1 }}><Input label="Gross (g)" keyboardType="decimal-pad" value={x.grossWeight} onChangeText={set(i, 'grossWeight')} testID={`gold-gross-${i + 1}`} /></View>
            <View style={{ flex: 1 }}><Input label="Stones (g)" keyboardType="decimal-pad" value={x.stoneWeight} onChangeText={set(i, 'stoneWeight')} testID={`gold-stones-${i + 1}`} /></View>
          </View>
          <Choice label="Purity" value={x.purityKarat} options={['24', '22', '20', '18', '14'].map(k => [k, k + ' ct'])} onChange={set(i, 'purityKarat')} testID={`gold-purity-${i + 1}`} />
          {items.length > 1 && <Btn small title="Remove item" onPress={() => setItems(items.filter((_, j) => j !== i))} />}
        </View>
      ))}
      <Btn small title="Add item" testID="gold-add" onPress={() => setItems([...items, { description: '', grossWeight: '', stoneWeight: '0', purityKarat: '22' }])} />
      <Muted>Net weight {net.toFixed(2)} g</Muted>
      <Btn title="Save appraisal" kind="primary" busy={r.busy} testID="gold-save"
        onPress={() => r.run(() => api.post(`/api/collaterals/${cid}/gold-appraisal`, { items: items.map(x => ({ description: x.description, grossWeight: Number(x.grossWeight), stoneWeight: Number(x.stoneWeight || 0), purityKarat: Number(x.purityKarat) })) }))
          .then(x => { if (x) { setItems([{ description: '', grossWeight: '', stoneWeight: '0', purityKarat: '22' }]); onDone(`Gold valued: VAL-${(x as { id?: number }).id ?? ''}`) } })} />
    </Card>
  )
}

function Valuation({ cid, onDone }: { cid: number; onDone: (m: string) => void }) {
  const [f, setF] = useState({ valuer: '', reportDate: new Date().toISOString().slice(0, 10), marketValue: '', realisableValue: '', distressValue: '', method: 'Sales comparison' })
  const r = useRun()
  const set = (k: keyof typeof f) => (x: string) => setF({ ...f, [k]: x })
  return (
    <Card title="Record the valuation report">
      <Feedback error={r.error} />
      <Input label="Valuer" value={f.valuer} onChangeText={set('valuer')} />
      <Input label="Report date (YYYY-MM-DD)" value={f.reportDate} onChangeText={set('reportDate')} />
      <Input label="Market value (₹)" keyboardType="numeric" value={f.marketValue} onChangeText={set('marketValue')} />
      <Input label="Realisable value (₹)" keyboardType="numeric" value={f.realisableValue} onChangeText={set('realisableValue')} />
      <Input label="Distress value (₹)" keyboardType="numeric" value={f.distressValue} onChangeText={set('distressValue')} />
      <Input label="Method" value={f.method} onChangeText={set('method')} />
      <Btn title="Save valuation" kind="primary" busy={r.busy} onPress={() => r.run(() => api.post(`/api/collaterals/${cid}/valuations`, { ...f, marketValue: Number(f.marketValue),
        realisableValue: f.realisableValue ? Number(f.realisableValue) : undefined, distressValue: f.distressValue ? Number(f.distressValue) : undefined })).then(x => { if (x) onDone('Valuation recorded') })} />
    </Card>
  )
}

function ColDocs({ cid, types, onDone }: { cid: number; types: string[]; onDone: (m: string) => void }) {
  const { syncNow, online } = useSession()
  const [t, setT] = useState(types[0])
  const [file, setFile] = useState<LocalFile | null>(null)
  const r = useRun()
  async function go() {
    if (!file) return
    await r.run(async () => {
      await add({ id: newId(), kind: 'upload', label: `${label(t)}: ${file.name}`, method: 'POST', path: `/api/collaterals/${cid}/documents`, fields: [{ name: 'file', file }, { name: 'docType', value: t }] })
      setFile(null)
      const s = await syncNow()
      if (s?.failed.length) throw new Error('The server refused the document; see the outbox')
      onDone(s && s.sent.length ? `${label(t)} uploaded and read` : 'Saved on this phone; it will upload when you are online')
    })
  }
  return (
    <Card title="Collateral documents">
      <Feedback error={r.error} />
      <Choice label="Document" value={t} options={types.map(x => [x, label(x)])} onChange={setT} testID="col-doc-type" />
      <Capture name={t.toLowerCase()} onFile={setFile} testID="col-doc" />
      {file && <Picked f={file} />}
      <Btn title={online ? 'Upload and read' : 'Save to send later'} kind="primary" disabled={!file} busy={r.busy} onPress={go} />
    </Card>
  )
}

/** Today's gold rate (22 carat, per gram), entered by operations; the lower of today's and the 30-day average is used. */
function GoldRate() {
  const [rates, setRates] = useState<{ goldRateUsed: number; gold: { rateDate: string; rate22k: number }[] } | null>(null)
  const [rate, setRate] = useState('')
  const r = useRun()
  const load = useCallback(() => { api.get<typeof rates>('/api/collateral-rates').then(setRates).catch(() => undefined) }, [])
  useEffect(() => { load() }, [load])
  return (
    <Card title="Gold rate" testID="gold-rate">
      <Feedback error={r.error} ok={r.ok} />
      {rates ? <Muted>Used today: {inr(rates.goldRateUsed)}/g (lower of today's rate and the 30-day average){rates.gold[0] ? ` · last entered ${rates.gold[0].rateDate}: ${inr(rates.gold[0].rate22k)}` : ''}</Muted> : null}
      <Input label="Today's 22 carat rate (₹ per gram)" keyboardType="numeric" value={rate} onChangeText={setRate} testID="gold-rate-input" />
      <Btn small title="Save rate" disabled={!Number(rate)} busy={r.busy} testID="gold-rate-save" onPress={() => r.run(() => api.post('/api/collateral-rates/gold', { rate22k: Number(rate) }), 'Gold rate saved').then(x => { if (x) { setRate(''); load() } })} />
    </Card>
  )
}

