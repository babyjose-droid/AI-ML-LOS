import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'
import { useAuth } from '../auth'
import { ErrorBox, Table, useLoad } from '../components/ui'
import { inr, label, pct, when } from '../format'

// Collateral on a live loan: what is held, release after closure or a part-payment, and substitution (maker, checker, handover).

interface Held {
  id: number; colNo: string; type: string; description?: string; address?: string; status: string; lienState: string; purpose: string; substituteFor?: number
  releasedAt?: string; value: number; maxLtv: number; canRelease?: boolean; releaseNote?: string
}
interface Change {
  id: number; kind: string; collateralId: number; newCollateralId?: number; reason: string; outstandingAt: number; ltvBefore?: number; ltvAfter?: number; checkNote?: string
  status: string; dueOn?: string; requestedBy: string; requestedAt: string; decidedBy?: string; decisionNote?: string; completedBy?: string; completedAt?: string
  handedTo?: string; daysLate?: number; compensation?: number; letter?: string
}
interface View {
  loanId: number; loanRef: string; applicationId: number; status: string; closed: boolean; outstanding: number; ltv?: number; lendable: number
  closedOn?: string; releaseDue?: string; releaseOverdueDays?: number; compensationIfLate?: number; collaterals: Held[]; changes: Change[]; rule: string
}
const LIEN: Record<string, string> = { ACTIVE: 'held', RELEASED: 'released', SUBSTITUTED: 'swapped out', NONE: 'not yet held' }

export function LoanCollateral({ loanId }: { loanId: number }) {
  const { user } = useAuth()
  const role = user?.role ?? ''
  const [r, setR] = useState(0)
  const v = useLoad(() => api.get<View>(`/api/book/loans/${loanId}/collateral`), [loanId, r])
  const [form, setForm] = useState<{ kind: 'release' | 'swap' | 'offer'; c: Held } | null>(null)
  const [reason, setReason] = useState('')
  const [subId, setSubId] = useState('')
  const [offer, setOffer] = useState({ type: 'RESIDENTIAL', description: '', address: '', pincode: '', areaSqft: '', ownerName: '' })
  const [act, setAct] = useState<{ id: number; kind: 'decide' | 'complete' } | null>(null)
  const [note, setNote] = useState('')
  const [error, setError] = useState<unknown>()
  const maker = ['OPERATIONS', 'CREDIT_OFFICER', 'ADMIN'].includes(role)
  const checker = ['CREDIT_MANAGER', 'CRO', 'ADMIN'].includes(role)
  const handover = ['OPERATIONS', 'ADMIN'].includes(role)
  const done = () => { setForm(null); setAct(null); setReason(''); setNote(''); setSubId(''); setR(x => x + 1) }
  async function run(fn: () => Promise<unknown>) { setError(undefined); try { await fn(); done() } catch (e) { setError(e) } }
  if (!v.data) return <ErrorBox error={v.error} />
  const d = v.data
  if (d.collaterals.length === 0) return null
  const name = (id?: number) => d.collaterals.find(c => c.id === id)?.colNo ?? '—'
  const offered = (c: Held) => d.collaterals.filter(x => x.purpose === 'SUBSTITUTE' && x.substituteFor === c.id && x.lienState === 'NONE')
  return (
    <div data-testid="loan-collateral">
      <h4>Collateral</h4>
      <p className="small">
        {d.closed ? <>Loan closed{d.closedOn ? ` on ${d.closedOn}` : ''}: documents are due back by <b>{d.releaseDue}</b>
          {(d.releaseOverdueDays ?? 0) > 0 && <span className="warn-text"> · {d.releaseOverdueDays} days late, {inr(d.compensationIfLate ?? 0)} owed to the borrower so far</span>}</>
          : <>Outstanding {inr(d.outstanding)}{d.ltv != null && <> · LTV {pct(d.ltv, 1)}</>} · lendable against what is held {inr(d.lendable)}</>}
      </p>
      <Table head={['Collateral', 'Value', 'Max LTV', 'Lien', '']} rows={d.collaterals.map(c => [
        <span><Link to={`/collaterals/${c.id}`} className="mono">{c.colNo}</Link> {label(c.type).toLowerCase()}{c.description ? ` · ${c.description}` : ''}
          {c.purpose === 'SUBSTITUTE' && <div className="small muted">Offered in place of {name(c.substituteFor)} · checks {label(c.status).toLowerCase()}</div>}</span>,
        c.value ? inr(c.value) : '—', pct(c.maxLtv, 0),
        <span className="small"><span className={'pill ' + (c.lienState === 'ACTIVE' ? 'ok' : 'muted')}>{LIEN[c.lienState] ?? c.lienState}</span>
          {c.lienState === 'ACTIVE' && c.releaseNote && <div className={c.canRelease ? 'muted' : 'warn-text'}>{c.releaseNote}</div>}</span>,
        c.lienState === 'ACTIVE' && maker ? <span className="row">
          {c.canRelease && <button className="btn sm" data-action={`release-${c.colNo}`} onClick={() => setForm({ kind: 'release', c })}>Release</button>}
          {!d.closed && <button className="btn sm" onClick={() => setForm({ kind: 'offer', c })}>Offer a substitute</button>}
          {!d.closed && offered(c).length > 0 && <button className="btn sm" onClick={() => { setSubId(String(offered(c)[0].id)); setForm({ kind: 'swap', c }) }}>Swap</button>}
        </span> : ''])} />

      {form && <div className="panel" style={{ marginTop: 8 }}>
        {form.kind === 'offer' ? <>
          <h4 style={{ marginTop: 0 }}>Offer a substitute for {form.c.colNo}</h4>
          <div className="grid2">
            <label>Type<select value={offer.type} onChange={e => setOffer({ ...offer, type: e.target.value })}>{['RESIDENTIAL', 'SHOP', 'PLOT', 'VEHICLE', 'GOLD'].map(t => <option key={t} value={t}>{label(t)}</option>)}</select></label>
            <label>Owner<input value={offer.ownerName} onChange={e => setOffer({ ...offer, ownerName: e.target.value })} /></label>
            <label>Description<input value={offer.description} onChange={e => setOffer({ ...offer, description: e.target.value })} /></label>
            <label>Address<input value={offer.address} onChange={e => setOffer({ ...offer, address: e.target.value })} /></label>
            <label>PIN code<input inputMode="numeric" maxLength={6} value={offer.pincode} onChange={e => setOffer({ ...offer, pincode: e.target.value })} /></label>
            <label>Area (sq ft)<input inputMode="numeric" value={offer.areaSqft} onChange={e => setOffer({ ...offer, areaSqft: e.target.value })} /></label>
          </div>
          <p className="small muted">It then goes through the usual checks (documents, photos, valuation) on its own collateral page before it can be swapped in.</p>
          <button className="btn primary" onClick={() => run(() => api.post(`/api/book/loans/${loanId}/collateral/${form.c.id}/substitute`,
            { ...offer, areaSqft: offer.areaSqft ? Number(offer.areaSqft) : null, description: offer.description || null }))}>Record substitute</button>
        </> : <>
          <h4 style={{ marginTop: 0 }}>{form.kind === 'release' ? `Release ${form.c.colNo}` : `Swap ${form.c.colNo}`}</h4>
          {form.kind === 'swap' && <label>Substitute<select value={subId} onChange={e => setSubId(e.target.value)}>
            {offered(form.c).map(x => <option key={x.id} value={x.id}>{x.colNo} · {label(x.type).toLowerCase()} · {x.value ? inr(x.value) : 'no valuation yet'} · {label(x.status).toLowerCase()}</option>)}</select></label>}
          <label>Why<input name="changeReason" value={reason} onChange={e => setReason(e.target.value)} placeholder={form.kind === 'release' ? 'e.g. Part-payment made, shop to be sold' : 'e.g. Selling the house, offering the flat'} /></label>
          <button className="btn primary" disabled={reason.trim().length < 10} onClick={() => run(() => form.kind === 'release'
            ? api.post(`/api/book/loans/${loanId}/collateral/release`, { collateralId: form.c.id, reason })
            : api.post(`/api/book/loans/${loanId}/collateral/substitution`, { collateralId: form.c.id, newCollateralId: Number(subId), reason }))}>Ask for approval</button>
        </>}
        <button className="btn" style={{ marginLeft: 8 }} onClick={() => setForm(null)}>Cancel</button>
      </div>}
      <ErrorBox error={error} />

      {d.changes.length > 0 && <>
        <h4>Releases and swaps</h4>
        <Table head={['Asked', 'What', 'Check', 'Status', '']} rows={d.changes.map(x => [
          <span className="small">{when(x.requestedAt)} · {x.requestedBy}<div className="muted">{x.reason}</div></span>,
          <span className="small">{x.kind === 'RELEASE' ? `Release ${name(x.collateralId)}` : `Swap ${name(x.collateralId)} for ${name(x.newCollateralId)}`}
            <div className="muted">Outstanding {inr(x.outstandingAt)}{x.ltvAfter != null ? ` · LTV after ${pct(x.ltvAfter, 1)}` : ''}</div></span>,
          <span className="small">{x.checkNote}</span>,
          <span className="small"><span className={'pill ' + (x.status === 'COMPLETED' ? 'ok' : x.status === 'REJECTED' ? 'bad' : 'warn')}>{x.status.toLowerCase()}</span>
            {x.decidedBy && <div className="muted">{x.decidedBy}: {x.decisionNote}</div>}
            {x.dueOn && x.status !== 'COMPLETED' && <div>Documents due {x.dueOn}</div>}
            {x.completedAt && <div className="muted">Documents to {x.handedTo} · {when(x.completedAt)}{(x.daysLate ?? 0) > 0 ? ` · ${x.daysLate} days late, ${inr(x.compensation ?? 0)} compensation` : ''}</div>}
            {x.letter && <details><summary>Release letter</summary><pre className="small" style={{ whiteSpace: 'pre-wrap' }} data-testid="release-letter">{x.letter}</pre></details>}</span>,
          <span className="row">
            {x.status === 'REQUESTED' && checker && x.requestedBy !== user?.username && <button className="btn sm" onClick={() => setAct({ id: x.id, kind: 'decide' })}>Decide</button>}
            {x.status === 'APPROVED' && handover && <button className="btn sm primary" data-action={`handover-${x.id}`} onClick={() => setAct({ id: x.id, kind: 'complete' })}>Documents handed back</button>}
          </span>])} />
      </>}
      {act && <div className="panel" style={{ marginTop: 8 }}>
        <label>{act.kind === 'decide' ? 'Note (at least 10 characters)' : 'Handed to (name of the borrower or their authorised person)'}<input name="changeNote" value={note} onChange={e => setNote(e.target.value)} /></label>
        {act.kind === 'decide' ? <div className="row">
          <button className="btn primary" disabled={note.trim().length < 10} onClick={() => run(() => api.post(`/api/collateral-changes/${act.id}/decision`, { approve: true, note }))}>Approve</button>
          <button className="btn danger" disabled={note.trim().length < 10} onClick={() => run(() => api.post(`/api/collateral-changes/${act.id}/decision`, { approve: false, note }))}>Reject</button>
          <button className="btn" onClick={() => setAct(null)}>Cancel</button>
        </div> : <div className="row">
          <button className="btn primary" disabled={note.trim().length < 3} onClick={() => run(() => api.post(`/api/collateral-changes/${act.id}/complete`, { handedTo: note }))}>Record handover and issue the letter</button>
          <button className="btn" onClick={() => setAct(null)}>Cancel</button>
        </div>}
      </div>}
      <p className="small muted">{d.rule}</p>
    </div>
  )
}
