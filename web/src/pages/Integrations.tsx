import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'
import { useAuth } from '../auth'
import { ErrorBox, Head, Pill, Table, useLoad } from '../components/ui'
import { when } from '../format'
import type { IntegrationLog } from '../types'

export default function Integrations() {
  const { user } = useAuth()
  const [status, setStatus] = useState('')
  const [rev, setRev] = useState(0)
  const [error, setError] = useState<unknown>()
  const [msg, setMsg] = useState<string>()
  const logs = useLoad(() => api.get<IntegrationLog[]>('/api/integrations/logs?status=' + status), [status, rev])
  const canRetry = user && ['ADMIN', 'OPERATIONS'].includes(user.role)

  async function retry(id: number) {
    setError(undefined)
    try {
      await api.post(`/api/integrations/logs/${id}/retry`)
      setMsg(`Call ${id} retried`)
      setRev(r => r + 1)
    } catch (e) { setError(e) }
  }

  const rows = logs.data ?? []
  return (
    <>
      <Head title="Integration logs" sub="Every vendor call, one row per attempt. Failed calls are retried automatically with backoff; after the last attempt they go to the dead-letter queue (DLQ) and can be retried here." />
      <div className="row" style={{ marginBottom: 12 }}>
        <select style={{ maxWidth: 220 }} value={status} onChange={e => setStatus(e.target.value)} aria-label="Status">
          <option value="">All statuses</option><option value="DLQ">Dead-letter queue</option><option value="FAILED">Failed attempts</option>
          <option value="SUCCESS">Success</option><option value="RESOLVED">Resolved</option>
        </select>
      </div>
      <ErrorBox error={error} />
      {msg && <div className="alert ok">{msg}</div>}
      <div className="panel flush">
        <Table head={['When', 'Application', 'Vendor', 'Operation', 'Attempt', 'Status', 'Latency', 'Result', '']} rows={rows.map(x => [
          <span className="small">{when(x.createdAt)}</span>,
          x.applicationId ? <Link to={'/applications/' + x.applicationId}>#{x.applicationId}</Link> : '—',
          x.vendor, <span className="mono">{x.operation}</span>, x.attempt, <Pill s={x.status} />, `${x.latencyMs} ms`,
          <span className="small">{x.responseSummary ?? x.errorMessage}</span>,
          x.status === 'DLQ' && canRetry ? <button className="btn sm primary" data-retry={x.id} onClick={() => retry(x.id)}>Retry</button> : '',
        ])} empty={logs.loading ? 'Loading…' : 'No calls.'} />
      </div>
    </>
  )
}
