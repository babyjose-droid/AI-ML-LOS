import { useNavigate } from 'react-router-dom'
import { api } from '../api'
import { useAuth } from '../auth'
import { ErrorBox, Head, Table, useLoad } from '../components/ui'
import { inr } from '../format'
import type { Task } from '../types'

export default function MyWork() {
  const { user } = useAuth()
  const nav = useNavigate()
  const { data, error, loading } = useLoad(() => api.get<Task[]>('/api/my-work'), [])
  const tasks = data ?? []
  return (
    <>
      <Head title="My work" sub={`Tasks for ${user?.role.replace(/_/g, ' ').toLowerCase()}, worked out from each application's current state.`} />
      <ErrorBox error={error} />
      <div className="panel flush">
        {loading ? <div className="empty">Loading…</div> : (
          <Table
            head={['Application', 'Applicant', 'Amount', 'Task', 'Age', 'SLA']}
            rows={tasks.map(t => [
              t.appNo ?? '—', t.applicant ?? '—', t.amount ? inr(t.amount) : '—', <b>{t.task}</b>,
              <span className={t.ageHours > t.slaHours ? 'pill bad' : 'pill muted'}>{t.ageHours} h</span>, `${t.slaHours} h`,
            ])}
            onRow={i => tasks[i].applicationId ? nav('/applications/' + tasks[i].applicationId) : nav('/integrations')}
            empty="No tasks waiting for you."
          />
        )}
      </div>
    </>
  )
}
