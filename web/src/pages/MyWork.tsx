import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api'
import { useAuth } from '../auth'
import { DOMAIN_LABEL, ErrorBox, Head, KpiStrip, Pill, useLoad } from '../components/ui'
import { inr } from '../format'
import type { AppSummary, Task } from '../types'

const roleName = (r: string) => r === 'CRO' ? 'CRO' : r.replace(/_/g, ' ').toLowerCase().replace(/^./, c => c.toUpperCase())
const BOARD = ['APP', 'KYC', 'DOCS', 'FIELD', 'DECISION', 'SANCTION', 'DISB', 'FRAUD']

/** Where a task opens: the application (on the right tab), a collateral case, or the admin screen it belongs to. */
function target(t: Task) {
  if (t.action.startsWith('collateralReview:')) return '/collateral/' + t.action.split(':')[1]
  if (t.applicationId) return '/applications/' + t.applicationId + (t.action === 'parties' ? '?tab=Parties' : '')
  return t.action === 'policy' ? '/admin/policy' : t.action === 'workflow' ? '/admin/workflows' : '/integrations'
}

/** SCR-091: tasks worked out from each application's state, as cards with the application's state board (prototype v3.1). */
export default function MyWork() {
  const { user } = useAuth()
  const nav = useNavigate()
  const { data, error, loading } = useLoad(() => api.get<Task[]>('/api/my-work'), [])
  const apps = useLoad(() => api.get<AppSummary[]>('/api/applications?state=&q='), [])
  const tasks = data ?? []
  const byId = new Map((apps.data ?? []).map(a => [a.id, a]))
  const late = tasks.filter(t => t.ageHours > t.slaHours).length
  const working = new Set(tasks.map(t => t.applicationId).filter(Boolean)).size
  const canCreate = user && ['SALES', 'OPERATIONS', 'ADMIN'].includes(user.role)
  return (
    <>
      <Head title="My work" sub={`Tasks for ${user ? roleName(user.role).toLowerCase() : ''}, worked out from each application's current state, so they appear and disappear as states change.`}>
        {canCreate && <Link className="btn" to="/applications/new" data-action="start-application">Start an application</Link>}
      </Head>
      <ErrorBox error={error} />
      <KpiStrip items={[
        ['Open tasks', loading ? '…' : tasks.length],
        ['Working applications', loading ? '…' : working],
        ['Past SLA', loading ? '…' : late, late ? 'need attention' : 'all on time', late > 0],
        ['Due within SLA', loading ? '…' : tasks.length - late],
      ]} />
      {loading && <div className="empty">Loading…</div>}
      {!loading && tasks.length === 0 && <div className="panel empty">No tasks waiting for you.</div>}
      {tasks.map((t, i) => {
        const a = t.applicationId ? byId.get(t.applicationId) : undefined
        const isLate = t.ageHours > t.slaHours
        return (
          <div key={i} className="panel task clickable" data-task={t.action} onClick={e => { if (!(e.target as HTMLElement).closest('a,button')) nav(target(t)) }}>
            <div className="taskh">
              <div style={{ minWidth: 0 }}>
                <h3><span className="rolepill">{user ? roleName(user.role) : ''}</span>{t.task}</h3>
                <div className="small muted" style={{ marginTop: 4 }}>
                  {[t.appNo, t.applicant, t.product, t.amount ? inr(t.amount) : null].filter(Boolean).join(' · ')}
                  {' · '}<span className={isLate ? 'bad-text' : ''}>{t.ageHours} h of {t.slaHours} h SLA{isLate ? ' · late' : ''}</span>
                </div>
              </div>
              {t.applicationId && <Link to={'/applications/' + t.applicationId} className="small">Application 360</Link>}
            </div>
            {a && (
              <div className="sboard" data-testid="task-states">
                {BOARD.filter(d => a.states[d as keyof typeof a.states]).map(d => <div key={d}>{DOMAIN_LABEL[d]}<Pill s={String(a.states[d as keyof typeof a.states])} /></div>)}
              </div>
            )}
            <div className="row">
              <button className="btn primary sm" onClick={() => nav(target(t))}>Open task</button>
            </div>
          </div>
        )
      })}
    </>
  )
}
