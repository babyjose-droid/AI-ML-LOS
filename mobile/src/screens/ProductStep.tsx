// Product-specific steps on the phone: asset finance (asset, dealer, documents, down payment), trade finance (limit and
// invoices), staged plans (documents, site inspection, tranche release) and loans against securities or deposits
// (statements, lien, margin calls). Set-up screens with many fields (plan stages, limit sub-limits) stay on the web console.
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

function Checks({ checks, title = 'Checks' }: { checks?: Check[]; title?: string }) {
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
        </View>
      ))}
    </Card>
  )
}

/** Upload a document of one of the given types through the outbox. */
function UploadDoc({ types, path, extra, title, onDone, labels }: { types: string[]; path: string; extra?: { name: string; value: string }[]; title: string; onDone: (m: string) => void; labels?: Record<string, string> }) {
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

function AssetStep({ d, base, act, done, busy }: P) {
  const a = d.asset, e = d.evaluation
  const [cls, setCls] = useState(d.classes?.[0]?.assetClass ?? '')
  const [dealer, setDealer] = useState('')
  const docs: [string, string][] = d.disbursed ? (d.postDocs ?? []).filter((p: any) => p.status !== 'RECEIVED').map((p: any) => [p.type, p.label]) : ASSET_DOCS
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
    </Card> : d.canEdit ? <Card title="Start the asset">
      <Choice label="Asset class" value={cls} options={(d.classes ?? []).map((c: any) => [c.assetClass, c.name ?? label(c.assetClass)])} onChange={setCls} />
      <Select label="Dealer" value={dealer} options={(d.dealers ?? []).map((x: any) => [String(x.id), x.name])} onChange={setDealer} />
      <Btn title="Save" kind="primary" busy={busy} disabled={!cls} onPress={() => act(() => api.put(base, { assetClass: cls, dealerId: dealer ? Number(dealer) : undefined, insuranceHypothecated: false }), 'Asset saved')} />
    </Card> : <Note kind="warn">No asset recorded yet.</Note>}
    {a && d.canVerify && a.downPayment != null && !a.downPaymentVerifiedBy && <Btn title="Verify down payment" kind="primary" busy={busy} testID="verify-dp" onPress={() => act(() => api.post(base + '/verify-down-payment', { reference: a.downPaymentRef }), 'Down payment verified')} />}
    {a && (d.canEdit || d.disbursed) && docs.length > 0 && <UploadDoc title={d.disbursed ? 'Documents due after disbursement' : 'Asset documents'} types={docs.map(x => x[0])} labels={Object.fromEntries(docs)} path={base + '/documents'} onDone={done} />}
    <Checks checks={e?.checks} title="Asset checks" />
    {(d.documents ?? []).length > 0 && <Card title="Documents">{d.documents.map((x: any) => <Row key={x.id} k={label(x.docType)} v={`${x.fileName}${x.readConfidence != null ? ` · ${Math.round(x.readConfidence * 100)}%` : ''}`} />)}</Card>}
    {a && <Btn title="Re-run checks" busy={busy} onPress={() => act(() => api.post(base + '/evaluate'), 'Checks re-run')} />}
  </>
}

function TradeStep({ d, base, act, done, busy }: P) {
  const l = d.limit
  const programme = d.kind !== 'RECEIVABLES'
  const [gstin, setGstin] = useState(d.suggestedGstin ?? '')
  const [anchor, setAnchor] = useState('')
  const [buyer, setBuyer] = useState('')
  return <>
    {l ? <Card>
      <Row k="Limit" v={`${l.limitNo} · ${inr(l.sanctioned ?? l.limit)}`} />
      <Row k="Status" v={<Pill s={l.status} />} testID="limit-status" />
      <Row k="Used · available" v={`${inr(l.utilised)} · ${inr(l.available)}`} />
      {d.anchor ? <Row k="Anchor" v={d.anchor.name} /> : null}
      {l.overdue ? <Row k="Overdue" v={inr(l.overdue)} /> : null}
    </Card> : null}
    {d.canEditStructure && <Card title="Limit structure">
      <Input label="Borrower GSTIN" value={gstin} onChangeText={setGstin} autoCapitalize="characters" />
      {programme && d.anchors?.length > 0 && <Choice label="Anchor" value={anchor} options={d.anchors.map((x: any) => [String(x.id), x.name])} onChange={setAnchor} />}
      {!programme && d.buyers?.length > 0 && <Choice label="Buyer (sub-limit for the full amount)" value={buyer} options={d.buyers.map((x: any) => [String(x.id), x.name])} onChange={setBuyer} />}
      <Btn title="Save structure" kind="primary" busy={busy} onPress={() => act(() => api.put(base, { borrowerGstin: gstin, anchorId: anchor ? Number(anchor) : undefined,
        sublimits: programme || !buyer ? undefined : [{ counterpartyId: Number(buyer), amount: d.requested }] }), 'Limit structure saved and checked')} />
    </Card>}
    <Checks checks={d.structure} title="Structure checks" />
    {d.canSubmit && <UploadDoc title="Submit an invoice" types={['-']} labels={{ '-': 'Invoice' }} path={base + '/invoices'} onDone={done} />}
    {(d.invoices ?? []).map((x: any) => <Invoice key={x.id} x={x} d={d} act={act} busy={busy} />)}
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
      {(x.checks ?? []).filter((c: Check) => c.result !== 'PASS').map((c: Check) => <Muted key={c.id}>{c.id} {c.label}: {c.reason}</Muted>)}
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

function PlanStep({ d, base, act, done, busy }: P) {
  const [seq, setSeq] = useState('')
  return <>
    <Card>
      <Row k="Loan" v={`${inr(d.loan)} · ${d.tenureMonths} months`} />
      {d.sanctioned ? <Row k="Sanctioned" v={inr(d.sanctioned)} /> : null}
      <Row k="Plan" v={<Pill s={d.status} />} testID="plan-status" />
      {Object.entries(d.details ?? {}).filter(([, v]) => v !== null && v !== '' && typeof v !== 'object').slice(0, 8).map(([k, v]) => <Row key={k} k={label(k)} v={String(v)} />)}
    </Card>
    {(d.conditions ?? []).length > 0 && <Note kind="warn">{d.conditions.join(' · ')}</Note>}
    {d.canUpload && (d.docTypes ?? []).length > 0 && <>
      {(d.tranches ?? []).length > 1 && <Choice label="For stage (optional)" value={seq} options={[['', 'Whole plan'], ...d.tranches.map((t: any) => [String(t.seq), `${t.seq}. ${t.label}`])]} onChange={setSeq} />}
      <UploadDoc title="Plan documents" types={[...d.docTypes].sort()} path={base + '/documents'} extra={seq ? [{ name: 'trancheSeq', value: seq }] : []} onDone={done} />
    </>}
    <Checks checks={d.checks} title="Plan checks" />
    {(d.tranches ?? []).map((t: any) => <Tranche key={t.id} t={t} d={d} act={act} busy={busy} />)}
    {(d.documents ?? []).length > 0 && <Card title="Documents">{d.documents.map((x: any) => <Row key={x.id} k={label(x.docType) + (x.trancheSeq ? ` (stage ${x.trancheSeq})` : '')} v={x.fileName} />)}</Card>}
    {!d.tranches?.length && <Muted>Plan details and stages are set on the web console.</Muted>}
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
    <Checks checks={d.checks} title={sec ? 'Securities checks' : 'Deposit checks'} />
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
