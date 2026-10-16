import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api, getToken } from '../api'
import { useAuth } from '../auth'
import { ErrorBox, Head, Modal, Pill, Table, useLoad, useTab } from '../components/ui'
import { inr, lakh, pct } from '../format'
import { Collections, LoanActions, Restructures, Topups } from './Growth'
import { LoanCollateral } from '../collateral/LoanCollateral'

interface Loan {
  id: number; loanRef: string; applicationId?: number; appNo?: string; borrowerName: string; segment: string; productCode: string
  sanctionedAmount: number; disbursedOn: string; tenureMonths: number; rate: number; emi: number; source: string; status: string
  outstanding: number; overdue: number; dpd: number; maxDpd: number; bounces3m: number; paidEmis: number; classification: string
  eclStage: number; ecl: number; provisionIracp: number; behaviourPd?: number; ewsScore: number; restructured: boolean; lastFeedDate?: string
}
interface Feed { id: number; feedDate: string; source: string; rowsTotal: number; accepted: number; rejected: number; errorsJson?: string; createdBy: string; createdAt: string }
interface Summary {
  loans: number; closed: number; outstanding: number; gnpa: number; nnpa: number; par30: number; ecl: number; provisionIracp: number; provisionHeld: number
  impairmentReserve: number; watchlist: number; classification: Record<string, { loans: number; outstanding: number; share: number; ecl: number; provisionIracp: number }>
  stages: Record<string, { loans: number; ead: number; ecl: number; coverage: number }>; lastFeed?: Feed; sources: Record<string, number>
}

const CLS_PILL: Record<string, string> = { STANDARD: 'ok', 'SMA-0': 'warn', 'SMA-1': 'warn', 'SMA-2': 'bad', NPA: 'bad' }

/** Loan book: LMS feed, SMA/NPA, Ind AS 109 ECL and the early-warning watchlist. */
export default function Portfolio() {
  const { user } = useAuth()
  const [tab, setTab] = useTab(['summary', 'loans', 'watch', 'collections', 'topups', 'restructures', 'feeds'] as const, 'summary')
  const [rev, setRev] = useState(0)
  const [msg, setMsg] = useState<string>()
  const [error, setError] = useState<unknown>()
  const [busy, setBusy] = useState(false)
  const s = useLoad(() => api.get<Summary>('/api/book/summary'), [rev])
  const canFeed = user && ['ADMIN', 'OPERATIONS'].includes(user.role)

  async function demo() {
    setBusy(true); setError(undefined)
    try { const f = await api.post<Feed>('/api/book/feed/demo'); setMsg(`Demo LMS feed for ${f.feedDate}: ${f.accepted} loans updated`); setRev(r => r + 1) }
    catch (e) { setError(e) } finally { setBusy(false) }
  }
  async function upload(file: File | undefined) {
    if (!file) return
    setBusy(true); setError(undefined)
    try {
      const fd = new FormData(); fd.append('file', file)
      const t = getToken()
      const r = await fetch('/api/book/feed/csv', { method: 'POST', body: fd, headers: t ? { Authorization: 'Bearer ' + t } : {} })
      const b = await r.json()
      if (!r.ok) throw new Error(b.message)
      setMsg(`CSV feed ${b.feedDate}: ${b.accepted} accepted, ${b.rejected} rejected`); setRev(x => x + 1)
    } catch (e) { setError(e) } finally { setBusy(false) }
  }

  return (
    <>
      <Head title="Loan book & early warning" sub="Live loans from the LMS feed: asset classification (SMA / NPA), expected credit loss (Ind AS 109) against the RBI minimum provision, and early warnings.">
        {canFeed && <>
          <label className="btn">Upload LMS CSV<input type="file" accept=".csv,text/csv" style={{ display: 'none' }} onChange={e => { upload(e.target.files?.[0]); e.target.value = '' }} /></label>
          <button className="btn primary" disabled={busy} data-action="demoFeed" onClick={demo}>{busy ? 'Loading…' : 'Run demo LMS feed (one month)'}</button>
        </>}
      </Head>
      <ErrorBox error={error ?? s.error} />
      {msg && <div className="alert ok">{msg}</div>}
      <div className="tabs" role="tablist">
        {([['summary', 'Summary'], ['loans', 'Loans'], ['watch', 'Early warning'], ['collections', 'Collections'], ['topups', 'Top-ups'], ['restructures', 'Restructuring'], ['feeds', 'LMS feeds']] as const)
          .filter(([k]) => !(k === 'collections' && user?.role === 'COMPLIANCE') && !(k === 'topups' && !['ADMIN', 'OPERATIONS', 'CREDIT_MANAGER', 'CRO'].includes(user?.role ?? ''))).map(([k, l]) =>
          <button key={k} role="tab" aria-selected={tab === k} className={tab === k ? 'on' : ''} onClick={() => setTab(k)}>{l}</button>)}
      </div>
      {tab === 'summary' && s.data && <SummaryView s={s.data} />}
      {tab === 'loans' && <LoanList rev={rev} watch={false} />}
      {tab === 'watch' && <LoanList rev={rev} watch />}
      {tab === 'collections' && <Collections rev={rev} />}
      {tab === 'topups' && <Topups rev={rev} />}
      {tab === 'restructures' && <Restructures rev={rev} />}
      {tab === 'feeds' && <Feeds rev={rev} />}
    </>
  )
}

function SummaryView({ s }: { s: Summary }) {
  if (s.loans === 0 && s.closed === 0) return <div className="panel empty">No loans in the book yet. Disbursed loans join automatically; load an LMS feed (CSV) or run the demo feed.</div>
  return (
    <>
      <div className="kpis" data-testid="book-summary">
        <div className="kpi"><span>Live loans</span><b>{s.loans}</b><span>{s.closed} closed</span></div>
        <div className="kpi"><span>Outstanding</span><b>{lakh(s.outstanding)}</b></div>
        <div className="kpi"><span>PAR 30+</span><b>{pct(s.par30, 1)}</b></div>
        <div className="kpi"><span>Gross NPA</span><b className={s.gnpa > 0.05 ? 'bad-text' : ''}>{pct(s.gnpa, 2)}</b><span>net {pct(s.nnpa, 2)}</span></div>
        <div className="kpi"><span>ECL (Ind AS 109)</span><b>{lakh(s.ecl)}</b><span>RBI minimum {lakh(s.provisionIracp)}</span></div>
        <div className="kpi"><span>On the watchlist</span><b>{s.watchlist}</b></div>
      </div>
      <div className="grid2" style={{ marginTop: 12 }}>
        <div className="panel flush">
          <h3 style={{ padding: '12px 12px 0' }}>Asset classification</h3>
          <Table head={['Class', 'Loans', 'Outstanding', 'Share', 'ECL', 'RBI minimum']} rows={Object.entries(s.classification).map(([k, v]) => [
            <span className={'pill ' + CLS_PILL[k]}>{k}</span>, v.loans, inr(v.outstanding), pct(v.share, 1), inr(v.ecl), inr(v.provisionIracp)])} />
        </div>
        <div className="panel flush">
          <h3 style={{ padding: '12px 12px 0' }}>Expected credit loss by stage</h3>
          <Table head={['Stage', 'Loans', 'Exposure', 'ECL', 'Coverage']} rows={Object.entries(s.stages).map(([k, v]) => [k, v.loans, inr(v.ead), inr(v.ecl), pct(v.coverage, 2)])} />
          <p className="small muted" style={{ padding: '0 12px 12px' }}>Stage 1: up to 30 days past due (12-month loss). Stage 2: 31–90 days or restructured (lifetime loss). Stage 3: NPA (credit-impaired).
            Provision held is the higher of ECL and the RBI minimum per loan: {inr(s.provisionHeld)}{s.impairmentReserve > 0 && <>; impairment reserve for the shortfall {inr(s.impairmentReserve)}</>}.</p>
        </div>
      </div>
      <p className="small muted">Sources: {s.sources.RHYTHM} disbursed in Rhythm, {s.sources.LMS} from the LMS feed, {s.sources.DEMO} demo.{s.lastFeed && ` Last feed ${s.lastFeed.feedDate} (${s.lastFeed.source}).`}</p>
    </>
  )
}

function LoanList({ rev, watch }: { rev: number; watch: boolean }) {
  const [cls, setCls] = useState('')
  const [q, setQ] = useState('')
  const [open, setOpen] = useState<number | null>(null)
  const l = useLoad(() => api.get<Loan[]>(`/api/book/loans?watch=${watch}&classification=${cls}&q=${encodeURIComponent(q)}`), [rev, cls, q, watch])
  return (
    <>
      {!watch && <div className="row" style={{ marginBottom: 12 }}>
        <input placeholder="Loan id, name or application" value={q} onChange={e => setQ(e.target.value)} style={{ maxWidth: 260 }} />
        <select value={cls} onChange={e => setCls(e.target.value)} aria-label="Classification">
          <option value="">All classes</option>{['STANDARD', 'SMA-0', 'SMA-1', 'SMA-2', 'NPA', 'CLOSED'].map(c => <option key={c}>{c}</option>)}
        </select>
      </div>}
      {watch && <p className="small muted">Loans with an early-warning score of 40 or more, highest first, with the suggested action.</p>}
      <ErrorBox error={l.error} />
      <div className="panel flush">
        <Table head={['Loan', 'Borrower', 'Outstanding', 'DPD', 'Class', 'ECL stage', 'ECL', 'Early warning']} onRow={i => setOpen(l.data![i].id)} rows={(l.data ?? []).map(x => [
          <span className="mono">{x.loanRef}</span>, <span>{x.borrowerName}<div className="small muted">{x.productCode} · {x.segment.toLowerCase()}{x.restructured ? ' · restructured' : ''}</div></span>,
          inr(x.outstanding), x.dpd, <span className={'pill ' + (CLS_PILL[x.classification] ?? '')}>{x.status === 'ACTIVE' ? x.classification : x.status.toLowerCase()}</span>,
          x.eclStage, inr(x.ecl), <span className={'pill ' + (x.ewsScore >= 60 ? 'bad' : x.ewsScore >= 40 ? 'warn' : 'muted')}>{x.ewsScore}</span>,
        ])} empty={l.loading ? 'Loading…' : 'No loans.'} />
      </div>
      {open && <LoanDetail id={open} onClose={() => setOpen(null)} />}
    </>
  )
}

interface Snap { feedDate: string; outstanding: number; overdue: number; dpd: number; classification: string; eclStage: number; ecl: number }

function LoanDetail({ id, onClose }: { id: number; onClose: () => void }) {
  const [r, setR] = useState(0)
  const d = useLoad(() => api.get<{ loan: Loan; ews: { signals?: { reason: string; points: number }[]; action?: string }; history: Snap[]; monthsOnBook: number; parties?: { id: number; role: string; partyType: string; name: string; pan: string; relationship: string }[] }>(`/api/book/loans/${id}`), [id, r])
  return (
    <Modal title="Loan" onClose={onClose}>
      <ErrorBox error={d.error} />
      {d.data && (() => {
        const l = d.data.loan
        return (
          <div data-loan-detail>
            <p><b>{l.borrowerName}</b> · <span className="mono">{l.loanRef}</span>{l.applicationId && <> · <Link to={`/applications/${l.applicationId}`}>{l.appNo}</Link></>}</p>
            <p className="small">{inr(l.sanctionedAmount)} at {l.rate}% for {l.tenureMonths} months, EMI {inr(l.emi)} · disbursed {l.disbursedOn} · {d.data.monthsOnBook} months on book · {l.paidEmis} EMIs paid</p>
            {(d.data.parties ?? []).length > 0 && <p className="small" data-testid="book-parties">With the borrower: {(d.data.parties ?? []).map(p => `${p.name} (${p.role === 'GUARANTOR' ? 'guarantor' : 'co-applicant'}, ${p.pan})`).join(', ')}</p>}
            <p className="small">Outstanding {inr(l.outstanding)} · overdue {inr(l.overdue)} · {l.dpd} days past due (worst {l.maxDpd}) · {l.classification} · ECL stage {l.eclStage}: {inr(l.ecl)} · RBI minimum {inr(l.provisionIracp)}
              {l.behaviourPd != null && <> · behaviour PD {pct(l.behaviourPd, 1)}</>}</p>
            <h4>Early warning: {l.ewsScore}/100</h4>
            <ul className="small">{(d.data.ews.signals ?? []).map(x => <li key={x.reason}>{x.reason} (+{x.points})</li>)}</ul>
            <p className="small"><b>Suggested action:</b> {d.data.ews.action}</p>
            <LoanActions loan={l} onChange={() => setR(x => x + 1)} />
            {l.applicationId && <LoanCollateral loanId={l.id} />}
            <h4>Feed history</h4>
            <Table head={['Feed date', 'Outstanding', 'Overdue', 'DPD', 'Class', 'Stage', 'ECL']} rows={d.data.history.map(h => [h.feedDate, inr(h.outstanding), inr(h.overdue), h.dpd, <Pill s={h.classification} />, h.eclStage, inr(h.ecl)])} />
          </div>
        )
      })()}
    </Modal>
  )
}

function Feeds({ rev }: { rev: number }) {
  const f = useLoad(() => api.get<Feed[]>('/api/book/feeds'), [rev])
  return (
    <>
      <p className="small muted">CSV columns: loanRef, appNo, outstanding, overdue, dpd, bouncesLast3m, lastPaymentDate, paidEmis, status; for loans new to the book also borrowerName, segment, productCode, sanctionedAmount, disbursedOn, tenureMonths, rate, emi, secured. The LMS can also post the same rows to <span className="mono">POST /api/book/feed</span>.</p>
      <div className="panel flush">
        <Table head={['Feed date', 'Source', 'Rows', 'Accepted', 'Rejected', 'By', 'Errors']} rows={(f.data ?? []).map(x => [x.feedDate, x.source, x.rowsTotal, x.accepted, x.rejected, x.createdBy,
          x.errorsJson ? <details><summary className="small">{(JSON.parse(x.errorsJson) as string[]).length} errors</summary><ul className="small">{(JSON.parse(x.errorsJson) as string[]).map(e => <li key={e}>{e}</li>)}</ul></details> : '—'])} empty="No feeds yet." />
      </div>
    </>
  )
}
