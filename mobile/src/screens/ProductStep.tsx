// Product-specific steps on the phone: asset finance (asset, dealer, documents, down payment), trade finance (limit and
// invoices), staged plans (documents, site inspection, tranche release) and loans against securities or deposits
// (statements, lien, margin calls), with the full set-up forms (asset details, buyer sub-limits, plan details and stages,
// deposit details) and exceptions on referred checks for the officers who may grant them.
import React, { useCallback, useEffect, useState } from 'react'
import { Text, View } from 'react-native'
import { api, getCached, message } from '../lib/api'
import type { LocalFile } from '../lib/files'
import { add, newId } from '../lib/outbox'
import { useSession } from '../session'
import { Btn, C, Card, Choice, Feedback, Input, Muted, Note, Pill, Row, Screen, Select, Spinner, inr, label, useRun, when } from '../ui'
import { Capture, Picked } from './Documents'
import { OfflineBanner } from './Lists'

interface Check { id: string; label: string; result: string; reason: string; ruling?: string }
const PATH: Record<string, string> = { asset: 'asset', limit: 'trade', plan: 'plan', pledge: 'pledge' }
const TITLE: Record<string, string> = { asset: 'Asset & dealer', limit: 'Limit & invoices', plan: 'Plan & tranches', pledge: 'Pledge & lien' }

/** The module's checks; a referred check can be accepted as an exception, with a reason, by the officers allowed to. */
function Checks({ checks, title = 'Checks', rule, hard = [] }: { checks?: Check[]; title?: string; rule?: (check: string, note: string) => Promise<void>; hard?: string[] }) {
  const [open, setOpen] = useState<string | null>(null)
  const [note, setNote] = useState('')
  if (!checks?.length) return null
  return (
    <Card title={title} testID="checks">
      {checks.map(c => (
        <View key={c.id} style={{ paddingVertical: 6, borderBottomWidth: 1, borderBottomColor: C.line }} testID={'check-' + c.id}>
          <View style={{ flexDirection: 'row', justifyContent: 'space-between', gap: 8 }}>
            <Text style={{ flex: 1, color: C.ink, fontWeight: '600' }}>{c.id} · {c.label}</Text>
            <Pill s={c.ruling ? 'PASS' : c.result} text={c.ruling ? 'Exception' : undefined} />
          </View>
          <Text style={{ color: C.muted, fontSize: 13, marginTop: 2 }}>{c.ruling ?? c.reason}</Text>
          {rule && c.result === 'REFER' && !c.ruling && !hard.includes(c.id) && (open === c.id ? <>
            <Input label="Why this exception is acceptable (at least 15 characters)" multiline value={note} onChangeText={setNote} testID={'exception-note-' + c.id} />
            <Btn small kind="primary" title="Record exception" disabled={note.trim().length < 15} testID={'exception-save-' + c.id} onPress={() => rule(c.id, note).then(() => { setOpen(null); setNote('') })} />
          </> : <Btn small title="Approve as an exception" testID={'exception-' + c.id} onPress={() => { setOpen(c.id); setNote('') }} />)}
        </View>
      ))}
    </Card>
  )
}

type F = { key: string; label: string; type?: 'text' | 'num' | 'date' | 'select'; options?: [string, string][] }
/** A set-up form: text, numbers, dates (YYYY-MM-DD) and choices. */
function Form({ fields, value, onChange, disabled }: { fields: F[]; value: Record<string, string>; onChange: (v: Record<string, string>) => void; disabled?: boolean }) {
  return <>{fields.map(f => f.type === 'select'
    ? <Choice key={f.key} label={f.label} value={value[f.key] ?? ''} options={f.options ?? []} onChange={x => !disabled && onChange({ ...value, [f.key]: x })} testID={'f-' + f.key} />
    : <Input key={f.key} label={f.label + (f.type === 'date' ? ' (YYYY-MM-DD)' : '')} editable={!disabled} keyboardType={f.type === 'num' ? 'numeric' : 'default'}
        value={value[f.key] ?? ''} onChangeText={x => onChange({ ...value, [f.key]: x })} testID={'f-' + f.key} />)}</>
}
const toForm = (o: Record<string, unknown> | undefined, keys: string[]) => Object.fromEntries(keys.map(k => [k, o?.[k] == null ? '' : String(o[k])]))
const fromForm = (v: Record<string, string>, fields: F[]) => {
  const out: Record<string, unknown> = {}
  for (const f of fields) { const x = (v[f.key] ?? '').trim(); out[f.key] = x === '' ? null : f.type === 'num' ? Number(x.replace(/,/g, '')) : f.key === 'institutionId' ? Number(x) : x }
  return out
}
function Collapse({ title, children, open: start = false, testID }: { title: string; children: React.ReactNode; open?: boolean; testID?: string }) {
  const [open, setOpen] = useState(start)
  return <Card testID={testID}>
    <Btn small title={(open ? '▾ ' : '▸ ') + title} onPress={() => setOpen(!open)} testID={testID ? testID + '-toggle' : undefined} />
    {open ? children : null}
  </Card>
}

/** Upload a document of one of the given types through the outbox. */
function UploadDoc({ types, path, extra, title, onDone, labels, children }: { types: string[]; path: string; extra?: { name: string; value: string }[]; title: string; onDone: (m: string) => void; labels?: Record<string, string>; children?: React.ReactNode }) {
  const { syncNow, online } = useSession()
  const [t, setT] = useState('')
  const [file, setFile] = useState<LocalFile | null>(null)
  const r = useRun()
  const type = t || types[0]
  async function go() {
    if (!file) return
    await r.run(async () => {
      await add({ id: newId(), kind: 'upload', label: `${labels?.[type] ?? label(type)}: ${file.name}`, method: 'POST', path, fields: [{ name: 'file', file }, ...(type === '-' ? [] : [{ name: 'docType', value: type }]), ...(extra ?? [])] })
      setFile(null)
      const s = await syncNow()
      if (s?.failed.length) throw new Error('The server refused the document; see the outbox')
      onDone(s && s.sent.length ? `${labels?.[type] ?? label(type)} uploaded and read` : 'Saved on this phone; it will upload when you are online')
    })
  }
  return (
    <Card title={title} testID="upload-card">
      {types.length > 1 && <Choice label="Document" value={type} options={types.map(x => [x, labels?.[x] ?? label(x)])} onChange={setT} testID="step-doc-type" />}
      {children}
      <Capture name={type.toLowerCase()} onFile={setFile} testID="step-doc" />
      {file && <Picked f={file} />}
      <Feedback error={r.error} />
      <Btn title={online ? 'Upload' : 'Save to send later'} kind="primary" disabled={!file} busy={r.busy} onPress={go} testID="step-upload" />
    </Card>
  )
}

export default function ProductStep({ id, kind }: { id: number; kind: string }) {
  const [d, setD] = useState<any>(null)
  const [savedAt, setSavedAt] = useState<number>()
  const r = useRun()
  const base = `/api/applications/${id}/${PATH[kind]}`
  const load = useCallback(async () => {
    try { const x = await getCached<any>(base); setD(x.data); setSavedAt(x.savedAt) } catch (e) { r.setError(message(e)) }
  }, [base]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { load() }, [load])
  const done = (m: string) => { r.setOk(m); load() }
  const act = (f: () => Promise<unknown>, m: string) => r.run(f, m).then(x => { if (x) load() })

  return (
    <Screen title={TITLE[kind] ?? label(kind)} testID={'step-' + kind}>
      <OfflineBanner savedAt={savedAt} />
      <Feedback error={r.error} ok={r.ok} />
      {!d ? <Spinner /> : kind === 'asset' ? <AssetStep id={id} d={d} base={base} act={act} done={done} busy={r.busy} />
        : kind === 'limit' ? <TradeStep id={id} d={d} base={base} act={act} done={done} busy={r.busy} />
        : kind === 'plan' ? <PlanStep d={d} base={base} act={act} done={done} busy={r.busy} />
        : <PledgeStep d={d} base={base} act={act} done={done} busy={r.busy} />}
    </Screen>
  )
}

type P = { id?: number; d: any; base: string; act: (f: () => Promise<unknown>, m: string) => Promise<void>; done: (m: string) => void; busy: boolean }

const ASSET_DOCS: [string, string][] = [['QUOTATION', 'Quotation / proforma'], ['INVOICE', 'Tax invoice'], ['DOWN_PAYMENT_RECEIPT', 'Down-payment receipt'], ['INSURANCE', 'Insurance policy'], ['PHOTO', 'Asset photo']]
const ASSET_GROUPS: [string, F[]][] = [
  ['Asset', [{ key: 'make', label: 'Make' }, { key: 'model', label: 'Model' }, { key: 'variant', label: 'Variant' }, { key: 'manufactureYear', label: 'Year of manufacture', type: 'num' },
    { key: 'units', label: 'Units', type: 'num' }, { key: 'serialNo', label: 'Serial / chassis no' }, { key: 'engineNo', label: 'Engine / motor no' }, { key: 'registrationNo', label: 'Registration no' }]],
  ['Quotation and invoice', [{ key: 'quoteNo', label: 'Quotation no' }, { key: 'quoteDate', label: 'Quotation date', type: 'date' }, { key: 'quoteAmount', label: 'Quotation amount', type: 'num' },
    { key: 'invoiceNo', label: 'Invoice no' }, { key: 'invoiceDate', label: 'Invoice date', type: 'date' }, { key: 'invoiceGstin', label: 'Invoice GSTIN' }, { key: 'basePrice', label: 'Base price', type: 'num' },
    { key: 'gstAmount', label: 'GST', type: 'num' }, { key: 'otherCharges', label: 'Other charges', type: 'num' }, { key: 'invoiceTotal', label: 'Invoice total', type: 'num' }]],
  ['Down payment and insurance', [{ key: 'downPayment', label: 'Down payment', type: 'num' }, { key: 'downPaymentRef', label: 'Receipt no' }, { key: 'subsidyAmount', label: 'Subsidy', type: 'num' },
    { key: 'insurer', label: 'Insurer' }, { key: 'insurancePolicyNo', label: 'Policy no' }, { key: 'insuredValue', label: 'Insured value (IDV)', type: 'num' }, { key: 'insuranceExpiry', label: 'Insurance valid till', type: 'date' }]],
]
const ASSET_FIELDS = ASSET_GROUPS.flatMap(g => g[1])

function AssetStep({ d, base, act, done, busy }: P) {
  const a = d.asset, e = d.evaluation
  const [f, setF] = useState<Record<string, string>>(() => ({ ...toForm(a, ASSET_FIELDS.map(x => x.key)), assetClass: a?.assetClass ?? d.classes?.[0]?.assetClass ?? '',
    dealerId: a?.dealerId ? String(a.dealerId) : '', insuranceHypothecated: a?.insuranceHypothecated ? 'true' : 'false' }))
  useEffect(() => { if (a) setF(x => ({ ...x, ...toForm(a, ASSET_FIELDS.map(y => y.key)), assetClass: a.assetClass, dealerId: a.dealerId ? String(a.dealerId) : '' })) }, [a?.id, d.documents?.length]) // eslint-disable-line react-hooks/exhaustive-deps
  const docs: [string, string][] = d.disbursed ? (d.postDocs ?? []).filter((p: any) => p.status !== 'RECEIVED').map((p: any) => [p.type, p.label]) : ASSET_DOCS
  const save = () => {
    const body: Record<string, unknown> = { assetClass: f.assetClass, dealerId: f.dealerId ? Number(f.dealerId) : undefined, insuranceHypothecated: f.insuranceHypothecated === 'true' }
    const vals = fromForm(f, ASSET_FIELDS)
    for (const [k, x] of Object.entries(vals)) if (x !== null) body[k] = x
    return act(() => api.put(base, body), a ? 'Asset saved and checked' : 'Asset saved')
  }
  return <>
    {a ? <Card>
      <Row k="Asset" v={a.assetNo} />
      <Row k="Class" v={label(a.assetClass)} />
      {d.dealer ? <Row k="Dealer" v={d.dealer.name} /> : null}
      {a.make ? <Row k="Make · model" v={`${a.make} ${a.model ?? ''} ${a.variant ?? ''}`.trim()} /> : null}
      {a.invoiceTotal ? <Row k="Invoice total" v={inr(a.invoiceTotal)} /> : a.quoteAmount ? <Row k="Quotation" v={inr(a.quoteAmount)} /> : null}
      {a.downPayment != null ? <Row k="Down payment" v={`${inr(a.downPayment)}${a.downPaymentVerifiedBy ? ' · verified' : ''}`} testID="down-payment" /> : null}
      {e ? <Row k="Maximum loan" v={`${inr(e.maxLoan)} · LTV ${Math.round(e.ltv * 100)}%`} /> : null}
      <Row k="Status" v={<Pill s={e?.status ?? a.status} />} />
    </Card> : !d.canEdit ? <Note kind="warn">No asset recorded yet.</Note> : null}
    {d.canEdit && <Collapse title={a ? 'Edit the asset, quotation, invoice and insurance' : 'Start the asset'} open={!a} testID="asset-form">
      <Choice label="Asset class" value={f.assetClass} options={(d.classes ?? []).map((c: any) => [c.assetClass, c.name ?? label(c.assetClass)])} onChange={x => setF({ ...f, assetClass: x })} testID="f-assetClass" />
      <Select label="Dealer" value={f.dealerId} options={(d.dealers ?? []).map((x: any) => [String(x.id), x.name])} onChange={x => setF({ ...f, dealerId: x })} testID="f-dealer" />
      {a && ASSET_GROUPS.map(([g, fields]) => <View key={g}><Text style={{ fontWeight: '700', color: C.ink, marginVertical: 6 }}>{g}</Text><Form fields={fields} value={f} onChange={setF} /></View>)}
      {a && <Choice label="Insurance names the lender (hypothecation)" value={f.insuranceHypothecated} options={[['true', 'Yes'], ['false', 'No']]} onChange={x => setF({ ...f, insuranceHypothecated: x })} />}
      <Muted>{a ? 'Fields read from the quotation, invoice and insurance by Document AI are filled in; correct them here.' : 'Choose the class and dealer, then upload the quotation: Document AI fills the details.'}</Muted>
      <Btn title={a ? 'Save and check' : 'Save'} kind="primary" busy={busy} disabled={!f.assetClass} testID="asset-save" onPress={save} />
    </Collapse>}
    {a && d.canVerify && a.downPayment != null && !a.downPaymentVerifiedBy && <Btn title="Verify down payment" kind="primary" busy={busy} testID="verify-dp" onPress={() => act(() => api.post(base + '/verify-down-payment', { reference: a.downPaymentRef }), 'Down payment verified')} />}
    {a && (d.canEdit || d.disbursed) && docs.length > 0 && <UploadDoc title={d.disbursed ? 'Documents due after disbursement' : 'Asset documents'} types={docs.map(x => x[0])} labels={Object.fromEntries(docs)} path={base + '/documents'} onDone={done} />}
    <Checks checks={e?.checks} title="Asset checks" rule={d.canRule ? (check, note) => act(() => api.post(base + '/exceptions', { check, note }), 'Exception recorded') : undefined} />
    {(d.documents ?? []).length > 0 && <Card title="Documents">{d.documents.map((x: any) => <Row key={x.id} k={label(x.docType)} v={`${x.fileName}${x.readConfidence != null ? ` · ${Math.round(x.readConfidence * 100)}%` : ''}`} />)}</Card>}
    {a && <Btn title="Re-run checks" busy={busy} onPress={() => act(() => api.post(base + '/evaluate'), 'Checks re-run')} />}
  </>
}

function TradeStep({ d, base, act, done, busy }: P) {
  const l = d.limit
  const programme = d.kind !== 'RECEIVABLES'
  const [gstin, setGstin] = useState(l?.borrowerGstin ?? d.suggestedGstin ?? '')
  const [anchor, setAnchor] = useState(d.anchor ? String(d.anchor.id) : '')
  const [subs, setSubs] = useState<Record<string, string>>(() => Object.fromEntries((d.sublimits ?? []).map((s: any) => [String(s.counterpartyId), String(s.amount)])))
  const [buyer, setBuyer] = useState('')
  const [freeze, setFreeze] = useState('')
  const total = Object.values(subs).reduce((t, x) => t + (Number(x) || 0), 0)
  const a = (d.anchors ?? []).find((x: any) => String(x.id) === anchor)
  return <>
    {l ? <Card>
      <Row k="Limit" v={`${l.limitNo} · ${inr(l.sanctioned ?? l.limit)}`} />
      <Row k="Status" v={<Pill s={l.status} />} testID="limit-status" />
      <Row k="Used · available" v={`${inr(l.utilised)} · ${inr(l.available)}`} />
      {d.anchor ? <Row k="Anchor" v={d.anchor.name} /> : null}
      {l.overdue ? <Row k="Overdue" v={inr(l.overdue)} /> : null}
      {l.frozenReason ? <Note kind="bad">Frozen: {l.frozenReason}</Note> : null}
    </Card> : null}
    {d.canEditStructure && <Card title={programme ? 'Programme and limit' : 'Buyers and sub-limits'} testID="trade-structure">
      <Muted>{d.kind === 'RECEIVABLES' ? 'Invoices on approved buyers are financed; each buyer gets a sub-limit and pays on the due date.'
        : d.kind === 'VENDOR' ? 'The borrower supplies an anchor; invoices the anchor accepts are financed and the anchor pays.' : 'The borrower buys from an anchor; the lender pays the anchor and the dealer repays.'}</Muted>
      <Input label="Borrower GSTIN" value={gstin} onChangeText={x => setGstin(x.toUpperCase())} autoCapitalize="characters" testID="f-gstin" />
      {programme && <Select label="Anchor" value={anchor} options={(d.anchors ?? []).map((x: any) => [String(x.id), `${x.name} · ${x.rating ?? 'unrated'}`])} onChange={setAnchor} testID="f-anchor" />}
      {a ? <Muted>{Math.round((a.advanceRate ?? 0) * 100)}% advance · up to {a.maxTenorDays} days · repaid by {String(a.repaidBy ?? '').toLowerCase()}</Muted> : null}
      {!programme && (d.buyers ?? []).map((b: any) => (
        <View key={b.id} style={{ borderTopWidth: 1, borderTopColor: C.line, paddingTop: 6 }}>
          <Text style={{ color: C.ink, fontWeight: '600' }}>{b.name}</Text>
          <Muted>{b.rating ?? 'unrated'} · {Math.round((b.advanceRate ?? 0) * 100)}% advance · {b.maxTenorDays} days · confirmation {b.confirmationRequired ? 'needed' : 'not needed'}{b.status !== 'ACTIVE' ? ' · ' + String(b.status).toLowerCase() : !b.empanelled ? ' · not empanelled' : ''}</Muted>
          <Input label={'Sub-limit ' + b.name + ' (₹)'} keyboardType="numeric" value={subs[String(b.id)] ?? ''} onChangeText={x => setSubs({ ...subs, [String(b.id)]: x.replace(/[^0-9.]/g, '') })} testID={'sub-' + b.code} />
        </View>
      ))}
      {!programme ? <Muted>Total of the sub-limits {inr(total)} (limit asked {inr(d.requested)})</Muted> : null}
      <Btn title="Save and check" kind="primary" busy={busy} testID="trade-save" onPress={() => act(() => api.put(base, { borrowerGstin: gstin, anchorId: anchor ? Number(anchor) : undefined,
        sublimits: programme ? undefined : Object.entries(subs).filter(([, x]) => Number(x) > 0).map(([k, x]) => ({ counterpartyId: Number(k), amount: Number(x) })) }), 'Limit structure saved and checked')} />
    </Card>}
    {l && (d.sublimits ?? []).length > 0 && !d.canEditStructure && <Card title="Buyer sub-limits">{d.sublimits.map((s: any) => <Row key={s.counterpartyId} k={s.name} v={`${inr(s.used)} of ${inr(s.amount)}`} />)}</Card>}
    <Checks checks={d.structure} title="Structure checks" />
    {d.canSubmit && <UploadDoc title="Submit an invoice" types={['-']} labels={{ '-': 'Invoice' }} path={base + '/invoices'} extra={buyer ? [{ name: 'counterpartyId', value: buyer }] : []} onDone={done}>
      {!programme && (d.sublimits ?? []).length > 0 && <Choice label="Buyer (or let Document AI read it)" value={buyer} options={[['', 'Read from the invoice'], ...d.sublimits.map((s: any) => [String(s.counterpartyId), s.name] as [string, string])]} onChange={setBuyer} />}
    </UploadDoc>}
    {(d.invoices ?? []).map((x: any) => <Invoice key={x.id} x={x} d={d} act={act} busy={busy} />)}
    {d.canRule && l && (l.status === 'ACTIVE' || l.status === 'FROZEN') && <Card title={l.status === 'FROZEN' ? 'Unfreeze the limit' : 'Freeze the limit'}>
      <Input label="Reason (at least 10 characters)" multiline value={freeze} onChangeText={setFreeze} />
      <Btn small kind={l.status === 'FROZEN' ? 'primary' : 'danger'} title={l.status === 'FROZEN' ? 'Unfreeze' : 'Freeze'} disabled={freeze.trim().length < 10} busy={busy}
        onPress={() => act(() => api.post(`${base}/${l.status === 'FROZEN' ? 'unfreeze' : 'freeze'}`, { note: freeze }), l.status === 'FROZEN' ? 'Limit unfrozen' : 'Limit frozen').then(() => setFreeze(''))} />
    </Card>}
  </>
}

function Invoice({ x, d, act, busy }: { x: any; d: any; act: P['act']; busy: boolean }) {
  const [ref, setRef] = useState('')
  const [amount, setAmount] = useState(String(x.due ? Number(x.due).toFixed(2) : ''))
  const live = ['FUNDED', 'PART_PAID'].includes(x.status)
  const open = ['SUBMITTED', 'CONFIRMED'].includes(x.status)
  const needsConfirmation = (x.checks ?? []).some((c: Check) => c.id === 'TF-10' && c.result === 'PENDING')
  return (
    <Card title={`Invoice ${x.invoiceNo ?? x.refNo}`} testID={'invoice-' + x.refNo}>
      <Row k="Status" v={<Pill s={x.status} />} />
      {x.counterparty ? <Row k="Counterparty" v={x.counterparty} /> : null}
      <Row k="Total · advance" v={`${inr(x.total)} · ${inr(x.advance)}`} />
      {x.dueDate ? <Row k="Due" v={x.dueDate} /> : null}
      {live ? <Row k="Funded · due now" v={`${inr(x.funded)} · ${inr(x.due)}`} /> : null}
      {x.checksStatus ? <Row k="Checks" v={<Pill s={x.checksStatus} />} /> : null}
      {d.canRule && (x.checks ?? []).some((c: Check) => c.result === 'REFER' && !c.ruling)
        ? <Checks checks={(x.checks ?? []).filter((c: Check) => c.result !== 'PASS')} title="Checks to clear" rule={(check, note) => act(() => api.post(`/api/trade-invoices/${x.id}/exceptions`, { check, note }), 'Exception recorded')} />
        : (x.checks ?? []).filter((c: Check) => c.result !== 'PASS').map((c: Check) => <Muted key={c.id}>{c.id} {c.label}: {c.reason}{c.ruling ? ' · exception: ' + c.ruling : ''}</Muted>)}
      {d.canOperate && open && needsConfirmation && <>
        <Input label="Counterparty confirmation reference" value={ref} onChangeText={setRef} />
        <Btn small title="Record confirmation" disabled={ref.trim().length < 4} busy={busy} onPress={() => act(() => api.post(`/api/trade-invoices/${x.id}/confirm`, { reference: ref }), 'Confirmation recorded')} />
      </>}
      {d.canOperate && open && x.checksStatus === 'PASS' && (x.submittedBy === d.me ? <Muted>Another person funds it (maker-checker).</Muted>
        : <Btn title={`Fund ${inr(x.advance)}`} kind="primary" busy={busy} testID={'fund-' + x.refNo} onPress={() => act(() => api.post(`/api/trade-invoices/${x.id}/fund`), 'Drawdown funded')} />)}
      {d.canOperate && live && <>
        <Input label="Amount received (₹)" keyboardType="numeric" value={amount} onChangeText={setAmount} />
        <Input label="Reference (UTR / cheque)" value={ref} onChangeText={setRef} />
        <Btn small title="Record repayment" disabled={!Number(amount)} busy={busy} onPress={() => act(() => api.post(`/api/trade-invoices/${x.id}/repay`, { amount: Number(amount), reference: ref }), 'Repayment recorded')} />
      </>}
    </Card>
  )
}

const PLAN_FORMS: Record<string, F[]> = {
  HOUSING: [
    { key: 'purpose', label: 'Purpose', type: 'select', options: [['CONSTRUCTION', 'Construction on own plot'], ['PURCHASE', 'Purchase of a house or flat'], ['EXTENSION', 'Extension or improvement']] },
    { key: 'estimate', label: 'Cost (estimate or agreement value, ₹)', type: 'num' }, { key: 'ownContribution', label: 'Own contribution (₹)', type: 'num' },
    { key: 'carpetAreaSqft', label: 'Carpet area (sq ft)', type: 'num' }, { key: 'builtUpAreaSqft', label: 'Built-up area (sq ft)', type: 'num' },
    { key: 'planNo', label: 'Approved plan / building permission no.' }, { key: 'propertyAddress', label: 'Property address' }],
  EDUCATION: [
    { key: 'studentName', label: 'Student' }, { key: 'studentDob', label: "Student's date of birth", type: 'date' },
    { key: 'relationship', label: 'Borrower is the student’s', type: 'select', options: [['PARENT', 'Parent'], ['GUARDIAN', 'Guardian'], ['SPOUSE', 'Spouse'], ['SELF', 'Self (the student)']] },
    { key: 'institutionId', label: 'Institution', type: 'select' }, { key: 'course', label: 'Course' }, { key: 'courseStart', label: 'Course start', type: 'date' },
    { key: 'durationMonths', label: 'Duration (months)', type: 'num' }, { key: 'admissionNo', label: 'Admission no.' }, { key: 'academicYear', label: 'Academic year' }],
  AGRI: [
    { key: 'activity', label: 'Activity', type: 'select', options: [['CROP', 'Crop cultivation'], ['HORTICULTURE', 'Horticulture'], ['DAIRY', 'Dairy'], ['POULTRY', 'Poultry'], ['FARM_EQUIPMENT', 'Farm equipment']] },
    { key: 'crop', label: 'Crops' }, { key: 'landAreaAcres', label: 'Land (acres)', type: 'num' }, { key: 'surveyNo', label: 'Gat / survey no.' }, { key: 'village', label: 'Village' },
    { key: 'scalePerUnit', label: 'Scale of finance (₹ per acre or unit)', type: 'num' }, { key: 'units', label: 'Units (animals, machines)', type: 'num' },
    { key: 'ownContribution', label: 'Own contribution (₹)', type: 'num' }, { key: 'harvestMonths', label: 'Harvest months (repayment)' }, { key: 'insuranceRef', label: 'Insurance (PMFBY / livestock) ref.' }],
  RENTAL: [
    { key: 'tenantName', label: 'Tenant' }, { key: 'tenantType', label: 'Kind of tenant', type: 'select', options: [['BANK', 'Bank'], ['CORPORATE', 'Company'], ['GOVERNMENT', 'Government'], ['RETAIL', 'Shop / retail'], ['INDIVIDUAL', 'Individual']] },
    { key: 'monthlyRent', label: 'Monthly rent (₹)', type: 'num' }, { key: 'leaseStart', label: 'Lease start', type: 'date' }, { key: 'leaseEnd', label: 'Lease end', type: 'date' },
    { key: 'lockInMonths', label: 'Lock-in (months)', type: 'num' }, { key: 'escalationPct', label: 'Escalation (%)', type: 'num' }, { key: 'escrowAccount', label: 'Escrow account for the rent' },
    { key: 'propertyAddress', label: 'Property address' }],
}
const PLAN_TITLE: Record<string, string> = { HOUSING: 'Property & stages', EDUCATION: 'Course & fees', AGRI: 'Farm & activity', RENTAL: 'Lease & rent' }
type TRow = { label: string; amount: string; stagePct: string }

function PlanStep({ d, base, act, done, busy }: P) {
  const [seq, setSeq] = useState('')
  const fields = (PLAN_FORMS[d.kind] ?? []).map(f => f.key === 'institutionId' ? { ...f, options: (d.parties ?? []).map((x: any) => [String(x.id), x.name + (x.status !== 'ACTIVE' ? ` (${String(x.status).toLowerCase()})` : '')] as [string, string]) } : f)
  const [form, setForm] = useState<Record<string, string>>(() => toForm(d.details, fields.map(f => f.key)))
  const [rows, setRows] = useState<TRow[]>(() => (d.tranches ?? []).map((t: any) => ({ label: t.label, amount: String(t.amount), stagePct: t.stagePct == null ? '' : String(t.stagePct) })))
  useEffect(() => { setForm(toForm(d.details, fields.map(f => f.key))) }, [JSON.stringify(d.details)]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { setRows((d.tranches ?? []).map((t: any) => ({ label: t.label, amount: String(t.amount), stagePct: t.stagePct == null ? '' : String(t.stagePct) }))) }, [JSON.stringify((d.tranches ?? []).map((t: any) => [t.label, t.amount, t.stagePct]))]) // eslint-disable-line react-hooks/exhaustive-deps
  const staged = d.kind === 'HOUSING' || d.kind === 'EDUCATION'
  const sum = rows.reduce((t, x) => t + (Number(x.amount) || 0), 0)
  const save = () => act(() => api.put(base, { details: fromForm(form, fields),
    tranches: staged ? rows.filter(x => Number(x.amount) > 0).map(x => ({ label: x.label, amount: Number(x.amount), stagePct: x.stagePct === '' ? null : Number(x.stagePct) })) : undefined }), 'Plan saved and checked')
  const setRow = (i: number, k: keyof TRow, x: string) => setRows(rows.map((y, j) => j === i ? { ...y, [k]: k === 'label' ? x : x.replace(/[^0-9.]/g, '') } : y))
  return <>
    <Card>
      <Row k="Loan" v={`${inr(d.loan)} · ${d.tenureMonths} months`} />
      {d.sanctioned ? <Row k="Sanctioned" v={inr(d.sanctioned)} /> : null}
      <Row k="Plan" v={<Pill s={d.status} />} testID="plan-status" />
      {!d.canEdit && Object.entries(d.details ?? {}).filter(([, v]) => v !== null && v !== '' && typeof v !== 'object').slice(0, 8).map(([k, v]) => <Row key={k} k={label(k)} v={String(v)} />)}
    </Card>
    {(d.conditions ?? []).length > 0 && <Note kind="warn">{d.conditions.join(' · ')}</Note>}
    {d.canEdit && <Collapse title={PLAN_TITLE[d.kind] ?? 'Plan details'} open={!d.tranches?.length && !Object.values(d.details ?? {}).some(x => x != null && x !== '')} testID="plan-form">
      <Form fields={fields} value={form} onChange={setForm} />
      {staged && <>
        <Text style={{ fontWeight: '700', color: C.ink, marginVertical: 6 }}>{d.kind === 'HOUSING' ? 'Construction stages (released with progress)' : 'Term-wise fee releases (paid to the institution)'}</Text>
        {rows.map((x, i) => (
          <View key={i} style={{ borderTopWidth: 1, borderTopColor: C.line, paddingTop: 6 }}>
            <Input label={`${d.kind === 'HOUSING' ? 'Stage' : 'Release'} ${i + 1}`} value={x.label} onChangeText={t => setRow(i, 'label', t)} testID={'tr-label-' + (i + 1)} />
            <View style={{ flexDirection: 'row', gap: 8 }}>
              <View style={{ flex: 1 }}><Input label="Amount (₹)" keyboardType="numeric" value={x.amount} onChangeText={t => setRow(i, 'amount', t)} testID={'tr-amount-' + (i + 1)} /></View>
              {d.kind === 'HOUSING' && <View style={{ flex: 1 }}><Input label="Progress needed (%)" keyboardType="numeric" value={x.stagePct} onChangeText={t => setRow(i, 'stagePct', t)} testID={'tr-pct-' + (i + 1)} /></View>}
            </View>
            <Btn small title="Remove" onPress={() => setRows(rows.filter((_, j) => j !== i))} />
          </View>
        ))}
        <Btn small title={d.kind === 'HOUSING' ? 'Add stage' : 'Add release'} testID="add-tranche"
          onPress={() => setRows([...rows, { label: d.kind === 'EDUCATION' ? `Term ${rows.length + 1} fee` : `Stage ${rows.length + 1}`, amount: '', stagePct: '' }])} />
        <Muted>Total {inr(sum)} of {inr(d.sanctioned ?? d.loan)}</Muted>
      </>}
      <Btn title="Save and check" kind="primary" busy={busy} testID="plan-save" onPress={save} />
    </Collapse>}
    {d.canUpload && (d.docTypes ?? []).length > 0 && <UploadDoc title="Plan documents" types={[...d.docTypes].sort()} path={base + '/documents'} extra={seq ? [{ name: 'trancheSeq', value: seq }] : []} onDone={done}>
      {(d.tranches ?? []).length > 1 && <Choice label="For stage (optional)" value={seq} options={[['', 'Whole plan'], ...d.tranches.map((t: any) => [String(t.seq), `${t.seq}. ${t.label}`] as [string, string])]} onChange={setSeq} />}
    </UploadDoc>}
    <Checks checks={d.checks} title="Plan checks" rule={d.canRule ? (check, note) => act(() => api.post(base + '/exceptions', { check, note }), 'Exception recorded') : undefined} />
    {(d.tranches ?? []).map((t: any) => <Tranche key={t.id} t={t} d={d} act={act} busy={busy} />)}
    {(d.documents ?? []).length > 0 && <Card title="Documents">{d.documents.map((x: any) => <Row key={x.id} k={label(x.docType) + (x.trancheSeq ? ` (stage ${x.trancheSeq})` : '')} v={x.fileName} />)}</Card>}
  </>
}

function Tranche({ t, d, act, busy }: { t: any; d: any; act: P['act']; busy: boolean }) {
  const [p, setP] = useState(t.stagePct ? String(t.stagePct) : '')
  const [note, setNote] = useState('')
  return (
    <Card title={`${t.seq}. ${t.label}`} testID={'tranche-' + t.seq}>
      <Row k="Amount" v={inr(t.amount)} />
      <Row k="Status" v={<Pill s={t.status} />} />
      {t.payee ? <Row k="Paid to" v={t.payee} /> : null}
      {t.certifiedPct != null ? <Row k="Inspected" v={`${t.certifiedPct}% · ${t.certifiedBy ?? ''}`} /> : null}
      {t.utr ? <Row k="UTR" v={t.utr} /> : null}
      {(t.releaseChecks ?? []).filter((c: Check) => c.result !== 'PASS').map((c: Check) => <Muted key={c.id}>{c.id} {c.label}: {c.reason}</Muted>)}
      {d.canCertify && !t.paidAt && t.seq > 1 && <>
        <Input label="Work complete (%)" keyboardType="numeric" value={p} onChangeText={setP} />
        <Input label="Inspection note" multiline value={note} onChangeText={setNote} />
        <Btn small title="Record inspection" disabled={!Number(p) || note.trim().length < 10} busy={busy} testID={'certify-' + t.seq}
          onPress={() => act(() => api.post(`/api/tranches/${t.id}/certify`, { percent: Number(p), note }), 'Inspection recorded')} />
      </>}
      {d.canRelease && t.release === 'PASS' && !t.paidAt && <Btn title={`Release ${inr(t.amount)}`} kind="primary" busy={busy} testID={'release-' + t.seq}
        onPress={() => act(() => api.post(`/api/tranches/${t.id}/release`), 'Release paid')} />}
    </Card>
  )
}

function PledgeStep({ d, base, act, done, busy }: P) {
  const p = d.pledge
  const sec = d.kind === 'SECURITIES'
  const [lienRef, setLienRef] = useState('')
  const [top, setTop] = useState({ isin: '', quantity: '', ref: '' })
  const [pay, setPay] = useState({ amount: '', ref: '' })
  const open = (d.marginCalls ?? []).find((c: any) => c.status === 'OPEN')
  const pfields: F[] = [{ key: 'holderName', label: 'Holder' }, ...(sec ? [{ key: 'depository', label: 'Depository (CDSL / NSDL)' }, { key: 'dematId', label: 'BO / client ID' }] as F[]
    : [{ key: 'issuer', label: 'Issuer (bank)' }, { key: 'depositNo', label: 'Deposit no.' }, { key: 'principal', label: 'Amount (₹)', type: 'num' }, { key: 'ratePa', label: 'Rate (% a year)', type: 'num' },
      { key: 'startDate', label: 'Deposit date', type: 'date' }, { key: 'maturityDate', label: 'Maturity', type: 'date' }] as F[])]
  const [pf, setPf] = useState<Record<string, string>>(() => toForm(p, pfields.map(f => f.key)))
  useEffect(() => { setPf(toForm(p, pfields.map(f => f.key))) }, [p?.updatedAt]) // eslint-disable-line react-hooks/exhaustive-deps
  return <>
    <Card>
      <Row k="Loan" v={inr(d.loan)} />
      {d.outstanding != null ? <Row k="Outstanding" v={inr(d.outstanding)} /> : null}
      {p?.value != null ? <Row k="Value" v={inr(p.value)} /> : null}
      {p?.drawingPower != null ? <Row k="Drawing power" v={inr(p.drawingPower)} testID="drawing-power" /> : null}
      {!sec && p?.issuer ? <Row k="Deposit" v={`${p.issuer} · ${p.depositNo ?? ''}`} /> : null}
      <Row k="Lien" v={<Pill s={p?.lienState ?? 'NONE'} />} testID="lien" />
      <Row k="Ready" v={d.readyForDisbursement ? 'For disbursement' : d.readyForSanction ? 'For sanction' : 'Not yet'} />
    </Card>
    {d.canEdit && <UploadDoc title={sec ? 'Statement of holdings' : 'Deposit receipt'} types={sec ? ['CAS_STATEMENT', 'LIEN_CONFIRMATION'] : ['FD_RECEIPT', 'LIEN_CONFIRMATION']}
      labels={{ CAS_STATEMENT: 'CAS statement (holdings)', FD_RECEIPT: 'Fixed deposit receipt', LIEN_CONFIRMATION: 'Lien / pledge confirmation' }} path={base + '/documents'} onDone={done} />}
    {d.canEdit && <Collapse title={sec ? 'Holder and demat account' : 'Deposit details'} testID="pledge-form">
      <Muted>Filled from the {sec ? 'CAS statement' : 'deposit receipt'} by Document AI; correct them here.</Muted>
      <Form fields={pfields} value={pf} onChange={setPf} />
      <Btn title="Save and check" kind="primary" busy={busy} testID="pledge-save" onPress={() => act(() => api.put(base, fromForm(pf, pfields)), 'Pledge saved and checked')} />
    </Collapse>}
    <Checks checks={d.checks} title={sec ? 'Securities checks' : 'Deposit checks'} hard={['PL-03', 'FD-03']} rule={d.canRule ? (check, note) => act(() => api.post(base + '/exceptions', { check, note }), 'Exception recorded') : undefined} />
    {sec && (d.holdings ?? []).length > 0 && <Card title="Holdings">{d.holdings.map((h: any) => <Row key={h.isin} k={h.name ?? h.isin} v={`${h.quantity} · ${inr(h.value)}${h.approved ? '' : ' · not approved'}`} />)}</Card>}
    {d.canLien && p && p.lienState !== 'MARKED' && d.readyForSanction && <Card title="Mark the lien">
      <Input label={sec ? 'Depository pledge reference (PSN)' : 'Lien reference from the bank'} value={lienRef} onChangeText={setLienRef} />
      <Btn title="Mark lien" kind="primary" disabled={lienRef.trim().length < 4} busy={busy} testID="mark-lien" onPress={() => act(() => api.post(base + '/lien', { ref: lienRef }), 'Lien marked')} />
    </Card>}
    {(d.marginCalls ?? []).length > 0 && <Card title="Margin calls">
      {d.marginCalls.map((c: any) => <Row key={c.id} k={`${when(c.createdAt)} · due ${c.dueBy}`} v={<Pill s={c.status} text={`${label(c.status)} · ${inr(c.shortfall)}`} />} />)}
    </Card>}
    {open && d.canCure && <Card title="Cure the margin call" testID="cure">
      {sec && <>
        <Choice label="Pledge more of" value={top.isin} options={(d.securities ?? []).filter((s: any) => s.approved).map((s: any) => [s.isin, s.name])} onChange={v => setTop({ ...top, isin: v })} />
        <Input label="Quantity" keyboardType="numeric" value={top.quantity} onChangeText={v => setTop({ ...top, quantity: v })} />
        <Input label="Pledge reference" value={top.ref} onChangeText={v => setTop({ ...top, ref: v })} />
        <Btn small title="Add to pledge" disabled={!top.isin || !Number(top.quantity) || top.ref.trim().length < 4} busy={busy}
          onPress={() => act(() => api.post(base + '/top-up', { isin: top.isin, quantity: Number(top.quantity), ref: top.ref }), 'Pledge topped up')} />
      </>}
      <Input label="Part-payment (₹)" keyboardType="numeric" value={pay.amount} onChangeText={v => setPay({ ...pay, amount: v })} />
      <Input label="Payment reference" value={pay.ref} onChangeText={v => setPay({ ...pay, ref: v })} />
      <Btn small title="Record payment" disabled={!Number(pay.amount)} busy={busy} onPress={() => act(() => api.post(base + '/part-payment', { amount: Number(pay.amount), ref: pay.ref }), 'Part-payment recorded')} />
    </Card>}
  </>
}
