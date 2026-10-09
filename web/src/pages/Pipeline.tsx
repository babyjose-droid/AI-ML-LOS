import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api'
import { useAuth } from '../auth'
import { ErrorBox, Head, Pill, Table, useLoad } from '../components/ui'
import { inr, lakh, when } from '../format'
import type { AppSummary } from '../types'

interface Summary { total: number; byState: Record<string, number>; pendingSanction: number; fraudReview: number; disbursedAmount: number; dlq: number }

export default function Pipeline() {
  const { user } = useAuth()
  const nav = useNavigate()
  const [state, setState] = useState('')
  const [q, setQ] = useState('')
  const list = useLoad(() => api.get<AppSummary[]>(`/api/applications?state=${state}&q=${encodeURIComponent(q)}`), [state, q])
  const sum = useLoad(() => api.get<Summary>('/api/pipeline/summary'), [])
  const apps = list.data ?? []
  const s = sum.data
  return (
    <>
      <Head title="Pipeline" sub="All applications with the state of each part of the process.">
        {user && ['SALES', 'OPERATIONS', 'ADMIN'].includes(user.role) && <Link className="btn primary" to="/applications/new">New application</Link>}
      </Head>
      {s && (
        <div className="kpis">
          <div className="kpi"><span>Applications</span><b>{s.total}</b></div>
          <div className="kpi"><span>Underwriting</span><b>{s.byState.UNDERWRITING ?? 0}</b></div>
          <div className="kpi"><span>Waiting for sanction</span><b>{s.pendingSanction}</b></div>
          <div className="kpi"><span>Fraud review</span><b>{s.fraudReview}</b></div>
          <div className="kpi"><span>Disbursed</span><b>{lakh(s.disbursedAmount)}</b></div>
          <div className="kpi"><span>Failed vendor calls</span><b>{s.dlq}</b></div>
        </div>
      )}
      <div className="row" style={{ marginBottom: 12 }}>
        <input style={{ maxWidth: 280 }} placeholder="Search name, PAN or application no." value={q} onChange={e => setQ(e.target.value)} />
        <select style={{ maxWidth: 200 }} value={state} onChange={e => setState(e.target.value)} aria-label="Filter by stage">
          <option value="">All stages</option>
          {['DRAFT', 'SUBMITTED', 'UNDERWRITING', 'SANCTIONED', 'DISBURSED', 'REJECTED', 'WITHDRAWN'].map(x => <option key={x} value={x}>{x.toLowerCase()}</option>)}
        </select>
      </div>
      <ErrorBox error={list.error} />
      <div className="panel flush">
        <Table
          head={['Application', 'Applicant', 'Product', 'Amount', 'Stage', 'KYC', 'Data', 'Docs', 'Decision', 'Sanction', 'Updated']}
          rows={apps.map(a => [
            <b>{a.appNo}</b>, a.applicantName, a.productCode, <span className="num">{inr(a.loanAmount)}</span>,
            <Pill s={a.states.APP} />, <Pill s={a.states.KYC} />, <Pill s={a.states.DATA} />, <Pill s={a.states.DOCS} />,
            <Pill s={a.states.DECISION} />, <Pill s={a.states.SANCTION} />, <span className="small muted">{when(a.updatedAt)}</span>,
          ])}
          onRow={i => nav('/applications/' + apps[i].id)}
          empty={list.loading ? 'Loading…' : 'No applications match.'}
        />
      </div>
    </>
  )
}
