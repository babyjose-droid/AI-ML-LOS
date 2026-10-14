import { useState } from 'react'
import { api } from '../api'
import { ErrorBox, Head, Table, useLoad } from '../components/ui'
import { pct } from '../format'

interface Group { group: string; decisions: number; approved: number; approvalRate: number; adverseImpactRatio?: number; avgRateApproved?: number; avgPd?: number; status: string }
interface Dim { key: string; name: string; groups: Group[]; flagged: number }
interface Report { days: number; decisions: number; approvalRate?: number; dimensions: Dim[]; flags: number; modelInputs: string[] }

/** Fairness monitoring: outcomes by gender, age, region and segment, with the four-fifths rule. */
export default function Fairness() {
  const [days, setDays] = useState(90)
  const r = useLoad(() => api.get<Report>('/api/fairness?days=' + days), [days])
  const d = r.data
  return (
    <>
      <Head title="Fairness monitoring" sub="Approval rate, price and risk by group on the latest decision of each application. A group whose approval rate is below 80% of the best group's (adverse impact ratio under 0.80) is flagged for review.">
        <select value={days} onChange={e => setDays(Number(e.target.value))} aria-label="Period">
          {[30, 90, 180, 365].map(x => <option key={x} value={x}>Last {x} days</option>)}
        </select>
      </Head>
      <ErrorBox error={r.error} />
      {d && (
        <>
          <div className="kpis" data-testid="fairness">
            <div className="kpi"><span>Decisions</span><b>{d.decisions}</b></div>
            <div className="kpi"><span>Approval rate</span><b>{d.approvalRate == null ? '—' : pct(d.approvalRate, 0)}</b></div>
            <div className="kpi"><span>Groups flagged</span><b className={d.flags ? 'bad-text' : ''}>{d.flags}</b></div>
          </div>
          {d.dimensions.map(dim => (
            <div className="panel flush" key={dim.key} style={{ marginTop: 12 }} data-dimension={dim.key}>
              <h3 style={{ padding: '12px 12px 0' }}>{dim.name}</h3>
              <Table head={['Group', 'Decisions', 'Approval rate', 'Adverse impact ratio', 'Average rate (approved)', 'Average PD', '']} rows={dim.groups.map(g => [
                g.group, g.decisions, pct(g.approvalRate, 0), g.adverseImpactRatio == null ? '—' : g.adverseImpactRatio.toFixed(2),
                g.avgRateApproved == null ? '—' : g.avgRateApproved + '%', g.avgPd == null ? '—' : pct(g.avgPd, 1),
                g.status === 'REVIEW' ? <span className="pill bad">Review</span> : g.status === 'TOO_FEW' ? <span className="pill muted">Under 30: not judged</span> : <span className="pill ok">OK</span>,
              ])} />
            </div>
          ))}
          <p className="small muted" style={{ marginTop: 12 }}>Gender, age and region are not inputs to the risk model. The model uses only: {d.modelInputs.join(', ')}. Outcomes are still checked here because other inputs can stand in for a protected attribute. A flag is a prompt to investigate, not proof of bias.</p>
        </>
      )}
    </>
  )
}
