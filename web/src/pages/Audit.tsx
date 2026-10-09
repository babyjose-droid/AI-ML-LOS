import { useState } from 'react'
import { api } from '../api'
import { ErrorBox, Head, Table, useLoad } from '../components/ui'
import { when } from '../format'
import type { AuditEvent } from '../types'

export default function Audit() {
  const [appId, setAppId] = useState('')
  const a = useLoad(() => api.get<AuditEvent[]>('/api/audit' + (appId ? '?applicationId=' + appId : '')), [appId])
  return (
    <>
      <Head title="Audit trail" sub="Append-only record of every action: who did what, when, and the details." />
      <div className="row" style={{ marginBottom: 12 }}>
        <input style={{ maxWidth: 240 }} placeholder="Filter by application id" value={appId} onChange={e => setAppId(e.target.value.replace(/\D/g, ''))} />
      </div>
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
