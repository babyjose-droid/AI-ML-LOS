import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'
import { ErrorBox, Head, useLoad } from '../components/ui'
import { inr, label } from '../format'

interface Card { applicationId: number; appNo: string; applicant: string; product: string; amount: number; stage: string; role: string; status: string; dueAt?: string; breached: boolean; ageHours: number }
interface Column { key: string; name: string; count: number; breached: number; cards: Card[] }

/** SCR-020: open applications by the stage they are in, with SLA and breaches. */
export default function Board() {
  const [product, setProduct] = useState('')
  const b = useLoad(() => api.get<{ columns: Column[]; breached: number }>('/api/pipeline/board?product=' + product), [product])
  const products = useLoad(() => api.get<{ code: string }[]>('/api/products'), [])
  return (
    <>
      <Head title="Stage board" sub="Every open application in the stage it is in now. Red cards are past their stage SLA (holidays do not count).">
        <select value={product} onChange={e => setProduct(e.target.value)} aria-label="Product">
          <option value="">All products</option>
          {(products.data ?? []).map(p => <option key={p.code} value={p.code}>{p.code}</option>)}
        </select>
      </Head>
      <ErrorBox error={b.error} />
      {b.data && <p className="small">{b.data.breached} application(s) past SLA.</p>}
      <div className="board" style={{ display: 'grid', gridAutoFlow: 'column', gridAutoColumns: 'minmax(220px, 1fr)', gap: 10, overflowX: 'auto', paddingBottom: 8 }}>
        {(b.data?.columns ?? []).map(c => (
          <div key={c.key} className="panel" style={{ margin: 0, padding: 10, minHeight: 120 }} data-column={c.key}>
            <div style={{ display: 'flex', justifyContent: 'space-between' }}><b>{c.name}</b><span className="small">{c.count}{c.breached ? <span className="bad-text"> · {c.breached} late</span> : ''}</span></div>
            {c.cards.map(x => (
              <Link key={x.applicationId} to={`/applications/${x.applicationId}`} className="card" style={{ display: 'block', marginTop: 8, padding: 8, borderRadius: 8, border: '1px solid var(--line, #ddd)', borderLeft: x.breached ? '4px solid var(--bad, #c0392b)' : undefined, color: 'inherit', textDecoration: 'none' }}>
                <div><b>{x.applicant}</b></div>
                <div className="small muted">{x.appNo} · {x.product} · {inr(x.amount)}</div>
                <div className="small">{label(x.role)} · {x.ageHours} h{x.dueAt && ` · due ${new Date(x.dueAt).toLocaleString()}`}</div>
              </Link>
            ))}
          </div>
        ))}
      </div>
    </>
  )
}
