import { useState } from 'react'
import { api } from '../api'
import { useAuth } from '../auth'
import { ErrorBox, Head, Table, useLoad } from '../components/ui'
import { when } from '../format'
import type { AuditEvent } from '../types'

export default function Audit() {
  const [appId, setAppId] = useState('')
  const { user } = useAuth()
  const today = new Date().toISOString().slice(0, 10)
  const [ex, setEx] = useState({ from: new Date(Date.now() - 30 * 864e5).toISOString().slice(0, 10), to: today, action: '' })
  const [exErr, setExErr] = useState<unknown>()
  async function download() {
    setExErr(undefined)
    let token: string | null = null
    try { token = localStorage.getItem('rhythm_token') } catch { /* ignore */ }
    const q = new URLSearchParams({ from: ex.from, to: ex.to, ...(ex.action ? { action: ex.action } : {}), ...(appId ? { applicationId: appId } : {}) })
    const r = await fetch('/api/audit/export?' + q, { headers: token ? { Authorization: 'Bearer ' + token } : {} })
    if (!r.ok) { setExErr(new Error((await r.json().catch(() => ({})))?.message ?? 'Export failed')); return }
    const url = URL.createObjectURL(await r.blob())
    const link = document.createElement('a')
    link.href = url
    link.download = `rhythm-audit-${ex.from}-to-${ex.to}.csv`
    link.click()
    URL.revokeObjectURL(url)
  }
  const a = useLoad(() => api.get<AuditEvent[]>('/api/audit' + (appId ? '?applicationId=' + appId : '')), [appId])
  return (
    <>
      <Head title="Audit trail" sub="Append-only record of every action: who did what, when, and the details." />
      <div className="row" style={{ marginBottom: 12 }}>
        <input style={{ maxWidth: 240 }} placeholder="Filter by application id" value={appId} onChange={e => setAppId(e.target.value.replace(/\D/g, ''))} />
      </div>
      {user && ['ADMIN', 'COMPLIANCE', 'CRO'].includes(user.role) && (
        <div className="panel">
          <div className="form">
            <label className="fld">From<input type="date" value={ex.from} onChange={e => setEx({ ...ex, from: e.target.value })} /></label>
            <label className="fld">To<input type="date" value={ex.to} onChange={e => setEx({ ...ex, to: e.target.value })} /></label>
            <label className="fld">Action starts with (optional)<input placeholder="sanction." value={ex.action} onChange={e => setEx({ ...ex, action: e.target.value })} /></label>
            <button className="btn primary" data-action="auditExport" onClick={download}>Export CSV</button>
          </div>
          <ErrorBox error={exErr} />
        </div>
      )}
      <ErrorBox error={a.error} />
      <div className="panel flush">
        <Table head={['When', 'Actor', 'Role', 'Action', 'Entity', 'Details']} rows={(a.data ?? []).map(x => [
          <span className="small">{when(x.createdAt)}</span>, x.actor, <span className="small muted">{x.actorRole}</span>,
          <span className="mono">{x.action}</span>, <span className="small">{x.entityType} {x.entityId ?? ''}</span>, <span className="small">{x.details}</span>,
        ])} empty={a.loading ? 'Loading…' : 'No events.'} />
      </div>
    </>
  )
}
