import { useState } from 'react'
import { api } from '../api'
import { ErrorBox, Head, Table, useLoad } from '../components/ui'
import { inr, label, pct } from '../format'

// Management information for a date range: funnel, decisions, turnaround, operations and control indicators.

type Row = Record<string, number | string | null>
interface Summary {
  from: string; to: string; generatedAt: string
  funnel: Row
  products: Row[]; branches: Row[]
  decisions: { mix: Row[]; bands: Row[]; policies: Row[] }
  turnaround: Row
  daily: Row[]
  operations: { documents: Row[]; kyc: Row; videoKyc: Row; vendors: Row[] }
  controls: { sanctionOverrides: number; referredButSanctioned: number; videoKycOverrides: number; documentsApprovedWithTamperFlag: number; policyChanges: Row[] }
}

const n = (v: unknown) => Number(v ?? 0)
const iso = (d: Date) => d.toISOString().slice(0, 10)
const hours = (v: unknown) => v === null || v === undefined ? '—' : n(v) < 48 ? `${n(v)} h` : `${(n(v) / 24).toFixed(1)} days`

export default function Reports() {
  const today = new Date()
  const [from, setFrom] = useState(iso(new Date(today.getTime() - 29 * 864e5)))
  const [to, setTo] = useState(iso(today))
  const r = useLoad(() => api.get<Summary>(`/api/reports/summary?from=${from}&to=${to}`), [from, to])
  const s = r.data

  function preset(days: number) {
    const t = new Date()
    setTo(iso(t))
    setFrom(iso(new Date(t.getTime() - (days - 1) * 864e5)))
  }

  async function download() {
    let token: string | null = null
    try { token = localStorage.getItem('rhythm_token') } catch { /* ignore */ }
    const res = await fetch(`/api/reports/applications.csv?from=${from}&to=${to}`, { headers: token ? { Authorization: 'Bearer ' + token } : {} })
    const url = URL.createObjectURL(await res.blob())
    const a = document.createElement('a')
    a.href = url
    a.download = `applications-${from}-to-${to}.csv`
    a.click()
    URL.revokeObjectURL(url)
  }

  return (
    <>
      <Head title="Reports" sub="Funnel, decisions, turnaround and controls for applications created in the selected dates.">
        <button className="btn" onClick={download} data-report="csv">Download applications (CSV)</button>
      </Head>
      <div className="panel row" style={{ gap: 10 }}>
        <label className="small">From <input type="date" value={from} onChange={e => setFrom(e.target.value)} aria-label="From" /></label>
        <label className="small">To <input type="date" value={to} onChange={e => setTo(e.target.value)} aria-label="To" /></label>
        {[7, 30, 90, 365].map(d => <button key={d} className="btn sm" onClick={() => preset(d)}>Last {d} days</button>)}
      </div>
      <ErrorBox error={r.error} />
      {!s ? <div className="panel empty">Loading…</div> : (
        <div data-report="summary">
          <Funnel f={s.funnel} />
          <div className="kpis">
            <div className="kpi"><span>Submitted → decision (median)</span><b>{hours(s.turnaround.submit_to_decision_p50)}</b><span>90% within {hours(s.turnaround.submit_to_decision_p90)}</span></div>
            <div className="kpi"><span>Decision → sanction (median)</span><b>{hours(s.turnaround.decision_to_sanction_p50)}</b></div>
            <div className="kpi"><span>Sanction → disbursal (median)</span><b>{hours(s.turnaround.sanction_to_disbursal_p50)}</b></div>
            <div className="kpi"><span>Application → money (median)</span><b>{hours(s.turnaround.end_to_end_p50)}</b><span>{n(s.turnaround.disbursed_count)} disbursed</span></div>
          </div>
          <Daily rows={s.daily} />
          <div className="grid2">
            <div className="panel">
              <h3 style={{ marginTop: 0 }}>By product</h3>
              <Table head={['Product', 'Applications', 'Requested', 'Disbursed', 'Amount disbursed', 'Declined']} rows={s.products.map(p => [
                String(p.product), n(p.applications), inr(n(p.requested)), n(p.disbursed), inr(n(p.disbursed_amount)), n(p.rejected),
              ])} empty="No applications in these dates." />
            </div>
            <div className="panel">
              <h3 style={{ marginTop: 0 }}>By branch</h3>
              <Table head={['Branch', 'Applications', 'Disbursed', 'Amount']} rows={s.branches.map(b => [String(b.branch), n(b.applications), n(b.disbursed), inr(n(b.disbursed_amount))])} empty="No applications in these dates." />
            </div>
          </div>
          <div className="grid2">
            <div className="panel">
              <h3 style={{ marginTop: 0 }}>Decision engine</h3>
              <Table head={['Decision', 'Applications', 'Average PD']} rows={s.decisions.mix.map(m => [label(String(m.decision)), n(m.n), pct(n(m.avg_pd), 1)])} empty="No decisions yet." />
              <div className="row" style={{ marginTop: 10, gap: 8 }}>
                {s.decisions.bands.map(b => <span key={String(b.band)} className="pill">Band {String(b.band)}: {n(b.n)}</span>)}
              </div>
              <p className="small muted">Policies used: {s.decisions.policies.map(p => `${p.policy} (${n(p.n)})`).join(', ') || '—'}</p>
            </div>
            <div className="panel" data-report="controls">
              <h3 style={{ marginTop: 0 }}>Controls</h3>
              <Table head={['Indicator', 'Count']} rows={[
                ['Sanctions above the recommended amount (override)', n(s.controls.sanctionOverrides)],
                ['Referred by the engine but sanctioned', n(s.controls.referredButSanctioned)],
                ['Video KYC approved against the AI', n(s.controls.videoKycOverrides)],
                ['Documents verified despite an edit signal', n(s.controls.documentsApprovedWithTamperFlag)],
              ].map(([a, b]) => [a, <b className={n(b) > 0 ? 'changed-text' : ''}>{b}</b>])} />
              {s.controls.policyChanges.length > 0 && <p className="small">Credit policy changes: {s.controls.policyChanges.map(p => `${p.version} (${String(p.status).toLowerCase()}, ${p.maker} → ${p.checker ?? '—'})`).join('; ')}</p>}
            </div>
          </div>
          <div className="grid2">
            <div className="panel">
              <h3 style={{ marginTop: 0 }}>Document AI</h3>
              <Table head={['Document', 'Read', 'Auto-accepted', 'Needed a person', 'Rejected', 'Edit signal']} rows={s.operations.documents.map(d => [
                String(d.type).replace(/_/g, ' ').toLowerCase(), n(d.n), pct(n(d.auto_ok) / Math.max(1, n(d.n)), 0), n(d.reviewed), n(d.rejected), n(d.tamper),
              ])} empty="No documents." />
              <p className="small">KYC: {n(s.operations.kyc.verified)} verified · {n(s.operations.kyc.review)} in review · {n(s.operations.kyc.failed)} failed ·
                Video KYC: {n(s.operations.videoKyc.sessions)} sessions, {n(s.operations.videoKyc.approved)} approved, {n(s.operations.videoKyc.rejected)} rejected, {n(s.operations.videoKyc.abandoned)} abandoned</p>
            </div>
            <div className="panel">
              <h3 style={{ marginTop: 0 }}>Vendors and AI services</h3>
              <Table head={['Service', 'Calls', 'Failures', 'Dead letter', 'Avg time']} rows={s.operations.vendors.map(v => [
                <span className="small">{String(v.vendor)} · {String(v.operation)}</span>, n(v.calls),
                <span className={n(v.failures) ? 'changed-text' : ''}>{n(v.failures)} ({pct(n(v.failures) / Math.max(1, n(v.calls)), 0)})</span>, n(v.dead_letter), `${n(v.avg_ms)} ms`,
              ])} empty="No calls." />
            </div>
          </div>
        </div>
      )}
    </>
  )
}

function Funnel({ f }: { f: Row }) {
  const steps: [string, string][] = [['Created', 'created'], ['Submitted', 'submitted'], ['Decided', 'decided'], ['Engine approved', 'engine_approved'], ['Sanctioned', 'sanctioned'], ['Disbursed', 'disbursed']]
  const top = Math.max(1, n(f.created))
  return (
    <div className="panel" data-report="funnel">
      <h3 style={{ marginTop: 0 }}>Funnel</h3>
      {steps.map(([lbl, k]) => (
        <div key={k} className="funnel-row">
          <span className="small">{lbl}</span>
          <div className="bar"><span style={{ width: `${(n(f[k]) / top) * 100}%`, background: 'var(--brand, #3b4aa0)' }} /></div>
          <b className="num">{n(f[k])}</b>
        </div>
      ))}
      <p className="small muted" style={{ marginBottom: 0 }}>Referred {n(f.referred)} · declined {n(f.rejected)} · withdrawn {n(f.withdrawn)} · disbursed {inr(n(f.disbursed_amount))}</p>
    </div>
  )
}

function Daily({ rows }: { rows: Row[] }) {
  const max = Math.max(1, ...rows.map(r => n(r.applications)))
  return (
    <div className="panel">
      <h3 style={{ marginTop: 0 }}>Applications per day</h3>
      <div className="daily" aria-label="Applications per day">
        {rows.map(r => (
          <div key={String(r.day)} title={`${r.day}: ${n(r.applications)} applications, ${n(r.disbursals)} disbursed`}>
            <span style={{ height: `${(n(r.applications) / max) * 100}%` }} />
          </div>
        ))}
      </div>
      <div className="row small muted" style={{ justifyContent: 'space-between' }}><span>{String(rows[0]?.day ?? '')}</span><span>{String(rows[rows.length - 1]?.day ?? '')}</span></div>
    </div>
  )
}
