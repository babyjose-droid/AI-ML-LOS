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
  const [picked, setPicked] = useState<number[]>([])
  const RETRY = ['DLQ', 'CIRCUIT_OPEN', 'DISABLED']

  async function bulk() {
    setError(undefined)
    try {
      const r = await api.post<{ id: number; ok: boolean; error?: string }[]>('/api/integrations/logs/retry-bulk', { ids: picked })
      setMsg(`${r.filter(x => x.ok).length} of ${r.length} retried successfully` + (r.some(x => !x.ok) ? ` · failed: ${r.filter(x => !x.ok).map(x => `#${x.id} ${x.error}`).join('; ')}` : ''))
      setPicked([])
      setRev(x => x + 1)
    } catch (e) { setError(e) }
  }

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
      <Head title="Integration hub" sub="Every connector with its settings and health, and every vendor call (one row per attempt, with its correlation ID). Failed calls are retried with backoff; after the last attempt they go to the dead-letter queue (DLQ). Repeated outages open the circuit so calls fail fast until the cool-down." />
      <Connectors rev={rev} onChange={() => setRev(x => x + 1)} />
      <div className="row" style={{ marginBottom: 12 }}>
        <select style={{ maxWidth: 220 }} value={status} onChange={e => setStatus(e.target.value)} aria-label="Status">
          <option value="">All statuses</option><option value="DLQ">Dead-letter queue</option><option value="FAILED">Failed attempts</option>
          <option value="SUCCESS">Success</option><option value="RESOLVED">Resolved</option>
          <option value="CIRCUIT_OPEN">Not sent: circuit open</option><option value="DISABLED">Not sent: connector off</option>
        </select>
        {canRetry && picked.length > 0 && <button className="btn primary" data-action="bulkRetry" onClick={bulk}>Retry {picked.length} selected</button>}
      </div>
      <ErrorBox error={error} />
      {msg && <div className="alert ok">{msg}</div>}
      <div className="panel flush">
        <Table head={['', 'When', 'Application', 'Vendor', 'Operation', 'Correlation ID', 'Attempt', 'Status', 'Latency', 'Result', '']} rows={rows.map(x => [
          canRetry && RETRY.includes(x.status) ? <input type="checkbox" aria-label={'Select call ' + x.id} checked={picked.includes(x.id)}
            onChange={e => setPicked(e.target.checked ? [...picked, x.id] : picked.filter(y => y !== x.id))} /> : '',
          <span className="small">{when(x.createdAt)}</span>,
          x.applicationId ? <Link to={'/applications/' + x.applicationId}>#{x.applicationId}</Link> : '—',
          x.vendor, <span className="mono">{x.operation}</span>, <span className="mono small">{x.requestRef}</span>, x.attempt, <Pill s={x.status} />, `${x.latencyMs} ms`,
          <span className="small">{x.responseSummary ?? x.errorMessage}</span>,
          RETRY.includes(x.status) && canRetry ? <button className="btn sm primary" data-retry={x.id} onClick={() => retry(x.id)}>Retry</button> : '',
        ])} empty={logs.loading ? 'Loading…' : 'No calls.'} />
      </div>
    </>
  )
}

interface ConnectorRow {
  config: { connector: string; name: string; mode: string; enabled: boolean; timeoutMs: number; maxAttempts: number; breakerThreshold: number; breakerCooldownS: number; baseUrl?: string; notes?: string; updatedBy?: string }
  breaker: { state: string; consecutiveFailures: number; openUntil?: string }
  calls24h: number; success24h: number; failed24h: number; awaitingRetry: number; avgLatencyMs: number; successRate?: number
}

function Connectors({ rev, onChange }: { rev: number; onChange: () => void }) {
  const { user } = useAuth()
  const c = useLoad(() => api.get<ConnectorRow[]>('/api/integrations/connectors'), [rev])
  const [edit, setEdit] = useState<ConnectorRow['config'] | null>(null)
  const [error, setError] = useState<unknown>()
  const admin = user?.role === 'ADMIN'
  async function save() {
    try {
      setError(undefined)
      const e = edit!
      await api.put('/api/admin/connectors/' + e.connector, { enabled: e.enabled, timeoutMs: Number(e.timeoutMs), maxAttempts: Number(e.maxAttempts),
        breakerThreshold: Number(e.breakerThreshold), breakerCooldownS: Number(e.breakerCooldownS), baseUrl: e.baseUrl ?? '', notes: e.notes ?? '' })
      setEdit(null); onChange()
    } catch (x) { setError(x) }
  }
  async function reset(k: string) {
    try { setError(undefined); await api.post(`/api/admin/connectors/${k}/reset-breaker`); onChange() } catch (x) { setError(x) }
  }
  const num = (k: keyof ConnectorRow['config'], lbl: string) => (
    <label className="fld">{lbl}<input type="number" value={String(edit![k] ?? '')} onChange={e => setEdit({ ...edit!, [k]: e.target.value })} /></label>
  )
  return (
    <div className="panel flush" data-testid="connectors" style={{ marginBottom: 12 }}>
      <ErrorBox error={c.error ?? error} />
      <Table head={['Connector', 'Mode', 'Status', 'Circuit', 'Last 24 h', 'Avg latency', 'Timeout · attempts', '']} rows={(c.data ?? []).map(x => [
        <span><b>{x.config.name}</b><div className="small muted mono">{x.config.connector}</div></span>,
        <span className="pill">{x.config.mode === 'MOCK' ? 'Mock' : 'Live'}</span>,
        <Pill s={x.config.enabled ? 'SUCCESS' : 'WITHDRAWN'} />,
        <span className={'pill' + (x.breaker.state === 'OPEN' ? ' bad' : x.breaker.state === 'HALF_OPEN' ? ' warn' : '')}>{x.breaker.state.replace('_', '-').toLowerCase()}{x.breaker.consecutiveFailures ? ` · ${x.breaker.consecutiveFailures} failed` : ''}</span>,
        <span className="small">{x.calls24h} calls{x.successRate != null && ` · ${Math.round(x.successRate * 100)}% ok`}{x.awaitingRetry ? ` · ${x.awaitingRetry} to retry` : ''}</span>,
        `${x.avgLatencyMs} ms`, <span className="small">{x.config.timeoutMs} ms · {x.config.maxAttempts}</span>,
        admin ? <div className="row"><button className="btn sm" onClick={() => setEdit({ ...x.config })}>Settings</button>{x.breaker.state !== 'CLOSED' && <button className="btn sm" onClick={() => reset(x.config.connector)}>Close circuit</button>}</div> : '',
      ])} />
      {edit && (
        <div className="form" style={{ padding: 12 }}>
          <label className="check"><input type="checkbox" checked={edit.enabled} onChange={e => setEdit({ ...edit, enabled: e.target.checked })} /> {edit.name} switched on</label>
          {num('timeoutMs', 'Timeout (ms)')}{num('maxAttempts', 'Attempts')}{num('breakerThreshold', 'Open circuit after failed calls')}{num('breakerCooldownS', 'Cool-down (s)')}
          <label className="fld wide">Base URL (live mode, https)<input value={edit.baseUrl ?? ''} onChange={e => setEdit({ ...edit, baseUrl: e.target.value })} /></label>
          <label className="fld wide">Notes<input value={edit.notes ?? ''} onChange={e => setEdit({ ...edit, notes: e.target.value })} /></label>
          <div className="row"><button className="btn primary" onClick={save}>Save</button><button className="btn" onClick={() => setEdit(null)}>Cancel</button></div>
        </div>
      )}
    </div>
  )
}
