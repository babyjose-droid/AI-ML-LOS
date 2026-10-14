import { useState } from 'react'
import { api } from '../api'
import { ErrorBox, Head, Table, useLoad } from '../components/ui'
import { inr, pct } from '../format'

interface Branch { branchId: number; branch: string; score: number; applications: number; approvalRate?: number; disbursed: number; disbursedAmount: number; medianTatDays: number; bookOutstanding: number; par30: number; gnpa: number; promiseKeptRate?: number; parts: Record<string, number> }
interface Officer { officer: string; name: string; role?: string; score: number; sourced: number; submitted: number; disbursed: number; disbursedAmount: number; avgPd: number; earlyDelinquency: number; incompleteData: number; parts: Record<string, number> }

const Score = ({ s, parts }: { s: number; parts: Record<string, number> }) =>
  <span title={Object.entries(parts).map(([k, v]) => `${k} ${Math.round(v * 100)}`).join(' · ')} className={'pill ' + (s >= 70 ? 'ok' : s >= 50 ? 'warn' : 'bad')}>{s}</span>

/** Branch and officer scorecards. */
export default function Scores() {
  const [days, setDays] = useState(90)
  const b = useLoad(() => api.get<Branch[]>(`/api/scores/branches?days=${days}`), [days])
  const o = useLoad(() => api.get<Officer[]>(`/api/scores/officers?days=${days}`), [days])
  return (
    <>
      <Head title="Branch and officer scores" sub="Scores out of 100. Branches: book quality 40%, disbursals 25%, speed 20%, promises kept 15%. Officers: early delinquency 45%, conversion 25%, volume 15%, complete data 15%.">
        <select value={days} onChange={e => setDays(Number(e.target.value))} aria-label="Period">{[30, 90, 180, 365].map(d => <option key={d} value={d}>Last {d} days</option>)}</select>
      </Head>
      <ErrorBox error={b.error ?? o.error} />
      <h3>Branches</h3>
      <div className="panel flush">
        <Table head={['Branch', 'Score', 'Applications', 'Approved', 'Disbursed', 'Median days to disburse', 'Book', 'PAR 30+', 'Gross NPA', 'Promises kept']} rows={(b.data ?? []).map(x => [
          x.branch, <Score s={x.score} parts={x.parts} />, x.applications, x.approvalRate == null ? '—' : pct(x.approvalRate, 0), `${x.disbursed} · ${inr(x.disbursedAmount)}`, x.medianTatDays,
          inr(x.bookOutstanding), pct(x.par30, 1), pct(x.gnpa, 1), x.promiseKeptRate == null ? '—' : pct(x.promiseKeptRate, 0)])} empty={b.loading ? 'Loading…' : 'No branch activity in this period.'} />
      </div>
      <h3 style={{ marginTop: 16 }}>Officers</h3>
      <div className="panel flush">
        <Table head={['Officer', 'Score', 'Sourced', 'Submitted', 'Disbursed', 'Average PD', 'Early delinquency', 'Incomplete data']} rows={(o.data ?? []).map(x => [
          <span>{x.name}<div className="small muted">{x.officer}</div></span>, <Score s={x.score} parts={x.parts} />, x.sourced, x.submitted, `${x.disbursed} · ${inr(x.disbursedAmount)}`,
          pct(x.avgPd, 1), pct(x.earlyDelinquency, 1), x.incompleteData])} empty={o.loading ? 'Loading…' : 'No officer activity in this period.'} />
      </div>
    </>
  )
}
