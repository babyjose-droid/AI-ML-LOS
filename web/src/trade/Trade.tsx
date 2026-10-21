import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, getToken } from '../api'
import { ErrorBox, Head, KpiStrip, Table, useLoad, useTab } from '../components/ui'
import { inr, label, pct, when } from '../format'

// Trade finance: the programme and buyer sublimits of an application, the revolving limit, and the invoices drawn against it;
// the trade book; anchors and buyers.

export interface Party { id: number; code: string; name: string; kind: string; programme?: string; gstin: string; city?: string; industry?: string; rating?: string
  programmeLimit?: number; maxTenorDays: number; advanceRate: number; graceDays: number; recourse: string; fldgPct?: number; confirmationRequired: boolean
  concentrationCap: number; repaidBy: string; accountName?: string; account?: string; ifsc?: string; empanelled: boolean; status: string; notes?: string
  outstanding: number; limits: number }
interface Check { id: string; label: string; result: string; reason: string; ruling?: string }
interface Limit { id: number; limitNo: string; status: string; structureOk: boolean; borrowerGstin?: string; limit: number; sanctioned?: number; utilised: number
  available: number; validFrom?: string; validTo?: string; ratePa?: number; frozenReason?: string; drawdowns: number; overdue: number; interestDue: number }
interface Inv { id: number; refNo: string; invoiceNo?: string; invoiceDate?: string; dueDate?: string; poNo?: string; sellerGstin?: string; buyerGstin?: string
  counterpartyId?: number; counterparty?: string; baseAmount?: number; gstAmount?: number; total?: number; advance?: number; fileName: string; readConfidence?: number
  engine?: string; status: string; checksStatus?: string; checks: Check[]; confirmationRef?: string; confirmedBy?: string; funded?: number; interest?: number
  fundedBy?: string; fundedAt?: string; utr?: string; paidTo?: string; repaid: number; due: number; overdueDays: number; rejectedReason?: string
  submittedBy: string; submittedAt: string }
interface View { kind: 'RECEIVABLES' | 'VENDOR' | 'DEALER'; product: string; requested: number; suggestedGstin?: string; anchors: Party[]; buyers: Party[]; limit?: Limit
  anchor?: Party; sublimits: { counterpartyId: number; name: string; rating?: string; amount: number; used: number }[]; structure: Check[]; invoices: Inv[]
  canEditStructure: boolean; canSubmit: boolean; canOperate: boolean; canRule: boolean; me: string }

const ST: Record<string, string> = { PASS: 'ok', REFER: 'warn', FAIL: 'bad', PENDING: 'muted', ACTIVE: 'ok', PROPOSED: 'muted', FROZEN: 'bad', SUBMITTED: 'muted',
  CONFIRMED: 'warn', FUNDED: 'ok', PART_PAID: 'warn', REPAID: 'ok', REJECTED: 'bad', SUSPENDED: 'bad', EXPIRED: 'bad' }
const St = ({ s }: { s?: string }) => <span className={'pill ' + (ST[s ?? ''] ?? 'muted')}>{(s ?? '—').replace(/_/g, ' ').toLowerCase()}</span>
export const KIND_LABEL: Record<string, string> = { RECEIVABLES: 'Invoice & receivables', VENDOR: 'Vendor (supply chain)', DEALER: 'Dealer & distributor' }
const WHO_PAYS: Record<string, string> = { RECEIVABLES: 'the buyer', VENDOR: 'the anchor', DEALER: 'the dealer (borrower)' }

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

/** The Limit section of Application 360: structure before sanction, drawdowns once active. */
export function LimitTab({ id, rev, onChange }: { id: string; rev: number; onChange: () => void }) {
  const [r, setR] = useState(0)
  const v = useLoad(() => api.get<View>(`/api/applications/${id}/trade`), [id, rev, r])
  const [gstin, setGstin] = useState('')
  const [anchorId, setAnchorId] = useState('')
  const [subs, setSubs] = useState<Record<number, string>>({})
  const [file, setFile] = useState<File | null>(null)
  const [buyer, setBuyer] = useState('')
  const [open, setOpen] = useState<number | null>(null)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState('')
  const [error, setError] = useState<unknown>()
  useEffect(() => {
    const d = v.data
    if (!d) return
    setGstin(d.limit?.borrowerGstin ?? d.suggestedGstin ?? '')
    setAnchorId(d.anchor ? String(d.anchor.id) : '')
    setSubs(Object.fromEntries(d.sublimits.map(s => [s.counterpartyId, String(s.amount)])))
  }, [v.data])
  const d = v.data
  if (!d) return v.error ? <ErrorBox error={v.error} /> : <div className="empty">Loading…</div>
  const l = d.limit
  const programme = d.kind !== 'RECEIVABLES'
  const run = async (f: () => Promise<unknown>, m: string) => {
    setBusy(true)
    try { await f(); setMsg(m); setError(undefined); setR(x => x + 1); onChange() } catch (x) { setError(x) } finally { setBusy(false) }
  }
  const saveStructure = () => run(() => api.put(`/api/applications/${id}/trade`, {
    borrowerGstin: gstin, anchorId: anchorId ? Number(anchorId) : undefined,
    sublimits: programme ? undefined : Object.entries(subs).filter(([, a]) => a && Number(a) > 0).map(([k, a]) => ({ counterpartyId: Number(k), amount: Number(a) })),
  }), 'Limit structure saved and checked')
  const send = () => {
    if (!file) return
    const f = new FormData(); f.append('file', file)
    if (buyer) f.append('counterpartyId', buyer)
    run(() => upload(`/api/applications/${id}/trade/invoices`, f).then(() => setFile(null)), 'Invoice submitted and read')
  }
  const selected = d.invoices.find(x => x.id === open)
  const anchor = d.anchors.find(a => String(a.id) === anchorId)

  return (
    <div data-trade>
      <ErrorBox error={error} />
      {msg && <div className="alert ok" role="status">{msg}</div>}
      <KpiStrip items={[
        ['Limit', l ? l.limitNo : 'not set', KIND_LABEL[d.kind]],
        [l?.sanctioned ? 'Sanctioned' : 'Requested', inr(l?.sanctioned ?? d.requested), l?.ratePa ? l.ratePa + '% a year' : undefined],
        ['Utilised', inr(l?.utilised ?? 0), l && l.limit ? pct((l.utilised ?? 0) / l.limit) : undefined],
        ['Available', <span data-available={l?.available ?? 0}>{inr(l?.available ?? 0)}</span>],
        ['Status', <span data-limit-status={l?.status ?? 'NONE'}><St s={l?.status ?? 'PENDING'} /></span>, l?.validTo ? 'to ' + l.validTo : undefined],
        ['Overdue', String(l?.overdue ?? 0), l?.interestDue ? 'interest due ' + inr(l.interestDue) : undefined, (l?.overdue ?? 0) > 0],
      ]} />
      {l?.status === 'FROZEN' && <div className="alert bad">Limit frozen: {l.frozenReason}</div>}

      {(!l || l.status === 'PROPOSED') && <div className="panel">
        <h3>Programme and limit</h3>
        <p className="small muted">
          {d.kind === 'RECEIVABLES' && 'Invoices raised by the borrower on approved buyers are financed; each buyer gets a sublimit and the buyer pays on the due date.'}
          {d.kind === 'VENDOR' && 'The borrower supplies an anchor; invoices accepted by the anchor are financed and the anchor pays the lender on the due date.'}
          {d.kind === 'DEALER' && 'The borrower buys from an anchor; the lender pays the anchor for each invoice and the dealer repays within the credit period.'}
        </p>
        <div className="grid2">
          <label className="fld">Borrower GSTIN
            <input name="borrowerGstin" value={gstin} disabled={!d.canEditStructure} onChange={e => setGstin(e.target.value.toUpperCase())} />
          </label>
          {programme && <label className="fld">Anchor
            <select name="anchorId" value={anchorId} disabled={!d.canEditStructure} onChange={e => setAnchorId(e.target.value)}>
              <option value="">Choose…</option>
              {d.anchors.map(a => <option key={a.id} value={a.id}>{a.name} · {a.rating ?? 'unrated'}{a.status !== 'ACTIVE' ? ' (' + a.status.toLowerCase() + ')' : ''}</option>)}
            </select>
          </label>}
        </div>
        {anchor && <p className="small muted">{label(anchor.programme ?? '')} programme · {pct(anchor.advanceRate, 0)} advance · up to {anchor.maxTenorDays} days · repaid by {anchor.repaidBy.toLowerCase()}
          {anchor.fldgPct ? ` · ${pct(anchor.fldgPct, 0)} first-loss guarantee` : ''}{anchor.programmeLimit ? ` · programme ${inr(anchor.limits)} of ${inr(anchor.programmeLimit)} used` : ''}</p>}
        {!programme && <Table head={['Buyer', 'Rating', 'Advance', 'Tenor', 'Confirmation', 'Sublimit (₹)']} rows={d.buyers.map(b => [
          <span>{b.name}{b.status !== 'ACTIVE' ? <span className="pill bad">{b.status.toLowerCase()}</span> : !b.empanelled ? <span className="pill warn">not empanelled</span> : ''}</span>,
          b.rating ?? '—', pct(b.advanceRate, 0), b.maxTenorDays + ' days', b.confirmationRequired ? 'needed' : 'not needed',
          <input aria-label={'Sublimit ' + b.name} name={'sub-' + b.code} inputMode="decimal" style={{ width: 120 }} value={subs[b.id] ?? ''} disabled={!d.canEditStructure}
            onChange={e => setSubs({ ...subs, [b.id]: e.target.value.replace(/[^0-9.]/g, '') })} />])} />}
        {d.canEditStructure && <div className="row" style={{ marginTop: 10 }}><button className="btn primary" disabled={busy} onClick={saveStructure}>Save and check</button></div>}
        {d.structure.length > 0 && <Table head={['Check', 'Result', 'Why']} rows={d.structure.map(c => [
          <span><b>{c.id}</b> {c.label}</span>, <span data-structure={c.id} data-result={c.result}><St s={c.result} /></span>, <span className="small">{c.reason}</span>])} />}
      </div>}

      {l && l.status !== 'PROPOSED' && <>
        {d.sublimits.length > 0 && <div className="panel">
          <h3>Buyer sublimits</h3>
          <Table head={['Buyer', 'Rating', 'Sublimit', 'In use', 'Free']} rows={d.sublimits.map(s => [s.name, s.rating ?? '—', inr(s.amount), inr(s.used), inr(Math.max(0, s.amount - s.used))])} />
        </div>}
        {d.anchor && <div className="panel"><h3>Programme</h3><p className="small" style={{ margin: 0 }}>{d.anchor.name} · {label(d.anchor.programme ?? '')} programme · {pct(d.anchor.advanceRate, 0)} advance · up to {d.anchor.maxTenorDays} days · repaid by {WHO_PAYS[d.kind]}{d.anchor.fldgPct ? ` · ${pct(d.anchor.fldgPct, 0)} first-loss guarantee` : ''}</p></div>}

        {d.canSubmit && <div className="panel">
          <h3>Draw against an invoice</h3>
          <p className="small muted">Upload the invoice. Invoice AI reads the number, dates, both GSTINs, PO and amounts, then the drawdown checks run.</p>
          <div className="row">
            {!programme && <select aria-label="Buyer" name="buyer" value={buyer} onChange={e => setBuyer(e.target.value)}>
              <option value="">Buyer from the invoice GSTIN</option>
              {d.sublimits.map(s => <option key={s.counterpartyId} value={s.counterpartyId}>{s.name}</option>)}
            </select>}
            <input type="file" aria-label="Invoice file" accept=".pdf,.jpg,.jpeg,.png" onChange={e => setFile(e.target.files?.[0] ?? null)} />
            <button className="btn primary" disabled={!file || busy} onClick={send}>Submit invoice</button>
          </div>
        </div>}

        <div className="panel">
          <h3>Drawdowns</h3>
          <Table head={['Drawdown', 'Invoice', 'Counterparty', 'Due', 'Invoice total', 'Advance', 'Checks', 'Status']} empty="No invoices yet" onRow={i => setOpen(d.invoices[i].id === open ? null : d.invoices[i].id)}
            rows={d.invoices.map(x => [
              <span data-invoice={x.refNo}>{x.refNo}</span>, <span>{x.invoiceNo ?? '—'}<br /><span className="small muted">{x.invoiceDate ?? ''}</span></span>,
              x.counterparty ?? '—', <span>{x.dueDate ?? '—'}{x.overdueDays > 0 && <><br /><span className="pill bad">{x.overdueDays} days overdue</span></>}</span>,
              inr(x.total ?? 0), inr(x.funded ?? x.advance ?? 0),
              <span data-checks={x.refNo} data-result={x.checksStatus}><St s={x.checksStatus} /></span>,
              <span data-invoice-status={x.refNo} data-status={x.status}><St s={x.status} /></span>])} />
          {selected && <InvoicePanel key={selected.id + ':' + r} x={selected} d={d} busy={busy} run={run} />}
        </div>

        {d.canRule && (l.status === 'ACTIVE' || l.status === 'FROZEN') && <FreezeBox id={id} frozen={l.status === 'FROZEN'} run={run} />}
      </>}
    </div>
  )
}

function InvoicePanel({ x, d, busy, run }: { x: Inv; d: View; busy: boolean; run: (f: () => Promise<unknown>, m: string) => Promise<void> }) {
  const [ref, setRef] = useState('')
  const [amount, setAmount] = useState(x.due.toFixed(2))
  const [note, setNote] = useState('')
  const [rule, setRule] = useState<string | null>(null)
  const live = ['FUNDED', 'PART_PAID'].includes(x.status)
  const openForWork = ['SUBMITTED', 'CONFIRMED'].includes(x.status)
  const needsConfirmation = x.checks.some(c => c.id === 'TF-10' && c.result === 'PENDING')
  return (
    <div className="panel" data-invoice-panel={x.refNo} style={{ marginTop: 10 }}>
      <h3>{x.refNo} · invoice {x.invoiceNo ?? '—'} <a href="#" className="small" onClick={e => { e.preventDefault(); openFile(`/api/trade-invoices/${x.id}/content`) }}>{x.fileName}</a></h3>
      <p className="small muted">Read by {x.engine ?? '—'}{x.readConfidence != null ? ' · ' + pct(x.readConfidence, 0) : ''} · seller {x.sellerGstin ?? '—'} · buyer {x.buyerGstin ?? '—'}
        {x.poNo ? ' · PO ' + x.poNo : ''} · taxable {inr(x.baseAmount ?? 0)} + GST {inr(x.gstAmount ?? 0)} · submitted by {x.submittedBy} {when(x.submittedAt)}</p>
      <Table head={['Check', 'Result', 'Why', '']} rows={x.checks.map(c => [
        <span><b>{c.id}</b> {c.label}</span>, <span data-check={c.id} data-result={c.result}><St s={c.result} />{c.ruling && <span className="small muted"> · cleared</span>}</span>,
        <span className="small">{c.reason}{c.ruling && <><br /><em>{c.ruling}</em></>}</span>,
        d.canRule && openForWork && c.result === 'REFER' && !c.ruling && !['TF-01', 'TF-03', 'TF-04', 'TF-08'].includes(c.id)
          ? <button className="btn sm" onClick={() => setRule(c.id)}>Approve exception</button> : ''])} />
      {rule && <div className="row" style={{ marginTop: 8 }}>
        <input aria-label="Exception reason" style={{ flex: 1 }} placeholder={`Why ${rule} can be accepted (15+ characters)`} value={note} onChange={e => setNote(e.target.value)} />
        <button className="btn primary" disabled={note.trim().length < 15 || busy} onClick={() => run(() => api.post(`/api/trade-invoices/${x.id}/exceptions`, { check: rule, note }), 'Exception recorded')}>Record</button>
      </div>}
      {x.status === 'FUNDED' || x.status === 'PART_PAID' || x.status === 'REPAID'
        ? <p className="small" data-funded>Funded {inr(x.funded ?? 0)} to {x.paidTo} · UTR {x.utr} · by {x.fundedBy} {when(x.fundedAt)} · interest {inr(x.interest ?? 0)} to {x.dueDate} · received {inr(x.repaid)} · due {inr(x.due)}</p> : null}
      {x.rejectedReason && <div className="alert bad">Rejected: {x.rejectedReason}</div>}
      {d.canOperate && <div className="row" style={{ marginTop: 10, flexWrap: 'wrap' }}>
        {openForWork && x.status === 'SUBMITTED' && needsConfirmation && <>
          <input aria-label="Confirmation reference" placeholder={`${x.counterparty ?? 'Counterparty'}'s acceptance ID or email`} value={ref} onChange={e => setRef(e.target.value)} />
          <button className="btn" disabled={ref.trim().length < 4 || busy} onClick={() => run(() => api.post(`/api/trade-invoices/${x.id}/confirm`, { reference: ref }), 'Confirmation recorded')}>Record confirmation</button>
        </>}
        {openForWork && x.checksStatus === 'PASS' && (x.submittedBy === d.me
          ? <span className="small muted">Another person funds it (maker-checker)</span>
          : <button className="btn primary" disabled={busy} onClick={() => run(() => api.post(`/api/trade-invoices/${x.id}/fund`), 'Drawdown funded')}>Fund {inr(x.advance ?? 0)}</button>)}
        {live && <>
          <input aria-label="Amount received" inputMode="decimal" style={{ width: 130 }} value={amount} onChange={e => setAmount(e.target.value.replace(/[^0-9.]/g, ''))} />
          <input aria-label="Repayment reference" placeholder="UTR / cheque no." value={ref} onChange={e => setRef(e.target.value)} />
          <button className="btn primary" disabled={!Number(amount) || busy} onClick={() => run(() => api.post(`/api/trade-invoices/${x.id}/repay`, { amount: Number(amount), reference: ref }), 'Repayment recorded')}>Record repayment</button>
        </>}
        {openForWork && <>
          <input aria-label="Reject reason" placeholder="Reason to reject (10+ characters)" value={note} onChange={e => setNote(e.target.value)} />
          <button className="btn danger" disabled={note.trim().length < 10 || busy} onClick={() => run(() => api.post(`/api/trade-invoices/${x.id}/reject`, { note }), 'Invoice rejected')}>Reject</button>
        </>}
      </div>}
    </div>
  )
}

function FreezeBox({ id, frozen, run }: { id: string; frozen: boolean; run: (f: () => Promise<unknown>, m: string) => Promise<void> }) {
  const [note, setNote] = useState('')
  return (
    <div className="panel">
      <h3>{frozen ? 'Unfreeze the limit' : 'Freeze the limit'}</h3>
      <div className="row">
        <input aria-label="Freeze reason" style={{ flex: 1 }} placeholder="Reason (10+ characters)" value={note} onChange={e => setNote(e.target.value)} />
        <button className={'btn ' + (frozen ? 'primary' : 'danger')} disabled={note.trim().length < 10}
          onClick={() => run(() => api.post(`/api/applications/${id}/trade/${frozen ? 'unfreeze' : 'freeze'}`, { note }), frozen ? 'Limit unfrozen' : 'Limit frozen')}>{frozen ? 'Unfreeze' : 'Freeze'}</button>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------- Trade finance book, queue, anchors and buyers

interface Book { limits: (Limit & { applicationId: number; appNo: string; borrower: string; product: string; kind: string; anchor?: string; stage: string })[]
  invoices: (Inv & { applicationId: number; borrower: string; product: string })[]; totalLimits: number; utilised: number; overdueAmount: number; counterparties: Party[] }

export function TradeFinancePage() {
  const [tab, setTab] = useTab(['limits', 'invoices', 'parties'] as const, 'limits')
  const [r, setR] = useState(0)
  const b = useLoad(() => api.get<Book>('/api/trade-finance'), [r])
  const d = b.data
  return (
    <>
      <Head title="Trade finance" sub="Invoice & receivables, vendor (supply chain) and dealer finance: revolving limits, drawdowns against invoices, anchors and buyers." />
      <ErrorBox error={b.error} />
      {d && <KpiStrip items={[
        ['Active limits', inr(d.totalLimits)], ['Utilised', inr(d.utilised), d.totalLimits ? pct(d.utilised / d.totalLimits) : undefined],
        ['Invoices in progress', String(d.invoices.filter(x => !['FUNDED', 'PART_PAID'].includes(x.status)).length)],
        ['Funded and open', String(d.invoices.filter(x => ['FUNDED', 'PART_PAID'].includes(x.status)).length)],
        ['Overdue', inr(d.overdueAmount), undefined, d.overdueAmount > 0],
      ]} />}
      <div className="tabs" role="tablist">
        {([['limits', 'Limits'], ['invoices', 'Invoice queue'], ['parties', 'Anchors & buyers']] as const).map(([k, l]) =>
          <button key={k} role="tab" aria-selected={tab === k} className={tab === k ? 'on' : ''} onClick={() => setTab(k)}>{l}</button>)}
      </div>
      {d && tab === 'limits' && <Table head={['Borrower', 'Programme', 'Limit', 'Utilised', 'Available', 'Valid to', 'Status']} empty="No trade limits yet" rows={d.limits.map(l => [
        <Link to={`/applications/${l.applicationId}?tab=Limit`}>{l.borrower}<br /><span className="small muted">{l.appNo} · {l.limitNo ?? '—'}</span></Link>,
        <span>{KIND_LABEL[l.kind] ?? l.kind}{l.anchor && <><br /><span className="small muted">{l.anchor}</span></>}</span>,
        inr(l.limit), inr(l.utilised), inr(l.available), l.validTo ?? '—', <span><St s={l.status} />{l.overdue > 0 && <span className="pill bad">{l.overdue} overdue</span>}</span>])} />}
      {d && tab === 'invoices' && <Table head={['Drawdown', 'Borrower', 'Counterparty', 'Due', 'Amount', 'Checks', 'Status']} empty="Nothing in the queue" rows={d.invoices.map(x => [
        <Link to={`/applications/${x.applicationId}?tab=Limit`}>{x.refNo}<br /><span className="small muted">{x.invoiceNo ?? ''}</span></Link>, x.borrower, x.counterparty ?? '—',
        <span>{x.dueDate ?? '—'}{x.overdueDays > 0 && <span className="pill bad">{x.overdueDays}d</span>}</span>, inr(x.funded ?? x.advance ?? 0), <St s={x.checksStatus} />, <St s={x.status} />])} />}
      {d && tab === 'parties' && <Parties list={d.counterparties} onSaved={() => setR(x => x + 1)} />}
    </>
  )
}

type PF = Record<string, string>
const EMPTY: PF = { code: '', name: '', kind: 'BUYER', programme: '', gstin: '', city: '', industry: '', rating: '', programmeLimit: '', maxTenorDays: '60', advanceRate: '0.80',
  graceDays: '7', confirmationRequired: 'true', concentrationCap: '0.50', repaidBy: 'BUYER', accountName: '', accountNo: '', ifsc: '', empanelled: 'true', status: 'ACTIVE', notes: '' }

function Parties({ list, onSaved }: { list: Party[]; onSaved: () => void }) {
  const [f, setF] = useState<(PF & { id?: string }) | null>(null)
  const [error, setError] = useState<unknown>()
  const save = async () => {
    if (!f) return
    const num = (k: string) => f[k] === '' ? undefined : Number(f[k])
    const body = { ...f, programmeLimit: num('programmeLimit'), maxTenorDays: num('maxTenorDays'), advanceRate: num('advanceRate'), graceDays: num('graceDays'),
      concentrationCap: num('concentrationCap'), confirmationRequired: f.confirmationRequired === 'true', empanelled: f.empanelled === 'true', accountNo: f.accountNo || undefined }
    try { if (f.id) await api.put(`/api/trade-counterparties/${f.id}`, body); else await api.post('/api/trade-counterparties', body); setF(null); setError(undefined); onSaved() } catch (x) { setError(x) }
  }
  const txt = (k: string, l: string) => <label className="fld">{l}<input name={k} value={f![k] ?? ''} onChange={e => setF({ ...f!, [k]: e.target.value })} /></label>
  const sel = (k: string, l: string, opts: string[]) => <label className="fld">{l}<select name={k} value={f![k]} onChange={e => setF({ ...f!, [k]: e.target.value })}>{opts.map(o => <option key={o} value={o}>{o || '—'}</option>)}</select></label>
  return (
    <>
      <ErrorBox error={error} />
      <div className="row" style={{ margin: '10px 0' }}><button className="btn primary" onClick={() => setF({ ...EMPTY })}>Add anchor or buyer</button></div>
      {f && <div className="panel">
        <h3>{f.id ? 'Edit ' + f.name : 'New anchor or buyer'}</h3>
        <div className="grid3">
          {!f.id && txt('code', 'Code')}{txt('name', 'Name')}{sel('kind', 'Kind', ['BUYER', 'ANCHOR'])}{f.kind === 'ANCHOR' && sel('programme', 'Programme', ['', 'VENDOR', 'DEALER'])}
          {txt('gstin', 'GSTIN')}{txt('city', 'City')}{txt('industry', 'Industry')}{txt('rating', 'Rating')}{txt('programmeLimit', f.kind === 'ANCHOR' ? 'Programme limit (₹)' : 'Buyer exposure cap (₹)')}
          {txt('advanceRate', 'Advance rate (0–1)')}{txt('maxTenorDays', 'Max tenor (days)')}{txt('graceDays', 'Grace (days)')}{txt('concentrationCap', 'Concentration cap (0–1)')}
          {sel('repaidBy', 'Repaid by', ['BUYER', 'ANCHOR', 'BORROWER'])}{sel('confirmationRequired', 'Confirmation needed', ['true', 'false'])}
          {txt('accountName', 'Account name')}{txt('accountNo', f.id ? 'Account no (blank keeps current)' : 'Account no')}{txt('ifsc', 'IFSC')}
          {sel('empanelled', 'Empanelled', ['true', 'false'])}{sel('status', 'Status', ['ACTIVE', 'SUSPENDED'])}
        </div>
        {txt('notes', 'Notes')}
        <div className="row" style={{ marginTop: 10 }}><button className="btn primary" onClick={save}>Save</button><button className="btn" onClick={() => setF(null)}>Cancel</button></div>
      </div>}
      <Table head={['Name', 'Kind', 'GSTIN', 'Rating', 'Terms', 'Limits set', 'Outstanding', 'Status', '']} rows={list.map(p => [
        <span>{p.name}<br /><span className="small muted">{p.code} · {p.city ?? ''}</span></span>, p.kind === 'ANCHOR' ? `Anchor · ${label(p.programme ?? '')}` : 'Buyer', p.gstin, p.rating ?? '—',
        <span className="small">{pct(p.advanceRate, 0)} · {p.maxTenorDays}d · {p.confirmationRequired ? 'confirms' : 'no confirmation'}{p.fldgPct ? ` · FLDG ${pct(p.fldgPct, 0)}` : ''}</span>,
        <span>{inr(p.limits)}{p.programmeLimit ? <span className="small muted"> of {inr(p.programmeLimit)}</span> : ''}</span>, inr(p.outstanding),
        <span><St s={p.status} />{!p.empanelled && <span className="pill warn">not empanelled</span>}</span>,
        <button className="btn sm" onClick={() => setF({ ...EMPTY, ...Object.fromEntries(Object.entries(p).map(([k, v]) => [k, v == null ? '' : String(v)])), accountNo: '' })}>Edit</button>])} />
    </>
  )
}
