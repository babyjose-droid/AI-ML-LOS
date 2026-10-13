import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'
import { ErrorBox, Head, Pill, useLoad } from '../components/ui'
import { REASON, inr, label } from '../format'

interface Card {
  applicationId: number; appNo: string; applicant: string; product: string; segment: string; amount: number
  decision?: string; riskBand?: string; pd?: number; score?: number; recommendedAmount?: number; rate?: number; foir?: number
  reasonCodes: string[]; deviations: string[]; fraudState: string; fieldState: string; level: string
  canAct: boolean; ageHours: number; slaHours: number; breached: boolean; sentBackBefore: boolean
}

/** SCR-051: cases waiting for a sanction decision, as case cards. */
export default function ReviewQueue() {
  const q = useLoad(() => api.get<Card[]>('/api/review-queue'), [])
  const [mine, setMine] = useState(true)
  const [level, setLevel] = useState('')
  const rows = (q.data ?? []).filter(c => (!mine || c.canAct) && (!level || c.level === level))
  return (
    <>
      <Head title="Review queue" sub="Cases waiting for a sanction decision: approve, approve with conditions, send back, escalate or decline.">
        <label className="small"><input type="checkbox" checked={mine} onChange={e => setMine(e.target.checked)} /> Only cases within my authority</label>
        <select value={level} onChange={e => setLevel(e.target.value)} aria-label="Level">
          <option value="">All levels</option><option>L1</option><option>L2</option><option>L3</option>
        </select>
      </Head>
      <ErrorBox error={q.error} />
      {q.data && rows.length === 0 && <div className="panel empty">Nothing waiting{mine ? ' within your authority' : ''}.</div>}
      <div className="cards" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))', gap: 12 }}>
        {rows.map(c => (
          <div key={c.applicationId} className="panel" data-testid="case-card" style={{ margin: 0, borderLeft: c.breached ? '4px solid var(--bad, #c0392b)' : undefined }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8 }}>
              <div><Link to={`/applications/${c.applicationId}`}><b>{c.applicant}</b></Link><div className="small muted">{c.appNo} · {c.product} · {label(c.segment)}</div></div>
              <div style={{ textAlign: 'right' }}><b>{inr(c.amount)}</b><div className="small muted">needs {c.level}</div></div>
            </div>
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', margin: '8px 0' }}>
              {c.decision && <Pill s={c.decision} />}
              {c.riskBand && <span className="pill">Band {c.riskBand}</span>}
              {c.fraudState !== 'CLEAR' && c.fraudState !== 'NOT_CHECKED' && <Pill s={'FRAUD_' + c.fraudState} />}
              {c.fieldState === 'REQUIRED' && <span className="pill warn">Field visit pending</span>}
              {c.sentBackBefore && <span className="pill warn">Sent back before</span>}
              <span className={'pill' + (c.breached ? ' bad' : '')}>{c.ageHours} h / SLA {c.slaHours} h</span>
            </div>
            <dl className="dl small">
              <dt>PD · score</dt><dd>{c.pd != null ? (c.pd * 100).toFixed(1) + '%' : '—'} · {c.score ?? '—'}</dd>
              <dt>Recommended</dt><dd>{c.recommendedAmount != null ? inr(c.recommendedAmount) : '—'}{c.rate != null && ` at ${c.rate}%`}</dd>
              <dt>FOIR after loan</dt><dd>{c.foir != null ? (c.foir * 100).toFixed(0) + '%' : '—'}</dd>
            </dl>
            {c.reasonCodes.length > 0 && <div className="small" style={{ marginTop: 6 }}>{c.reasonCodes.map(r => <span key={r} className="pill" title={REASON[r] ?? r} style={{ marginRight: 4 }}>{r}</span>)}</div>}
            {c.deviations.length > 0 && (
              <div className="small" style={{ marginTop: 6 }}><b>Deviations ({c.deviations.length})</b><ul style={{ margin: '4px 0', paddingLeft: 16 }}>{c.deviations.map(d => <li key={d}>{d}</li>)}</ul></div>
            )}
            <div style={{ marginTop: 8 }}><Link className="btn sm primary" to={`/applications/${c.applicationId}`}>Open case</Link></div>
          </div>
        ))}
      </div>
    </>
  )
}
