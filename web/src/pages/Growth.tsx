import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api'
import { useAuth } from '../auth'
import { ErrorBox, Modal, Table, useLoad } from '../components/ui'
import { inr, label, pct, when } from '../format'

interface Rec { propensity: number; channel: string; bestTime: string; script: string; priority: number; drivers: string[] }
interface ContactRow { id: number; channel: string; outcome: string; contactedAt: string; ptpDate?: string; ptpAmount?: number; ptpStatus?: string; note?: string; createdBy: string }
interface QueueRow {
  loanId: number; loanRef: string; borrowerName: string; segment: string; outstanding: number; overdue: number; dpd: number; classification: string; ewsScore: number
  recommendation: Rec; ptpKept: number; ptpBroken: number; openPromise?: ContactRow; lastContact?: ContactRow
}

const CH: Record<string, string> = { SMS: 'SMS', WHATSAPP: 'WhatsApp', CALL: 'Call', FIELD_VISIT: 'Field visit', NOTICE: 'Notice' }

/** Collections work queue with the AI recommendation per loan. */
export function Collections({ rev }: { rev: number }) {
  const [r, setR] = useState(0)
  const q = useLoad(() => api.get<QueueRow[]>('/api/collections/queue'), [rev, r])
  const st = useLoad(() => api.get<{ contacts: number; promisesKept: number; promisesBroken: number; promisesOpen: number; keptRate?: number }>('/api/collections/stats'), [rev, r])
  const [open, setOpen] = useState<QueueRow | null>(null)
  return (
    <>
      {st.data && <div className="kpis">
        <div className="kpi"><span>In the queue</span><b>{q.data?.length ?? '…'}</b></div>
        <div className="kpi"><span>Contacts logged</span><b>{st.data.contacts}</b></div>
        <div className="kpi"><span>Promises open</span><b>{st.data.promisesOpen}</b></div>
        <div className="kpi"><span>Promises kept</span><b>{st.data.keptRate == null ? '—' : pct(st.data.keptRate, 0)}</b><span>{st.data.promisesKept} kept, {st.data.promisesBroken} broken</span></div>
      </div>}
      <p className="small muted">Overdue loans and loans on the early-warning list, most urgent first. Calls and visits only between 8 am and 7 pm.</p>
      <ErrorBox error={q.error} />
      <div className="panel flush">
        <Table head={['Priority', 'Loan', 'DPD', 'Overdue', 'Likely to pay', 'Next step', 'Best time', 'Promise']} onRow={i => setOpen(q.data![i])}
          rows={(q.data ?? []).map(x => [x.recommendation.priority, <span>{x.borrowerName}<div className="small muted mono">{x.loanRef}</div></span>, x.dpd, inr(x.overdue),
            <span className={'pill ' + (x.recommendation.propensity >= 0.6 ? 'ok' : x.recommendation.propensity >= 0.3 ? 'warn' : 'bad')}>{pct(x.recommendation.propensity, 0)}</span>,
            CH[x.recommendation.channel], <span className="small">{x.recommendation.bestTime}</span>,
            x.openPromise ? <span className="small">{inr(x.openPromise.ptpAmount)} by {x.openPromise.ptpDate}</span> : x.ptpBroken ? <span className="pill bad">{x.ptpBroken} broken</span> : '—'])}
          empty={q.loading ? 'Loading…' : 'Nothing to collect.'} />
      </div>
      {open && <ContactPanel loanId={open.loanId} title={`${open.borrowerName} · ${open.loanRef}`} onClose={() => setOpen(null)} onDone={() => setR(x => x + 1)} />}
    </>
  )
}

export function ContactPanel({ loanId, title, onClose, onDone }: { loanId: number; title: string; onClose: () => void; onDone: () => void }) {
  const [r, setR] = useState(0)
  const d = useLoad(() => api.get<QueueRow & { contacts: ContactRow[] }>(`/api/collections/loans/${loanId}`), [loanId, r])
  const [f, setF] = useState({ channel: 'CALL', outcome: 'PROMISE_TO_PAY', ptpDate: '', ptpAmount: '', note: '' })
  const [error, setError] = useState<unknown>()
  async function save() {
    setError(undefined)
    try {
      await api.post(`/api/collections/loans/${loanId}/contacts`, { ...f, ptpAmount: f.ptpAmount ? Number(f.ptpAmount) : undefined, ptpDate: f.ptpDate || undefined })
      setR(x => x + 1); onDone(); setF({ ...f, note: '' })
    } catch (e) { setError(e) }
  }
  const rec = d.data?.recommendation
  return (
    <Modal title={title} onClose={onClose}>
      <ErrorBox error={d.error ?? error} />
      {rec && <div className="alert">
        <b>{CH[rec.channel]}</b> · {rec.bestTime} · likely to pay {pct(rec.propensity, 0)}<div className="small">{rec.script}</div>
        {rec.drivers.length > 0 && <div className="small muted">{rec.drivers.join(' · ')}</div>}
      </div>}
      <div className="grid2">
        <label>Channel<select value={f.channel} onChange={e => setF({ ...f, channel: e.target.value })}>{Object.entries(CH).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label>
        <label>Outcome<select value={f.outcome} onChange={e => setF({ ...f, outcome: e.target.value })}>
          {['PROMISE_TO_PAY', 'PAID', 'NO_ANSWER', 'REFUSED', 'DISPUTE', 'WRONG_NUMBER', 'SENT'].map(o => <option key={o} value={o}>{label(o)}</option>)}</select></label>
        {f.outcome === 'PROMISE_TO_PAY' && <>
          <label>Promised date<input type="date" value={f.ptpDate} onChange={e => setF({ ...f, ptpDate: e.target.value })} /></label>
          <label>Amount (₹)<input inputMode="numeric" value={f.ptpAmount} onChange={e => setF({ ...f, ptpAmount: e.target.value })} /></label>
        </>}
      </div>
      <label>Note<input value={f.note} maxLength={500} onChange={e => setF({ ...f, note: e.target.value })} /></label>
      <button className="btn primary" data-action="logContact" onClick={save}>Log contact</button>
      <h4>History</h4>
      <Table head={['When', 'Channel', 'Outcome', 'Promise', 'By']} rows={(d.data?.contacts ?? []).map(c => [when(c.contactedAt), CH[c.channel], label(c.outcome),
        c.ptpDate ? <span>{inr(c.ptpAmount)} by {c.ptpDate} <span className={'pill ' + (c.ptpStatus === 'KEPT' ? 'ok' : c.ptpStatus === 'BROKEN' ? 'bad' : 'muted')}>{c.ptpStatus?.toLowerCase()}</span></span> : '—', c.createdBy])} empty="No contacts yet." />
    </Modal>
  )
}

interface Topup { loanId: number; loanRef: string; borrowerName: string; segment: string; productCode: string; outstanding: number; paidEmis: number; amount: number; rate: number; tenureMonths: number; canApply: boolean; offer?: { id: number; expiresOn: string } }

/** Good payers who can be offered a top-up; an accepted offer becomes a pre-filled draft application. */
export function Topups({ rev }: { rev: number }) {
  const [r, setR] = useState(0)
  const t = useLoad(() => api.get<Topup[]>('/api/topups/eligible'), [rev, r])
  const [error, setError] = useState<unknown>()
  const nav = useNavigate()
  async function act(fn: () => Promise<unknown>) { setError(undefined); try { await fn(); setR(x => x + 1) } catch (e) { setError(e) } }
  return (
    <>
      <p className="small muted">Active loans with at least 6 months on book and 6 instalments paid, never more than 30 days late, no bounce in 3 months, not restructured, behaviour PD under 10%.
        Total exposure up to 1.5× the original loan. Taking up an offer creates a draft application; the borrower gives fresh consent and it is underwritten again.</p>
      <ErrorBox error={error ?? t.error} />
      <div className="panel flush">
        <Table head={['Borrower', 'Outstanding', 'EMIs paid', 'Top-up', 'Rate', '']} rows={(t.data ?? []).map(x => [
          <span>{x.borrowerName}<div className="small muted"><span className="mono">{x.loanRef}</span> · {x.productCode}</div></span>, inr(x.outstanding), x.paidEmis, inr(x.amount), x.rate + '%',
          x.offer ? <span className="row">
            {x.canApply && <button className="btn small primary" onClick={() => act(async () => { const a = await api.post<{ applicationId: number }>(`/api/topups/offers/${x.offer!.id}/apply`); nav(`/applications/${a.applicationId}`) })}>Borrower accepts</button>}
            <button className="btn small" onClick={() => act(() => api.post(`/api/topups/offers/${x.offer!.id}/decline`))}>Declined</button>
            <span className="small muted">offer valid to {x.offer.expiresOn}</span></span>
            : <button className="btn small" data-action="offerTopup" onClick={() => act(() => api.post(`/api/topups/loans/${x.loanId}/offer`))}>Make offer</button>])}
          empty={t.loading ? 'Loading…' : 'No eligible borrowers yet.'} />
      </div>
    </>
  )
}

interface Proposal { id: number; loanId: number; kind: string; newTenureMonths: number; newRate: number; moratoriumMonths: number; newEmi: number; outstandingAt: number; reason: string; status: string; requestedBy: string; requestedAt: string; decidedBy?: string; decisionNote?: string; scheduleJson?: string }

/** Restructuring proposals waiting for a checker. */
export function Restructures({ rev }: { rev: number }) {
  const { user } = useAuth()
  const [r, setR] = useState(0)
  const p = useLoad(() => api.get<{ proposal: Proposal; loanRef: string; borrowerName: string; currentEmi: number; dpd: number; classification: string }[]>('/api/restructures/pending'), [rev, r])
  const [error, setError] = useState<unknown>()
  const checker = user && ['ADMIN', 'CREDIT_MANAGER', 'CRO'].includes(user.role)
  async function decide(id: number, ok: boolean) {
    const note = window.prompt(ok ? 'Approval note (optional)' : 'Why is it rejected?') ?? undefined
    setError(undefined)
    try { await api.post(`/api/restructures/${id}/${ok ? 'approve' : 'reject'}`, { note }); setR(x => x + 1) } catch (e) { setError(e) }
  }
  return (
    <>
      <p className="small muted">Maker-checker: the person who proposes cannot approve. On approval the arrears are capitalised and the loan is classified NPA (restructured) until a year of on-time payment under the new terms (RBI prudential framework).</p>
      <ErrorBox error={error ?? p.error} />
      <div className="panel flush">
        <Table head={['Borrower', 'Proposal', 'EMI', 'Reason', 'Proposed by', '']} rows={(p.data ?? []).map(x => [
          <span>{x.borrowerName}<div className="small muted mono">{x.loanRef} · {x.dpd} DPD</div></span>,
          <span className="small">{label(x.proposal.kind)}: {x.proposal.newTenureMonths} months at {x.proposal.newRate}%{x.proposal.moratoriumMonths ? `, ${x.proposal.moratoriumMonths}-month moratorium` : ''}</span>,
          <span className="small">{inr(x.currentEmi)} → {inr(x.proposal.newEmi)}</span>, <span className="small">{x.proposal.reason}</span>, x.proposal.requestedBy,
          checker && x.proposal.requestedBy !== user?.username ? <span className="row"><button className="btn small primary" onClick={() => decide(x.proposal.id, true)}>Approve</button>
            <button className="btn small" onClick={() => decide(x.proposal.id, false)}>Reject</button></span> : <span className="small muted">{checker ? 'You proposed it' : 'Awaiting checker'}</span>])}
          empty={p.loading ? 'Loading…' : 'Nothing waiting.'} />
      </div>
    </>
  )
}

/** Restructuring and collection actions inside the loan detail. */
export function LoanActions({ loan, onChange }: { loan: { id: number; loanRef: string; borrowerName: string; status: string; tenureMonths: number; rate: number; emi: number }; onChange: () => void }) {
  const { user } = useAuth()
  const [r, setR] = useState(0)
  const hist = useLoad(() => api.get<Proposal[]>(`/api/book/loans/${loan.id}/restructures`), [loan.id, r])
  const [form, setForm] = useState(false)
  const [contacts, setContacts] = useState(false)
  const [f, setF] = useState({ kind: 'TENURE_EXTENSION', newTenureMonths: '', newRate: '', moratoriumMonths: '0', reason: '' })
  const [error, setError] = useState<unknown>()
  const maker = user && ['ADMIN', 'OPERATIONS', 'CREDIT_OFFICER', 'CREDIT_MANAGER'].includes(user.role)
  async function propose() {
    setError(undefined)
    try {
      await api.post(`/api/book/loans/${loan.id}/restructure`, { kind: f.kind, reason: f.reason, moratoriumMonths: Number(f.moratoriumMonths || 0),
        newTenureMonths: f.newTenureMonths ? Number(f.newTenureMonths) : undefined, newRate: f.newRate ? Number(f.newRate) : undefined })
      setForm(false); setR(x => x + 1); onChange()
    } catch (e) { setError(e) }
  }
  return (
    <div style={{ marginTop: 12 }}>
      <ErrorBox error={error} />
      <div className="row">
        {maker && loan.status === 'ACTIVE' && <button className="btn" onClick={() => setForm(!form)}>Propose restructuring</button>}
        <button className="btn" onClick={() => setContacts(true)}>Collections contacts</button>
      </div>
      {form && <div className="panel" style={{ marginTop: 8 }}>
        <div className="grid2">
          <label>Kind<select value={f.kind} onChange={e => setF({ ...f, kind: e.target.value })}>
            <option value="TENURE_EXTENSION">Tenure extension</option><option value="MORATORIUM">Moratorium</option><option value="RATE_REDUCTION">Rate reduction</option></select></label>
          <label>Remaining tenure (months)<input inputMode="numeric" placeholder="unchanged" value={f.newTenureMonths} onChange={e => setF({ ...f, newTenureMonths: e.target.value })} /></label>
          <label>New rate (% a year)<input inputMode="decimal" placeholder={String(loan.rate)} value={f.newRate} onChange={e => setF({ ...f, newRate: e.target.value })} /></label>
          <label>Moratorium (months, 0–6)<input inputMode="numeric" value={f.moratoriumMonths} onChange={e => setF({ ...f, moratoriumMonths: e.target.value })} /></label>
        </div>
        <label>Reason (the borrower's cash-flow problem)<input value={f.reason} maxLength={500} onChange={e => setF({ ...f, reason: e.target.value })} /></label>
        <button className="btn primary" onClick={propose}>Send for approval</button>
      </div>}
      {(hist.data ?? []).length > 0 && <>
        <h4>Restructuring</h4>
        <Table head={['Proposed', 'Terms', 'EMI', 'Status', 'Checker']} rows={hist.data!.map(x => [when(x.requestedAt) + ' by ' + x.requestedBy,
          `${label(x.kind)}: ${x.newTenureMonths} months at ${x.newRate}%${x.moratoriumMonths ? `, ${x.moratoriumMonths}-month moratorium` : ''}`, inr(x.newEmi),
          <span className={'pill ' + (x.status === 'APPROVED' ? 'ok' : x.status === 'REJECTED' ? 'bad' : 'warn')}>{x.status.toLowerCase()}</span>, x.decidedBy ?? '—'])} />
      </>}
      {contacts && <ContactPanel loanId={loan.id} title={`${loan.borrowerName} · ${loan.loanRef}`} onClose={() => setContacts(false)} onDone={onChange} />}
    </div>
  )
}
