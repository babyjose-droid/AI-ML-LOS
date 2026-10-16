import { useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { api } from '../api'
import { useAuth } from '../auth'
import { ErrorBox, Head, KpiStrip, Pill, Table, useLoad } from '../components/ui'
import { inr, label, lakh, when } from '../format'
import type { AppSummary } from '../types'

interface Summary { total: number; byState: Record<string, number>; pendingSanction: number; fraudReview: number; disbursedAmount: number; dlq: number }
interface Card { applicationId: number; appNo: string; applicant: string; product: string; amount: number; stage: string; role: string; status: string; dueAt?: string; breached: boolean; ageHours: number }
interface Column { key: string; name: string; count: number; breached: number; cards: Card[] }

/** SCR-020: the origination pipeline. Board by stage (as in the prototype) or a searchable list; typing a search shows the list. */
export default function Pipeline() {
  const { user } = useAuth()
  const nav = useNavigate()
  const [sp, setSp] = useSearchParams()
  const [state, setState] = useState('')
  const [q, setQ] = useState('')
  const [product, setProduct] = useState('')
  const view = q.trim() || state ? 'list' : sp.get('view') === 'list' ? 'list' : 'board'
  const setView = (v: string) => { const n = new URLSearchParams(sp); if (v === 'board') n.delete('view'); else n.set('view', v); setSp(n, { replace: true }) }
  const list = useLoad(() => view === 'list' ? api.get<AppSummary[]>(`/api/applications?state=${state}&q=${encodeURIComponent(q)}`) : Promise.resolve([] as AppSummary[]), [state, q, view])
  const board = useLoad(() => view === 'board' ? api.get<{ columns: Column[]; breached: number }>('/api/pipeline/board?product=' + product) : Promise.resolve(undefined), [product, view])
  const products = useLoad(() => api.get<{ code: string; name: string }[]>('/api/products'), [])
  const sum = useLoad(() => api.get<Summary>('/api/pipeline/summary'), [])
  const apps = (list.data ?? []).filter(a => !product || a.productCode === product)
  const s = sum.data
  return (
    <>
      <Head title="Origination pipeline" sub="Every live application, by stage. Cards turn red when a stage runs past its SLA. Open any card to see what AI did and what is left for people.">
        {user && ['SALES', 'OPERATIONS', 'ADMIN'].includes(user.role) && <Link className="btn primary" to="/applications/new">New application</Link>}
      </Head>
      {s && <KpiStrip items={[
        ['In flight', s.total, 'applications'],
        ['Underwriting', s.byState.UNDERWRITING ?? 0, 'with credit'],
        ['Waiting for sanction', s.pendingSanction, 'need a decision'],
        ['SLA breaches', board.data?.breached ?? '—', 'need attention', !!board.data?.breached],
        ['Fraud review', s.fraudReview, 'on hold'],
        ['Disbursed', lakh(s.disbursedAmount), `${s.dlq} failed vendor call(s)`],
      ]} />}
      <div className="chiprow" role="group" aria-label="Product">
        <button className={'chip-s' + (product === '' ? ' on' : '')} onClick={() => setProduct('')}>All</button>
        {(products.data ?? []).map(p => <button key={p.code} className={'chip-s' + (product === p.code ? ' on' : '')} onClick={() => setProduct(p.code)}>{p.name}</button>)}
      </div>
      <div className="toolbar" style={{ marginTop: 12 }}>
        <div className="row" style={{ marginTop: 0 }}>
          <input style={{ width: 280 }} placeholder="Search name, PAN or application no." value={q} onChange={e => setQ(e.target.value)} />
          <select style={{ width: 180 }} value={state} onChange={e => setState(e.target.value)} aria-label="Filter by stage">
            <option value="">All stages</option>
            {['DRAFT', 'SUBMITTED', 'UNDERWRITING', 'SANCTIONED', 'DISBURSED', 'REJECTED', 'WITHDRAWN'].map(x => <option key={x} value={x}>{x.toLowerCase()}</option>)}
          </select>
        </div>
        <div className="seg" role="group" aria-label="View">
          <button className={view === 'board' ? 'on' : ''} onClick={() => { setQ(''); setState(''); setView('board') }} data-view="board">Board</button>
          <button className={view === 'list' ? 'on' : ''} onClick={() => setView('list')} data-view="list">List</button>
        </div>
      </div>
      <ErrorBox error={list.error ?? board.error} />
      {view === 'board' ? (
        <div className="board" data-testid="pipeline-board">
          {(board.data?.columns ?? []).map(c => (
            <div key={c.key} className="col" data-column={c.key}>
              <div className="colh"><b>{c.name}</b><span className="muted">{c.count}{c.breached ? <span className="bad-text"> · {c.breached} late</span> : ''}</span></div>
              {c.cards.map(x => (
                <Link key={x.applicationId} to={`/applications/${x.applicationId}`} className={'kcard' + (x.breached ? ' late' : '')}>
                  <b>{x.applicant}</b>
                  <span className="small muted">{x.product}</span>
                  <span className="num">{inr(x.amount)}</span>
                  <span className="kmeta"><span className="num">{x.ageHours} h{x.breached ? ' · late' : ''}</span><span>{x.appNo}</span></span>
                  <span className="kflags"><span className="pill">{label(x.role)}</span>{x.status && x.status !== 'ACTIVE' && <Pill s={x.status} />}</span>
                </Link>
              ))}
              {c.cards.length === 0 && <span className="small muted" style={{ padding: 4 }}>Nothing here</span>}
            </div>
          ))}
          {board.loading && <div className="empty">Loading…</div>}
        </div>
      ) : (
        <div className="panel flush">
          <Table
            head={['Application', 'Applicant', 'Product', 'Amount', 'Stage', 'KYC', 'Data', 'Docs', 'Decision', 'Sanction', 'Updated']}
            rows={apps.map(a => [
              <b>{a.appNo}</b>, a.applicantName, <span>{a.productCode} <span className="small muted" data-wf-pin>{a.workflowVersion ? `v${a.workflowVersion}` : 'default'}</span></span>, <span className="num">{inr(a.loanAmount)}</span>,
              <Pill s={a.states.APP} />, <Pill s={a.states.KYC} />, <Pill s={a.states.DATA} />, <Pill s={a.states.DOCS} />,
              <Pill s={a.states.DECISION} />, <Pill s={a.states.SANCTION} />, <span className="small muted">{when(a.updatedAt)}</span>,
            ])}
            onRow={i => nav('/applications/' + apps[i].id)}
            empty={list.loading ? 'Loading…' : 'No applications match.'}
          />
        </div>
      )}
    </>
  )
}
