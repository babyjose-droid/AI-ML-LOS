import { useEffect, useState } from 'react'
import { api, getToken } from '../api'
import { useAuth } from '../auth'
import { Link } from 'react-router-dom'
import { ErrorBox, Modal, Pill, Table, useLoad } from '../components/ui'
import { inr, when } from '../format'
import type { Consent, MemoDeviation, MemoView } from '../types'

const SOURCE: Record<string, string> = { KYC: 'Identity (KYC)', AA: 'Bank data (Account Aggregator)', BUREAU: 'Credit bureau', GST: 'GST returns', COMMUNICATION: 'Messages' }
const LANG: Record<string, string> = { en: 'English', hi: 'Hindi', mr: 'Marathi', ta: 'Tamil', ml: 'Malayalam', kn: 'Kannada', te: 'Telugu', bn: 'Bengali', gu: 'Gujarati' }

/** Consent ledger (BR-04): what the borrower agreed to, for how long, and withdrawal with its recorded effect. */
export function ConsentPanel({ id, rev, onChange }: { id: string; rev: number; onChange: () => void }) {
  const { user } = useAuth()
  const list = useLoad(() => api.get<Consent[]>(`/api/applications/${id}/consents`), [id, rev])
  const [w, setW] = useState<Consent | null>(null)
  const [note, setNote] = useState('')
  const [err, setErr] = useState<unknown>(null)
  const front = ['SALES', 'OPERATIONS', 'ADMIN'].includes(user?.role ?? '')
  async function withdraw() {
    try {
      setErr(null)
      await api.post(`/api/applications/${id}/consents/${w!.source}/withdraw`, { note })
      setW(null); setNote(''); onChange()
    } catch (e) { setErr(e) }
  }
  const rows = list.data ?? []
  return (
    <div className="panel" style={{ marginTop: 12 }} data-testid="consents">
      <h3>Consents</h3>
      <ErrorBox error={list.error} />
      <Table head={['Source', 'Purpose', 'Scope', 'Channel · language', 'Given', 'Valid until', 'Status', '']} rows={rows.map(c => [
        SOURCE[c.source] ?? c.source, <span className="small">{c.purpose}</span>, <span className="small">{c.scope}</span>,
        <span className="small">{c.channel} · {LANG[c.language] ?? c.language}</span>, when(c.grantedAt), c.validUntil ? when(c.validUntil) : 'Until withdrawn',
        <span title={c.effect ?? ''}><Pill s={(c as Consent & { state?: string }).state ?? c.status} />{c.withdrawnAt && <div className="small muted">by {c.withdrawnBy} · {when(c.withdrawnAt)}</div>}</span>,
        front && c.status === 'GRANTED' ? <button className="btn small" data-action={'withdraw-' + c.source} onClick={() => setW(c)}>Withdraw</button> : '',
      ])} empty="Consents are recorded when the application is submitted." />
      {w && (
        <Modal title={`Withdraw ${SOURCE[w.source] ?? w.source} consent`} onClose={() => setW(null)}>
          <ErrorBox error={err} />
          <p className="small muted">After withdrawal: no further pulls from this source. Data already held is used only for this application. Disbursement is blocked if KYC, bank or bureau consent is no longer valid.</p>
          <label className="fld">How did the borrower ask? (at least 10 characters)<textarea name="note" rows={3} value={note} onChange={e => setNote(e.target.value)} /></label>
          <button className="btn danger" onClick={withdraw}>Withdraw consent</button>
        </Modal>
      )}
    </div>
  )
}

/** Credit memo (FSD 12.2): edit, justify each deviation, sign and lock; versions and numbers not found in the file. */
export function CreditMemoPanel({ id, rev, onChange }: { id: string; rev: number; onChange: () => void }) {
  const [n, setN] = useState(0)
  const v = useLoad(() => api.get<MemoView>(`/api/applications/${id}/memo`), [id, rev, n])
  const [body, setBody] = useState('')
  const [devs, setDevs] = useState<MemoDeviation[]>([])
  const [editing, setEditing] = useState(false)
  const [err, setErr] = useState<unknown>(null)
  const [busy, setBusy] = useState(false)
  useEffect(() => { if (v.data?.memo) { setBody(v.data.memo.body); setDevs(v.data.deviations) } }, [v.data])
  if (v.error) return <ErrorBox error={v.error} />
  const d = v.data
  if (!d) return <div className="panel empty">Loading…</div>
  if (!d.memo) return <div className="panel empty">The credit memo is drafted when the decision engine reaches a decision.</div>
  const m = d.memo
  async function call(fn: () => Promise<unknown>) {
    setBusy(true); setErr(null)
    try { await fn(); setEditing(false); setN(x => x + 1); onChange() } catch (e) { setErr(e) } finally { setBusy(false) }
  }
  const save = () => call(() => api.put(`/api/applications/${id}/memo`, { body, deviations: devs }))
  const sign = () => call(async () => {
    if (editing) await api.put(`/api/applications/${id}/memo`, { body, deviations: devs })
    await api.post(`/api/applications/${id}/memo/sign`, {})
  })
  return (
    <>
      <div className="panel" data-testid="memo">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
          <h3 style={{ margin: 0 }}>Credit memo v{m.version} <Pill s={m.status} /></h3>
          <span className="small muted">
            {m.status === 'SIGNED' ? `Signed by ${m.signedBy} (${m.signerRole}) · ${when(m.signedAt!)} · locked` : `Last edited by ${m.editedBy}${m.editedAt ? ' · ' + when(m.editedAt) : ''}`}
            {d.signRequired && m.status !== 'SIGNED' && ' · Sanction waits for this memo to be signed'}
          </span>
        </div>
        <ErrorBox error={err} />
        {d.unsourcedNumbers.length > 0 && (
          <div className="alert warn small" style={{ marginTop: 8 }}>Numbers not found in the file: {d.unsourcedNumbers.join(', ')}. Check them against the data before signing.</div>
        )}
        {editing
          ? <textarea name="memoBody" rows={18} style={{ width: '100%', fontFamily: 'inherit', marginTop: 8 }} value={body} onChange={e => setBody(e.target.value)} />
          : <pre className="memo">{m.body}</pre>}
      </div>
      <div className="panel">
        <h3>Deviations</h3>
        {devs.length === 0 ? <p className="small muted">No policy deviations: every rule passed or was decided automatically.</p> : devs.map((x, i) => (
          <div key={x.rule} style={{ marginBottom: 10 }}>
            <div><b>{x.rule}</b> · {x.description}</div>
            {d.canEdit
              ? <textarea name={'justify-' + x.rule} rows={2} style={{ width: '100%' }} placeholder="Why this deviation is acceptable (at least 10 characters)" value={x.justification}
                  onChange={e => { const c = [...devs]; c[i] = { ...x, justification: e.target.value }; setDevs(c); setEditing(true) }} />
              : <div className="small">{x.justification || <span className="muted">No justification yet</span>}</div>}
          </div>
        ))}
        {d.canEdit && (
          <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
            {!editing && <button className="btn" onClick={() => setEditing(true)}>Edit memo</button>}
            {editing && <button className="btn" disabled={busy} onClick={save}>Save as new version</button>}
            <button className="btn primary" data-action="signMemo" disabled={busy} onClick={sign}>Sign and lock</button>
          </div>
        )}
      </div>
      <div className="panel flush">
        <Table head={['Version', 'Status', 'Edited by', 'When', 'Signed by']} rows={d.history.map(h => [h.version, <Pill s={h.status} />, h.editedBy, h.editedAt ? when(h.editedAt) : '—', h.signedBy || '—'])} />
      </div>
    </>
  )
}

interface Customer {
  name: string; pan: string; mobile: string; city?: string; segment: string; language?: string; disbursedTotal: number; exposure: number; otherPansOnMobile: string[]
  applications: { applicationId: number; appNo: string; product: string; amount: number; state: string; createdAt: string; decision?: string; band?: string; disbursed: number; samePan: boolean }[]
}

/** Customer 360 (SCR-031): the borrower across applications on the same PAN or mobile number. */
export function CustomerPanel({ id, rev }: { id: string; rev: number }) {
  const c = useLoad(() => api.get<Customer>(`/api/applications/${id}/customer`), [id, rev])
  if (!c.data) return <ErrorBox error={c.error} />
  const d = c.data
  return (
    <>
      <div className="kpis" data-testid="customer360">
        <div className="kpi"><span>Customer</span><b>{d.name}</b><span className="mono">{d.pan} · {d.mobile}</span></div>
        <div className="kpi"><span>Applications</span><b>{d.applications.length}</b></div>
        <div className="kpi"><span>Disbursed so far</span><b>{inr(d.disbursedTotal)}</b></div>
        <div className="kpi"><span>Exposure (open + disbursed)</span><b>{inr(d.exposure)}</b></div>
      </div>
      {d.otherPansOnMobile.length > 0 && <div className="alert warn small" style={{ marginTop: 10 }}>Other PANs use this mobile number: {d.otherPansOnMobile.join(', ')}. The fraud check counts these links.</div>}
      <NetworkPanel id={id} rev={rev} />
      <HouseholdPanel id={id} rev={rev} />
      <div className="panel flush" style={{ marginTop: 10 }}>
        <Table head={['Application', 'Product', 'Amount', 'Stage', 'Decision', 'Disbursed', 'Created', 'Match']} rows={d.applications.map(x => [
          <Link to={`/applications/${x.applicationId}`}>{x.appNo}</Link>, x.product, inr(x.amount), <Pill s={x.state} />,
          x.decision ? <span><Pill s={x.decision} />{x.band && <span className="small"> band {x.band}</span>}</span> : '—',
          x.disbursed ? inr(x.disbursed) : '—', when(x.createdAt), x.samePan ? 'Same PAN' : 'Same mobile',
        ])} />
      </div>
    </>
  )
}

interface NetNode { id: number; appNo: string; name: string; state: string; fraudState: string; self: boolean; visible: boolean }
interface Net { score: number; factors: string[]; nodes: NetNode[]; edges: { from: number; to: number; via: string }[] }

/** Network risk: applications linked by mobile, bank account, device, email or address with another PAN. */
export function NetworkPanel({ id, rev }: { id: string; rev: number }) {
  const n = useLoad(() => api.get<Net>(`/api/applications/${id}/network`), [id, rev])
  if (!n.data) return <ErrorBox error={n.error} />
  const d = n.data
  const W = 520, H = 260, cx = W / 2, cy = H / 2
  const others = d.nodes.filter(x => !x.self)
  const pos: Record<number, [number, number]> = {}
  d.nodes.forEach(x => { if (x.self) pos[x.id] = [cx, cy] })
  others.forEach((x, i) => { const a = (2 * Math.PI * i) / Math.max(1, others.length); pos[x.id] = [cx + Math.cos(a) * 190, cy + Math.sin(a) * 95] })
  const color = (x: NetNode) => x.fraudState === 'BLOCK' ? 'var(--bad, #c0392b)' : x.fraudState === 'REVIEW' || x.state === 'REJECTED' ? 'var(--warn, #d68910)' : x.self ? 'var(--accent, #2e86c1)' : 'var(--muted, #7f8c8d)'
  return (
    <div className="panel" style={{ marginTop: 10 }} data-network>
      <h3 style={{ marginTop: 0 }}>Network risk <span className={'pill' + (d.score >= 60 ? ' bad' : d.score >= 30 ? ' warn' : ' ok')}>{d.score}/100</span></h3>
      {d.nodes.length <= 1 ? <p className="small muted">No other PAN shares this applicant's mobile, bank account, device, email or address.</p> : (
        <>
          <ul className="small">{d.factors.map(f => <li key={f}>{f}</li>)}</ul>
          <svg viewBox={`0 0 ${W} ${H}`} style={{ width: '100%', maxWidth: W, height: 'auto' }} role="img" aria-label="Linked applications">
            {d.edges.map((e, i) => pos[e.from] && pos[e.to] && (
              <g key={i}>
                <line x1={pos[e.from][0]} y1={pos[e.from][1]} x2={pos[e.to][0]} y2={pos[e.to][1]} stroke="currentColor" strokeOpacity={0.3} />
                <text x={(pos[e.from][0] + pos[e.to][0]) / 2} y={(pos[e.from][1] + pos[e.to][1]) / 2 - 3} fontSize={10} textAnchor="middle" fill="currentColor" opacity={0.7}>{e.via}</text>
              </g>
            ))}
            {d.nodes.map(x => pos[x.id] && (
              <g key={x.id}>
                <circle cx={pos[x.id][0]} cy={pos[x.id][1]} r={x.self ? 10 : 7} fill={color(x)} />
                <text x={pos[x.id][0]} y={pos[x.id][1] + 20} fontSize={10} textAnchor="middle" fill="currentColor">{x.appNo}</text>
              </g>
            ))}
          </svg>
          <Table head={['Application', 'Applicant', 'Stage', 'Fraud']} rows={others.map(x => [
            x.visible ? <Link to={`/applications/${x.id}`}>{x.appNo}</Link> : x.appNo, x.name, <Pill s={x.state} />, <Pill s={x.fraudState} />,
          ])} />
        </>
      )}
    </div>
  )
}

interface Earner { name: string; relation: string; occupation?: string; monthlyIncome: number; included: boolean }
interface HouseholdView { household?: { members: number; monthlyExpenses: number; existingEmi: number; updatedBy?: string }; earners: Earner[]; monthlyIncome?: number; annualIncome?: number; surplus?: number; mfiQualifying?: boolean; mfiRepaymentRoom?: number }
const RELATIONS = ['SELF', 'SPOUSE', 'SON', 'DAUGHTER', 'FATHER', 'MOTHER', 'BROTHER', 'SISTER', 'OTHER']

/** Household cash flow: members, earners, expenses and repayments (the unit for microfinance). */
export function HouseholdPanel({ id, rev }: { id: string; rev: number }) {
  const { user } = useAuth()
  const [n, setN] = useState(0)
  const h = useLoad(() => api.get<HouseholdView>(`/api/applications/${id}/household`), [id, rev, n])
  const [edit, setEdit] = useState<{ members: string; monthlyExpenses: string; existingEmi: string; earners: Earner[] } | null>(null)
  const [err, setErr] = useState<unknown>(null)
  const can = ['SALES', 'OPERATIONS', 'FIELD_OFFICER', 'CREDIT_OFFICER', 'ADMIN'].includes(user?.role ?? '')
  if (!h.data) return <ErrorBox error={h.error} />
  const d = h.data
  function start() {
    setEdit({ members: String(d.household?.members ?? 1), monthlyExpenses: String(d.household?.monthlyExpenses ?? ''), existingEmi: String(d.household?.existingEmi ?? 0),
      earners: d.earners.length ? d.earners.map(e => ({ ...e })) : [{ name: '', relation: 'SELF', occupation: '', monthlyIncome: 0, included: true }] })
  }
  async function save() {
    try {
      setErr(null)
      await api.put(`/api/applications/${id}/household`, { members: Number(edit!.members), monthlyExpenses: Number(edit!.monthlyExpenses || 0), existingEmi: Number(edit!.existingEmi || 0),
        earners: edit!.earners.map(e => ({ ...e, monthlyIncome: Number(e.monthlyIncome) })) })
      setEdit(null); setN(x => x + 1)
    } catch (e) { setErr(e) }
  }
  return (
    <div className="panel" style={{ marginTop: 10 }} data-household>
      <h3 style={{ marginTop: 0 }}>Household cash flow</h3>
      <ErrorBox error={err} />
      {!edit && (d.household ? (
        <>
          <p className="small">{d.household.members} members · income {inr(d.monthlyIncome ?? 0)}/month ({inr(d.annualIncome ?? 0)} a year) · expenses {inr(d.household.monthlyExpenses)} · existing repayments {inr(d.household.existingEmi)} ·
            surplus <b>{inr(d.surplus ?? 0)}</b>. Microfinance: {d.mfiQualifying ? 'qualifies (income up to ₹3 lakh a year)' : 'above the ₹3 lakh household-income limit'}; room for repayments within the 50% cap {inr(d.mfiRepaymentRoom ?? 0)}/month.</p>
          <Table head={['Member', 'Relation', 'Occupation', 'Income / month', 'Counted']} rows={d.earners.map(e => [e.name, e.relation.toLowerCase(), e.occupation ?? '—', inr(e.monthlyIncome), e.included ? 'Yes' : 'No'])} />
        </>
      ) : <p className="small muted">Not captured. Needed for microfinance (the RBI cap uses household income); useful for any family business.</p>)}
      {!edit && can && <button className="btn sm" data-action="editHousehold" onClick={start}>{d.household ? 'Edit household' : 'Add household'}</button>}
      {edit && (
        <div className="form">
          <label className="fld">Members<input type="number" min={1} name="hhMembers" value={edit.members} onChange={e => setEdit({ ...edit, members: e.target.value })} /></label>
          <label className="fld">Household expenses / month (₹)<input type="number" name="hhExpenses" value={edit.monthlyExpenses} onChange={e => setEdit({ ...edit, monthlyExpenses: e.target.value })} /></label>
          <label className="fld">Existing loan repayments / month (₹)<input type="number" name="hhEmi" value={edit.existingEmi} onChange={e => setEdit({ ...edit, existingEmi: e.target.value })} /></label>
          {edit.earners.map((m, i) => (
            <div key={i} className="row wide" style={{ gap: 6, flexWrap: 'wrap' }}>
              <input placeholder="Name" aria-label="Member name" value={m.name} onChange={e => { const c = [...edit.earners]; c[i] = { ...m, name: e.target.value }; setEdit({ ...edit, earners: c }) }} />
              <select aria-label="Relation" value={m.relation} onChange={e => { const c = [...edit.earners]; c[i] = { ...m, relation: e.target.value }; setEdit({ ...edit, earners: c }) }}>{RELATIONS.map(r => <option key={r}>{r}</option>)}</select>
              <input placeholder="Occupation" aria-label="Occupation" value={m.occupation ?? ''} onChange={e => { const c = [...edit.earners]; c[i] = { ...m, occupation: e.target.value }; setEdit({ ...edit, earners: c }) }} />
              <input type="number" placeholder="Income / month" aria-label="Monthly income" value={String(m.monthlyIncome)} onChange={e => { const c = [...edit.earners]; c[i] = { ...m, monthlyIncome: Number(e.target.value) }; setEdit({ ...edit, earners: c }) }} />
              <label className="check"><input type="checkbox" checked={m.included} onChange={e => { const c = [...edit.earners]; c[i] = { ...m, included: e.target.checked }; setEdit({ ...edit, earners: c }) }} /> counted</label>
              <button className="btn sm" onClick={() => setEdit({ ...edit, earners: edit.earners.filter((_, j) => j !== i) })}>Remove</button>
            </div>
          ))}
          <div className="row wide">
            <button className="btn sm" onClick={() => setEdit({ ...edit, earners: [...edit.earners, { name: '', relation: 'SPOUSE', occupation: '', monthlyIncome: 0, included: true }] })}>Add earning member</button>
            <button className="btn primary" onClick={save}>Save household</button><button className="btn" onClick={() => setEdit(null)}>Cancel</button>
          </div>
        </div>
      )}
    </div>
  )
}

interface FieldCheck { check: string; status: string; detail: string }
interface FieldVisitView {
  visit: { id: number; officer: string; checkinAt: string; distanceM?: number; locationCheck: string; capturedOffline: boolean; remarks: string; outcome: string; syncedAt: string }
  answers: Record<string, unknown>; references: { name: string; phone: string; says: string }[]; checks: FieldCheck[]
  photos: { photo: { id: number; kind: string; takenAt?: string; lat?: number; lng?: number }; checks: FieldCheck[] }[]
}

function AuthImg({ src, alt }: { src: string; alt: string }) {
  const [url, setUrl] = useState<string>()
  useEffect(() => {
    let u: string | undefined
    const t = getToken()
    fetch(src, { headers: t ? { Authorization: 'Bearer ' + t } : {} }).then(r => r.ok ? r.blob() : null).then(b => { if (b) { u = URL.createObjectURL(b); setUrl(u) } }).catch(() => undefined)
    return () => { if (u) URL.revokeObjectURL(u) }
  }, [src])
  return url ? <img src={url} alt={alt} style={{ width: '100%', borderRadius: 6 }} /> : <div className="small muted">Loading photo…</div>
}

const CHECK_PILL: Record<string, string> = { OK: 'ok', WARN: 'warn', FAIL: 'bad' }

/** Field visits synced from the field officer app, with the location and photo checks. */
export function FieldVisitsPanel({ id, rev }: { id: string; rev: number }) {
  const v = useLoad(() => api.get<FieldVisitView[]>(`/api/applications/${id}/field-visits`), [id, rev])
  if (!v.data) return <ErrorBox error={v.error} />
  if (v.data.length === 0) return <div className="panel empty">No visit from the field officer app yet. Field officers see their visits under Field visits (works offline on the phone).</div>
  return (
    <>
      {v.data.map(x => (
        <div className="panel" key={x.visit.id} data-field-visit-view>
          <h3 style={{ marginTop: 0 }}>Visit by {x.visit.officer} · {when(x.visit.checkinAt)} <span className={'pill ' + (x.visit.outcome === 'POSITIVE' ? 'ok' : x.visit.outcome === 'NEGATIVE' ? 'bad' : 'warn')}>{x.visit.outcome.toLowerCase()}</span>
            {x.visit.capturedOffline && <span className="pill muted" style={{ marginLeft: 6 }}>captured offline</span>}</h3>
          {x.checks.map(c => <div key={c.check} className="small"><span className={'pill ' + (CHECK_PILL[c.status] ?? '')}>{c.status}</span> {c.check}: {c.detail}</div>)}
          <Table head={['Checklist', 'Answer']} rows={Object.entries(x.answers).map(([k, a]) => [k.replace(/([A-Z])/g, ' $1').toLowerCase(), typeof a === 'boolean' ? (a ? 'Yes' : 'No') : String(a)])} />
          <p className="small"><b>Remarks:</b> {x.visit.remarks}</p>
          {x.references.length > 0 && <p className="small"><b>References:</b> {x.references.map(r => `${r.name}${r.phone ? ' (' + r.phone + ')' : ''}${r.says ? ': ' + r.says : ''}`).join(' · ')}</p>}
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))', gap: 10 }}>
            {x.photos.map(p => (
              <div key={p.photo.id} className="panel" style={{ margin: 0, padding: 8 }} data-field-photo={p.photo.kind}>
                <div className="small"><b>{p.photo.kind}</b>{p.photo.takenAt && ' · ' + new Date(p.photo.takenAt).toLocaleTimeString()}</div>
                <AuthImg src={`/api/field/photos/${p.photo.id}/content`} alt={p.photo.kind} />
                {p.checks.map(c => <div key={c.check} className="small"><span className={'pill ' + (CHECK_PILL[c.status] ?? '')}>{c.status}</span> {c.check}: {c.detail}</div>)}
              </div>
            ))}
          </div>
        </div>
      ))}
    </>
  )
}
