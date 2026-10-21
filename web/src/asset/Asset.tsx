import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, getToken } from '../api'
import { ErrorBox, Head, KpiStrip, Table, useLoad, useTab } from '../components/ui'
import { inr, label, pct, when } from '../format'

// Asset finance: the asset on an application (dealer, quotation, invoice, down payment, insurance, checks, cover, documents due after
// disbursement), the dealers and suppliers master with the asset classes, and the asset finance book.

export interface AssetClass { assetClass: string; label: string; maxLtv: number; minDownPct: number; depRate: number; maxAgeYears: number; insuranceRequired: boolean
  registrationRequired: boolean; serialLabel: string; quoteVariance: number; fleetLimit?: number; postDocs?: string; postDays: number; updatedBy?: string; updatedAt?: string }
export interface DealerRow { id: number; code: string; name: string; kind: string; categories: string; oem?: string; gstin?: string; city?: string; account?: string; ifsc?: string
  empanelled: boolean; status: string; maxExposure?: number; exposure: number; loans: number }
interface AssetRec { id: number; assetNo: string; assetClass: string; dealerId?: number; make?: string; model?: string; variant?: string; manufactureYear?: number; units: number
  serialNo?: string; engineNo?: string; registrationNo?: string; quoteNo?: string; quoteDate?: string; quoteAmount?: number; invoiceNo?: string; invoiceDate?: string
  basePrice?: number; gstAmount?: number; otherCharges?: number; invoiceTotal?: number; invoiceGstin?: string; downPayment?: number; downPaymentRef?: string
  downPaymentVerifiedBy?: string; downPaymentVerifiedAt?: string; insurer?: string; insurancePolicyNo?: string; insuredValue?: number; insuranceExpiry?: string
  insuranceHypothecated: boolean; subsidyAmount?: number; status: string; maxLoan?: number; lienState: string; disbursedTo?: string }
interface Check { id: string; label: string; result: string; reason: string; ruling?: string }
interface Evaluation { status: string; assetCost: number; netCost: number; maxLoan: number; ltv: number; downPct: number; checks: Check[]; beforeDisbursement: string[]
  cover: { year: number; assetValue: number; outstanding: number; cover?: number }[]; conditions: string[] }
interface ADoc { id: number; docType: string; fileName: string; readConfidence?: number; engine?: string; uploadedBy: string; uploadedAt: string; fieldsJson?: string }
interface PostDoc { type: string; label: string; dueBy: string; status: string; receivedAt?: string; by?: string }
interface View { product: string; classes: AssetClass[]; dealers: DealerRow[]; asset?: AssetRec; dealer?: DealerRow; documents: ADoc[]; evaluation?: Evaluation
  postDocs: PostDoc[]; fleet: { loans: { appNo: string; assetNo: string; asset: string; serialNo?: string; registrationNo?: string; amount: number; state: string }[]; otherExposure: number; limit?: number }
  canEdit: boolean; canVerify: boolean; canRule: boolean; disbursed: boolean }

const ST: Record<string, string> = { PASS: 'ok', REFER: 'warn', FAIL: 'bad', PENDING: 'muted', RECEIVED: 'ok', DUE: 'warn', OVERDUE: 'bad', MARKED: 'ok', NONE: 'muted', ACTIVE: 'ok', SUSPENDED: 'bad' }
const St = ({ s }: { s?: string }) => <span className={'pill ' + (ST[s ?? ''] ?? 'muted')}>{(s ?? '—').replace(/_/g, ' ').toLowerCase()}</span>
const PRE_DOCS: [string, string][] = [['QUOTATION', 'Quotation / proforma'], ['INVOICE', 'Tax invoice'], ['DOWN_PAYMENT_RECEIPT', 'Down-payment receipt'], ['INSURANCE', 'Insurance policy'], ['PHOTO', 'Asset photo']]
const DOC_LABEL: Record<string, string> = Object.fromEntries([...PRE_DOCS, ['RC_HP', 'RC with hypothecation'], ['INSURANCE_HP', 'Insurance with hypothecation'],
  ['INSTALLATION_CERT', 'Installation certificate'], ['NET_METER', 'Net-meter approval'], ['DELIVERY_NOTE', 'Delivery note']])

async function upload(url: string, form: FormData) {
  const t = getToken()
  const r = await fetch(url, { method: 'POST', body: form, headers: t ? { Authorization: 'Bearer ' + t } : {} })
  const b = await r.json().catch(() => ({}))
  if (!r.ok) throw new Error(b.message ?? 'Upload failed')
  return b
}

async function openFile(url: string) {
  const t = getToken()
  const r = await fetch(url, { headers: t ? { Authorization: 'Bearer ' + t } : {} })
  if (r.ok) window.open(URL.createObjectURL(await r.blob()), '_blank')
}

type Form = Record<string, string>
const FIELDS: [keyof AssetRec, string, 'text' | 'num' | 'date'][] = [
  ['make', 'Make', 'text'], ['model', 'Model', 'text'], ['variant', 'Variant', 'text'], ['manufactureYear', 'Year of manufacture', 'num'], ['units', 'Units', 'num'],
  ['serialNo', 'Serial / chassis no', 'text'], ['engineNo', 'Engine / motor no', 'text'], ['registrationNo', 'Registration no', 'text'],
  ['quoteNo', 'Quotation no', 'text'], ['quoteDate', 'Quotation date', 'date'], ['quoteAmount', 'Quotation amount', 'num'],
  ['invoiceNo', 'Invoice no', 'text'], ['invoiceDate', 'Invoice date', 'date'], ['invoiceGstin', 'Invoice GSTIN', 'text'],
  ['basePrice', 'Base price', 'num'], ['gstAmount', 'GST', 'num'], ['otherCharges', 'Other charges', 'num'], ['invoiceTotal', 'Invoice total', 'num'],
  ['downPayment', 'Down payment', 'num'], ['downPaymentRef', 'Receipt no', 'text'], ['subsidyAmount', 'Subsidy', 'num'],
  ['insurer', 'Insurer', 'text'], ['insurancePolicyNo', 'Policy no', 'text'], ['insuredValue', 'Insured value (IDV)', 'num'], ['insuranceExpiry', 'Insurance valid till', 'date'],
]
const GROUPS: [string, (keyof AssetRec)[]][] = [
  ['Asset', ['make', 'model', 'variant', 'manufactureYear', 'units', 'serialNo', 'engineNo', 'registrationNo']],
  ['Quotation and invoice', ['quoteNo', 'quoteDate', 'quoteAmount', 'invoiceNo', 'invoiceDate', 'invoiceGstin', 'basePrice', 'gstAmount', 'otherCharges', 'invoiceTotal']],
  ['Down payment and insurance', ['downPayment', 'downPaymentRef', 'subsidyAmount', 'insurer', 'insurancePolicyNo', 'insuredValue', 'insuranceExpiry']],
]

function toForm(a?: AssetRec): Form {
  const f: Form = {}
  FIELDS.forEach(([k]) => { const v = a?.[k]; f[k] = v == null ? '' : String(v) })
  f.assetClass = a?.assetClass ?? ''
  f.dealerId = a?.dealerId ? String(a.dealerId) : ''
  f.insuranceHypothecated = a?.insuranceHypothecated ? 'true' : ''
  return f
}

/** The Asset section of Application 360. */
export function AssetTab({ id, rev, onChange }: { id: string; rev: number; onChange: () => void }) {
  const [r, setR] = useState(0)
  const v = useLoad(() => api.get<View>(`/api/applications/${id}/asset`), [id, rev, r])
  const [form, setForm] = useState<Form>(toForm())
  const [docType, setDocType] = useState('QUOTATION')
  const [file, setFile] = useState<File | null>(null)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState('')
  const [error, setError] = useState<unknown>()
  const [rule, setRule] = useState<{ check: string; note: string } | null>(null)
  useEffect(() => { if (v.data) setForm(toForm(v.data.asset)) }, [v.data])
  const d = v.data
  if (!d) return v.error ? <ErrorBox error={v.error} /> : <div className="empty">Loading…</div>
  const a = d.asset, e = d.evaluation
  const cls = d.classes.find(c => c.assetClass === (form.assetClass || a?.assetClass)) ?? d.classes[0]
  const done = (m: string) => { setMsg(m); setError(undefined); setR(x => x + 1); onChange() }
  const run = async (f: () => Promise<unknown>, m: string) => { setBusy(true); try { await f(); done(m) } catch (x) { setError(x) } finally { setBusy(false) } }
  const save = () => {
    const body: Record<string, unknown> = { assetClass: form.assetClass || cls?.assetClass, dealerId: form.dealerId ? Number(form.dealerId) : undefined, insuranceHypothecated: form.insuranceHypothecated === 'true' }
    FIELDS.forEach(([k, , t]) => { const s = (form[k] ?? '').trim(); if (s !== '') body[k] = t === 'num' ? Number(s.replace(/,/g, '')) : s })
    return run(() => api.put(`/api/applications/${id}/asset`, body), 'Asset saved and checked')
  }
  const send = () => {
    if (!file) return
    const f = new FormData(); f.append('file', file); f.append('docType', docType)
    run(() => upload(`/api/applications/${id}/asset/documents`, f).then(() => setFile(null)), (DOC_LABEL[docType] ?? label(docType)) + ' uploaded and read')
  }
  const docChoices: [string, string][] = d.disbursed ? d.postDocs.filter(p => p.status !== 'RECEIVED').map(p => [p.type, p.label]) : PRE_DOCS
  const fld = (k: keyof AssetRec) => {
    const [, l, t] = FIELDS.find(x => x[0] === k)!
    const lbl = k === 'serialNo' && cls?.serialLabel ? cls.serialLabel : l
    return <label key={k} className="fld">{lbl}
      <input name={k} type={t === 'date' ? 'date' : 'text'} inputMode={t === 'num' ? 'decimal' : undefined} value={form[k] ?? ''} disabled={!d.canEdit}
        onChange={ev => setForm({ ...form, [k]: ev.target.value })} />
    </label>
  }
  const blocking = e?.checks.filter(c => (c.result === 'REFER' && !c.ruling) || c.result === 'FAIL') ?? []

  return (
    <div data-asset>
      <ErrorBox error={error} />
      {msg && <div className="alert ok" role="status">{msg}</div>}
      <KpiStrip items={[
        ['Asset', a ? a.assetNo : 'not recorded', a ? `${cls?.label ?? a.assetClass}${a.make ? ' · ' + a.make + ' ' + (a.model ?? '') : ''}` : undefined],
        ['Asset cost', e?.assetCost ? inr(e.assetCost) : '—', e && e.netCost !== e.assetCost ? 'net ' + inr(e.netCost) : undefined],
        ['Max loan', e?.maxLoan ? inr(e.maxLoan) : '—', cls ? 'LTV cap ' + pct(cls.maxLtv, 0) : undefined],
        ['LTV', e?.ltv ? pct(e.ltv) : '—', e?.downPct ? 'down ' + pct(e.downPct) : undefined, !!e && !!cls && e.ltv > cls.maxLtv],
        ['Asset rules', <span data-asset-status={a?.status ?? 'NONE'}><St s={a?.status ?? 'PENDING'} /></span>, blocking.length ? blocking.length + ' to clear' : undefined],
        ['Lien', <St s={a?.lienState ?? 'NONE'} />, a?.disbursedTo ? 'paid to ' + a.disbursedTo : undefined],
      ]} />

      <div className="panel">
        <h3>Dealer and asset</h3>
        <div className="grid2">
          <label className="fld">Asset class
            <select name="assetClass" value={form.assetClass || cls?.assetClass || ''} disabled={!d.canEdit || d.classes.length < 2} onChange={ev => setForm({ ...form, assetClass: ev.target.value })}>
              {d.classes.map(c => <option key={c.assetClass} value={c.assetClass}>{c.label}</option>)}
            </select>
          </label>
          <label className="fld">Dealer or supplier
            <select name="dealerId" value={form.dealerId} disabled={!d.canEdit} onChange={ev => setForm({ ...form, dealerId: ev.target.value })}>
              <option value="">Choose…</option>
              {d.dealers.map(x => <option key={x.id} value={x.id}>{x.name} · {x.city ?? ''}{x.status !== 'ACTIVE' ? ' (' + x.status.toLowerCase() + ')' : !x.empanelled ? ' (not empanelled)' : ''}</option>)}
            </select>
          </label>
        </div>
        {d.dealer && <p className="small muted">{d.dealer.code} · GSTIN {d.dealer.gstin ?? '—'} · pays to account {d.dealer.account ?? '—'} ({d.dealer.ifsc ?? '—'}) · exposure {inr(d.dealer.exposure)}{d.dealer.maxExposure ? ' of ' + inr(d.dealer.maxExposure) : ''}</p>}
        {GROUPS.map(([g, keys]) => <div key={g}>
          <h4 style={{ margin: '14px 0 6px' }}>{g}</h4>
          <div className="grid3">{keys.map(fld)}</div>
        </div>)}
        {cls?.insuranceRequired && <label className="row small" style={{ marginTop: 8 }}>
          <input type="checkbox" name="insuranceHypothecated" checked={form.insuranceHypothecated === 'true'} disabled={!d.canEdit}
            onChange={ev => setForm({ ...form, insuranceHypothecated: ev.target.checked ? 'true' : '' })} /> Insurance shows the lender's hypothecation
        </label>}
        {d.canEdit && <div className="row" style={{ marginTop: 12 }}>
          <button className="btn primary" disabled={busy} onClick={save}>Save and check</button>
          {a && <button className="btn" disabled={busy} onClick={() => run(() => api.post(`/api/applications/${id}/asset/evaluate`), 'Checks re-run')}>Re-run checks</button>}
        </div>}
      </div>

      {a && <div className="panel">
        <h3>{d.disbursed ? 'Documents' : 'Dealer documents'}</h3>
        <p className="small muted">Document AI reads the quotation, invoice, receipt and insurance and fills blank fields; what you typed is never overwritten.</p>
        <Table head={['Document', 'File', 'Read', 'Uploaded']} empty="No documents yet" rows={d.documents.map(x => [
          DOC_LABEL[x.docType] ?? label(x.docType),
          <a href="#" onClick={ev => { ev.preventDefault(); openFile(`/api/asset-documents/${x.id}/content`) }}>{x.fileName}</a>,
          x.readConfidence != null ? pct(x.readConfidence, 0) + (x.engine ? ' · ' + x.engine : '') : '—',
          `${x.uploadedBy} · ${when(x.uploadedAt)}`,
        ])} />
        {docChoices.length > 0 && (d.canEdit || d.disbursed) && <div className="row" style={{ marginTop: 10 }}>
          <select aria-label="Document type" name="docType" value={docChoices.some(c => c[0] === docType) ? docType : docChoices[0][0]} onChange={ev => setDocType(ev.target.value)}>
            {docChoices.map(([k, l]) => <option key={k} value={k}>{l}</option>)}
          </select>
          <input type="file" aria-label="Asset document" accept=".pdf,.jpg,.jpeg,.png" onChange={ev => { setFile(ev.target.files?.[0] ?? null); if (!docChoices.some(c => c[0] === docType)) setDocType(docChoices[0][0]) }} />
          <button className="btn" disabled={!file || busy} onClick={send}>Upload</button>
        </div>}
        {a.downPayment != null && <div className="row" style={{ marginTop: 10 }}>
          {a.downPaymentVerifiedAt
            ? <span className="pill ok" data-dp-verified>Down payment {inr(a.downPayment)} verified by {a.downPaymentVerifiedBy}</span>
            : <><span className="pill warn">Down payment {inr(a.downPayment)} not verified</span>
              {d.canVerify && <button className="btn sm" disabled={busy} onClick={() => run(() => api.post(`/api/applications/${id}/asset/verify-down-payment`, { reference: a.downPaymentRef }), 'Down payment verified')}>Verify down payment</button>}</>}
        </div>}
      </div>}

      {e && <div className="panel">
        <h3>Asset checks</h3>
        <Table head={['Check', 'Result', 'Why', '']} rows={e.checks.map(c => [
          <span><b>{c.id}</b> {c.label}</span>,
          <span data-check={c.id} data-result={c.result}><St s={c.result} />{c.ruling && <span className="small muted"> · cleared</span>}</span>,
          <span className="small">{c.reason}{c.ruling && <><br /><em>Exception: {c.ruling}</em></>}</span>,
          c.result === 'REFER' && !c.ruling && d.canRule ? <button className="btn sm" onClick={() => setRule({ check: c.id, note: '' })}>Approve exception</button> : '',
        ])} />
        {rule && <div className="panel" style={{ marginTop: 8 }}>
          <label className="fld">Why {rule.check} can be accepted (at least 15 characters)
            <textarea name="ruling" value={rule.note} onChange={ev => setRule({ ...rule, note: ev.target.value })} />
          </label>
          <div className="row">
            <button className="btn primary" disabled={rule.note.trim().length < 15 || busy} onClick={() => run(() => api.post(`/api/applications/${id}/asset/exceptions`, rule).then(() => setRule(null)), 'Exception recorded')}>Record exception</button>
            <button className="btn" onClick={() => setRule(null)}>Cancel</button>
          </div>
        </div>}
        {e.beforeDisbursement.length > 0 && <><h4>Before disbursement</h4><ul className="small">{e.beforeDisbursement.map(x => <li key={x}>{x}</li>)}</ul></>}
        {e.conditions.length > 0 && <><h4>Conditions</h4><ul className="small">{e.conditions.map(x => <li key={x}>{x}</li>)}</ul></>}
      </div>}

      {e && e.cover.length > 0 && <div className="panel">
        <h3>Security cover over the loan</h3>
        <p className="small muted">Asset value written down at {cls ? pct(cls.depRate, 0) : '—'} a year against the loan outstanding.</p>
        <Table head={['Year', 'Asset value', 'Outstanding', 'Cover']} rows={e.cover.map(c => [
          String(c.year), inr(c.assetValue), inr(c.outstanding), c.cover == null ? 'repaid' : <span className={c.cover < 1 ? 'bad' : ''}>{c.cover.toFixed(2)}×</span>])} />
      </div>}

      {(d.fleet.loans.length > 0 || d.fleet.limit) && <div className="panel">
        <h3>Borrower's other asset loans</h3>
        <p className="small muted">Exposure elsewhere {inr(d.fleet.otherExposure)}{d.fleet.limit ? ' · fleet limit ' + inr(d.fleet.limit) : ''}</p>
        <Table head={['Application', 'Asset', 'Identifier', 'Amount', 'State']} empty="No other asset loans" rows={d.fleet.loans.map(l => [
          l.appNo, l.asset || l.assetNo, l.registrationNo ?? l.serialNo ?? '—', inr(l.amount), label(l.state)])} />
      </div>}

      {d.postDocs.length > 0 && <div className="panel" data-post-docs>
        <h3>Documents due after disbursement</h3>
        <Table head={['Document', 'Due by', 'Status']} rows={d.postDocs.map(p => [p.label, p.dueBy, <span data-post-doc={p.type}><St s={p.status} />{p.by && <span className="small muted"> · {p.by}</span>}</span>])} />
      </div>}
    </div>
  )
}

// ---------------------------------------------------------------- Dealers and suppliers (Admin)

const KINDS = ['DEALER', 'SUPPLIER', 'INSTALLER', 'OEM']
type DealerForm = { id?: number; code: string; name: string; kind: string; categories: string; oem: string; gstin: string; pan: string; city: string; pincode: string
  accountName: string; accountNo: string; ifsc: string; empanelled: boolean; status: string; maxExposure: string; notes: string }
const EMPTY: DealerForm = { code: '', name: '', kind: 'DEALER', categories: '', oem: '', gstin: '', pan: '', city: '', pincode: '', accountName: '', accountNo: '', ifsc: '', empanelled: true, status: 'ACTIVE', maxExposure: '', notes: '' }

export function DealersPage() {
  const [tab, setTab] = useTab(['dealers', 'classes'] as const, 'dealers')
  return (
    <>
      <Head title="Dealers & suppliers" sub="Empanelled dealers, suppliers and installers that asset loans pay out to, and the policy for each asset class." />
      <div className="tabs" role="tablist">
        {([['dealers', 'Dealers & suppliers'], ['classes', 'Asset classes']] as const).map(([k, l]) =>
          <button key={k} role="tab" aria-selected={tab === k} className={tab === k ? 'on' : ''} onClick={() => setTab(k)}>{l}</button>)}
      </div>
      {tab === 'dealers' ? <Dealers /> : <Classes />}
    </>
  )
}

function Dealers() {
  const [r, setR] = useState(0)
  const list = useLoad(() => api.get<DealerRow[]>('/api/dealers'), [r])
  const classes = useLoad(() => api.get<AssetClass[]>('/api/asset-policies'), [])
  const [f, setF] = useState<DealerForm | null>(null)
  const [error, setError] = useState<unknown>()
  const [msg, setMsg] = useState('')
  const save = async () => {
    if (!f) return
    const body = { ...f, maxExposure: f.maxExposure ? Number(f.maxExposure) : undefined, accountNo: f.accountNo || undefined }
    try {
      if (f.id) await api.put(`/api/dealers/${f.id}`, body); else await api.post('/api/dealers', body)
      setMsg(`${f.name} saved`); setF(null); setError(undefined); setR(x => x + 1)
    } catch (x) { setError(x) }
  }
  const txt = (k: keyof DealerForm, l: string) => <label className="fld">{l}<input name={k} value={String(f![k] ?? '')} onChange={e => setF({ ...f!, [k]: e.target.value })} /></label>
  const cats = (f?.categories ?? '').split(',').filter(Boolean)
  return (
    <>
      <ErrorBox error={error ?? list.error} />
      {msg && <div className="alert ok" role="status">{msg}</div>}
      <div className="row" style={{ marginBottom: 10 }}><button className="btn primary" onClick={() => setF({ ...EMPTY })}>Add dealer or supplier</button></div>
      {f && <div className="panel">
        <h3>{f.id ? 'Edit ' + f.name : 'New dealer or supplier'}</h3>
        <div className="grid3">
          {!f.id && txt('code', 'Code')}{txt('name', 'Name')}
          <label className="fld">Kind<select name="kind" value={f.kind} onChange={e => setF({ ...f, kind: e.target.value })}>{KINDS.map(k => <option key={k}>{k}</option>)}</select></label>
          {txt('oem', 'OEM / brand')}{txt('gstin', 'GSTIN')}{txt('pan', 'PAN')}{txt('city', 'City')}{txt('pincode', 'PIN code')}
          {txt('accountName', 'Account name')}{txt('accountNo', f.id ? 'Account no (blank keeps current)' : 'Account no')}{txt('ifsc', 'IFSC')}{txt('maxExposure', 'Exposure limit (₹)')}
          <label className="fld">Status<select name="status" value={f.status} onChange={e => setF({ ...f, status: e.target.value })}>{['ACTIVE', 'SUSPENDED'].map(k => <option key={k}>{k}</option>)}</select></label>
        </div>
        <div className="row small" style={{ marginTop: 8 }}>Asset classes:
          {(classes.data ?? []).map(c => <label key={c.assetClass} className="row"><input type="checkbox" checked={cats.includes(c.assetClass)}
            onChange={e => setF({ ...f, categories: (e.target.checked ? [...cats, c.assetClass] : cats.filter(x => x !== c.assetClass)).join(',') })} />{c.label}</label>)}
          <label className="row"><input type="checkbox" checked={f.empanelled} onChange={e => setF({ ...f, empanelled: e.target.checked })} />Empanelled</label>
        </div>
        {txt('notes', 'Notes')}
        <div className="row" style={{ marginTop: 10 }}><button className="btn primary" onClick={save}>Save</button><button className="btn" onClick={() => setF(null)}>Cancel</button></div>
      </div>}
      <Table head={['Code', 'Name', 'Kind', 'Asset classes', 'City', 'Pays to', 'Exposure', 'Status', '']} empty="No dealers yet" rows={(list.data ?? []).map(d => [
        d.code, d.name, label(d.kind), d.categories.split(',').join(', '), d.city ?? '—', d.account ? `${d.account} · ${d.ifsc}` : '—',
        <span>{inr(d.exposure)}{d.maxExposure ? <span className="small muted"> of {inr(d.maxExposure)}</span> : ''}</span>,
        <span><St s={d.status} />{!d.empanelled && <span className="pill bad">not empanelled</span>}</span>,
        <button className="btn sm" onClick={() => setF({ ...EMPTY, ...Object.fromEntries(Object.entries(d).map(([k, v]) => [k, v ?? ''])), accountNo: '', maxExposure: d.maxExposure ? String(d.maxExposure) : '', empanelled: d.empanelled } as DealerForm)}>Edit</button>,
      ])} />
    </>
  )
}

function Classes() {
  const [r, setR] = useState(0)
  const list = useLoad(() => api.get<AssetClass[]>('/api/asset-policies'), [r])
  const [f, setF] = useState<Record<string, string> | null>(null)
  const [error, setError] = useState<unknown>()
  const keys: [string, string][] = [['maxLtv', 'Max LTV (0–1)'], ['minDownPct', 'Min down payment (0–1)'], ['depRate', 'Depreciation a year (0–1)'], ['maxAgeYears', 'Max asset age (years)'],
    ['quoteVariance', 'Invoice vs quotation tolerance (0–1)'], ['fleetLimit', 'Fleet limit (₹)'], ['postDocs', 'Documents after disbursal'], ['postDays', 'Days to collect them']]
  const save = async () => {
    if (!f) return
    const body: Record<string, unknown> = { insuranceRequired: f.insuranceRequired === 'true' }
    keys.forEach(([k]) => { if (f[k] !== '') body[k] = k === 'postDocs' ? f[k] : Number(f[k]) })
    try { await api.put(`/api/asset-policies/${f.assetClass}`, body); setF(null); setError(undefined); setR(x => x + 1) } catch (x) { setError(x) }
  }
  return (
    <>
      <ErrorBox error={error ?? list.error} />
      <Table head={['Asset class', 'Max LTV', 'Min down', 'Depreciation', 'Max age', 'Insurance', 'After disbursal', '']} rows={(list.data ?? []).map(c => [
        c.label, pct(c.maxLtv, 0), pct(c.minDownPct, 0), pct(c.depRate, 0), c.maxAgeYears + ' yrs', c.insuranceRequired ? 'required' : 'no',
        c.postDocs ? c.postDocs.split(',').map(x => DOC_LABEL[x] ?? label(x)).join(', ') + ` · ${c.postDays} days` : '—',
        <button className="btn sm" onClick={() => setF(Object.fromEntries(Object.entries(c).map(([k, v]) => [k, v == null ? '' : String(v)])))}>Edit</button>])} />
      {f && <div className="panel" style={{ marginTop: 8 }}>
        <h3>{f.label}</h3>
        <div className="grid3">{keys.map(([k, l]) => <label key={k} className="fld">{l}<input name={k} value={f[k] ?? ''} onChange={e => setF({ ...f, [k]: e.target.value })} /></label>)}</div>
        <label className="row small"><input type="checkbox" checked={f.insuranceRequired === 'true'} onChange={e => setF({ ...f, insuranceRequired: String(e.target.checked) })} />Insurance with hypothecation required</label>
        <div className="row" style={{ marginTop: 10 }}><button className="btn primary" onClick={save}>Save</button><button className="btn" onClick={() => setF(null)}>Cancel</button></div>
      </div>}
    </>
  )
}

// ---------------------------------------------------------------- Asset finance book (Portfolio)

interface Book { assets: { applicationId: number; appNo: string; applicant: string; product: string; assetNo: string; assetClass: string; asset: string; dealer?: string; amount: number
  cost?: number; status: string; stage: string; lien: string; postDocsOpen: number; postDocsOverdue: number }[]
  byClass: { assetClass: string; label: string; loans: number; amount: number; disbursed: number }[]; postDocsOpen: number; postDocsOverdue: number; dealers: DealerRow[] }

export function AssetFinancePage() {
  const b = useLoad(() => api.get<Book>('/api/asset-finance'), [])
  const [cls, setCls] = useState('')
  const d = b.data
  const rows = (d?.assets ?? []).filter(x => !cls || x.assetClass === cls)
  return (
    <>
      <Head title="Asset finance" sub="Vehicles, equipment, solar, medical equipment and consumer durables: loans by asset class, dealer exposure, and documents still due after disbursement." />
      <ErrorBox error={b.error} />
      {d && <>
        <KpiStrip items={[
          ['Asset loans', String(d.assets.length)],
          ['Amount', inr(d.byClass.reduce((s, c) => s + c.amount, 0))],
          ['Disbursed', inr(d.byClass.reduce((s, c) => s + c.disbursed, 0))],
          ['Documents due', String(d.postDocsOpen)],
          ['Overdue', String(d.postDocsOverdue), undefined, d.postDocsOverdue > 0],
        ]} />
        <div className="seg" role="tablist" style={{ margin: '10px 0' }}>
          <button className={!cls ? 'on' : ''} onClick={() => setCls('')}>All</button>
          {d.byClass.map(c => <button key={c.assetClass} className={cls === c.assetClass ? 'on' : ''} onClick={() => setCls(c.assetClass)}>{c.label} · {c.loans}</button>)}
        </div>
        <Table head={['Application', 'Asset', 'Dealer', 'Amount', 'Asset rules', 'Stage', 'Lien', 'Due after disbursal']} empty="No asset loans yet" rows={rows.map(x => [
          <Link to={`/applications/${x.applicationId}?tab=Asset`}>{x.applicant}<br /><span className="small muted">{x.appNo} · {x.product}</span></Link>,
          <span>{x.asset || x.assetNo}<br /><span className="small muted">{x.assetNo}</span></span>, x.dealer ?? '—', inr(x.amount), <St s={x.status} />, label(x.stage), <St s={x.lien} />,
          x.postDocsOpen ? <span className={x.postDocsOverdue ? 'pill bad' : 'pill warn'}>{x.postDocsOpen} open{x.postDocsOverdue ? `, ${x.postDocsOverdue} overdue` : ''}</span> : '—'])} />
        <div className="panel" style={{ marginTop: 12 }}>
          <h3>Dealer exposure</h3>
          <Table head={['Dealer', 'Asset classes', 'Loans', 'Exposure', 'Limit', 'Status']} rows={d.dealers.map(x => [x.name, x.categories.split(',').join(', '), String(x.loans), inr(x.exposure),
            x.maxExposure ? inr(x.maxExposure) : '—', <St s={x.status} />])} />
        </div>
      </>}
    </>
  )
}
