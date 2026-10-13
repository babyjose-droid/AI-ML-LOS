import { Link } from 'react-router-dom'
import { api } from '../api'
import { ErrorBox, Head, useLoad } from '../components/ui'
import { lakh, pct } from '../format'

interface OverviewData {
  byState: Record<string, number>; newToday: number; pendingSanction: number; applications30d: number; decided30d: number
  approvalRate30d?: number; straightThroughRate30d?: number; medianTatHours?: number; disbursed30d: number; slaBreaches: number
  integrationsAwaitingRetry: number; integrationFailureRate24h?: number; policyVersion: string
}

/** SCR-001: the day at a glance for the user's branches. */
export default function Overview() {
  const o = useLoad(() => api.get<OverviewData>('/api/overview'), [])
  const d = o.data
  return (
    <>
      <Head title="Overview" sub="Your branches today and over the last 30 days." />
      <ErrorBox error={o.error} />
      {d && (
        <>
          <div className="kpis" data-testid="overview">
            <div className="kpi"><span>New today</span><b>{d.newToday}</b></div>
            <div className="kpi"><span>In underwriting</span><b>{d.byState.UNDERWRITING ?? 0}</b></div>
            <div className="kpi"><span>Waiting for sanction</span><b><Link to="/review-queue">{d.pendingSanction}</Link></b></div>
            <div className="kpi"><span>SLA breaches open</span><b className={d.slaBreaches ? 'bad-text' : ''}><Link to="/board">{d.slaBreaches}</Link></b></div>
            <div className="kpi"><span>Calls awaiting retry</span><b><Link to="/integrations">{d.integrationsAwaitingRetry}</Link></b></div>
          </div>
          <div className="kpis" style={{ marginTop: 12 }}>
            <div className="kpi"><span>Applications (30 days)</span><b>{d.applications30d}</b></div>
            <div className="kpi"><span>Approval rate</span><b>{d.approvalRate30d == null ? '—' : pct(d.approvalRate30d, 0)}</b><span>of {d.decided30d} decided</span></div>
            <div className="kpi"><span>Decided without a person</span><b>{d.straightThroughRate30d == null ? '—' : pct(d.straightThroughRate30d, 0)}</b></div>
            <div className="kpi"><span>Median submit → sanction</span><b>{d.medianTatHours == null ? '—' : `${d.medianTatHours} h`}</b></div>
            <div className="kpi"><span>Disbursed (30 days)</span><b>{lakh(d.disbursed30d)}</b></div>
          </div>
          <div className="panel" style={{ marginTop: 12 }}>
            <h3 style={{ marginTop: 0 }}>Applications by stage</h3>
            <div className="row">
              {Object.entries(d.byState).map(([k, v]) => <Link key={k} className="btn sm" to="/applications">{k.toLowerCase()} · {v}</Link>)}
            </div>
            <p className="small muted" style={{ marginBottom: 0 }}>
              Credit policy in force: {d.policyVersion}.{d.integrationFailureRate24h != null && ` Vendor call failures in the last 24 hours: ${pct(d.integrationFailureRate24h, 1)}.`}
            </p>
          </div>
        </>
      )}
    </>
  )
}
