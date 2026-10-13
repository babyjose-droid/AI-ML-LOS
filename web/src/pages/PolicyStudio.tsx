import { useEffect, useMemo, useState } from 'react'
import { api } from '../api'
import { useAuth } from '../auth'
import { ErrorBox, Head, Pill, Table, useLoad } from '../components/ui'
import { inr, pct, when } from '../format'

// Policy studio: draft a credit-policy change, simulate it on the loan book and recent applications,
// send it for approval; the CRO (not the drafter) approves it and only then does it go live.

interface Field { key: string; label: string; group: string; min: number; max: number; step: number; unit: string; help: string }
interface Version {
  id: number; version: string; status: string; notes?: string; createdBy?: string; createdAt: string; basedOn?: string
  submittedAt?: string; checker?: string; decidedAt?: string; decisionNote?: string; simulated: boolean; liveFrom?: string; retiredAt?: string
}
interface Change { key: string; label: string; group: string; from: number; to: number; unit: string }
interface Metrics {
  approve: number; conditional: number; refer: number; reject: number; approvalRate: number; referRate: number; rejectRate: number
  straightThroughRate: number; approvedAmount: number; expectedLoss: number; expectedLossRate: number; avgPdApproved: number; badRateApproved?: number
}
interface Simulation {
  liveVersion: string; draftVersion: string; changes: Change[]; simulatedAt?: string; simulatedBy?: string
  book: { loans: number; source: string; live: Metrics; draft: Metrics }
  swaps: { swapIn: number; swapInBadRate: number; swapOut: number; swapOutBadRate: number }
  segments: Record<string, { loans?: number; liveApproved?: number; draftApproved?: number; liveBad?: number; draftBad?: number }>
  recent: { applications: number; live: Metrics; draft: Metrics; changed: { applicationId: number; appNo: string; name: string; live: string; draft: string; liveAmount: number; draftAmount: number; liveRate: number; draftRate: number }[] }
  ruleHits: { rule: string; label: string; live: number; draft: number }[]
}
interface Detail { policy: Version; params: Record<string, number | string>; changes: Change[]; simulation: Simulation | null }

const MAKERS = ['CREDIT_MANAGER', 'CRO', 'ADMIN']

function show(v: number | undefined, unit: string) {
  if (v === undefined || v === null) return '—'
  if (unit === 'share') return pct(v, v < 0.1 ? 1 : 0)
  if (unit === '₹') return inr(v)
  return `${v} ${unit}`
}

export default function PolicyStudio() {
  const { user } = useAuth()
  const [rev, setRev] = useState(0)
  const versions = useLoad(() => api.get<Version[]>('/api/policies'), [rev])
  const fields = useLoad(() => api.get<Field[]>('/api/policies/fields'), [])
  const [selected, setSelected] = useState<number | null>(null)
  const [error, setError] = useState<unknown>()
  const list = versions.data ?? []
  const open = list.find(v => v.status === 'DRAFT' || v.status === 'PENDING_APPROVAL')
  const live = list.find(v => v.status === 'LIVE')
  const current = selected ?? open?.id ?? live?.id ?? null
  const isMaker = !!user && MAKERS.includes(user.role)

  async function newDraft() {
    try {
      const d = await api.post<Version>('/api/policies/drafts', {})
      setSelected(d.id)
      setRev(r => r + 1)
      setError(undefined)
    } catch (e) { setError(e) }
  }

  return (
    <>
      <Head title="Credit policy studio" sub="Change the credit policy safely: draft, simulate on past loans and recent applications, then the CRO approves before it goes live.">
        {isMaker && !open && <button className="btn primary" data-policy="new" onClick={newDraft}>New draft from live policy</button>}
      </Head>
      <ErrorBox error={error ?? versions.error} />
      <div className="policy-layout">
        <div className="panel flush">
          <Table head={['Version', 'Status', 'By']} onRow={i => setSelected(list[i].id)} rows={list.map(v => [
            <b className={v.id === current ? '' : 'muted'}>{v.version}</b>, <Pill s={v.status} />, <span className="small">{v.createdBy}{v.checker ? ` · ${v.checker}` : ''}</span>,
          ])} />
        </div>
        <div>
          {current && fields.data && <PolicyDetail key={current + ':' + rev} id={current} fields={fields.data} onChange={() => setRev(r => r + 1)} />}
        </div>
      </div>
    </>
  )
}

function PolicyDetail({ id, fields, onChange }: { id: number; fields: Field[]; onChange: () => void }) {
  const { user } = useAuth()
  const d = useLoad(() => api.get<Detail>(`/api/policies/${id}`), [id])
  const live = useLoad(() => api.get<Record<string, number>>('/api/policies/live'), [id])
  const [vals, setVals] = useState<Record<string, string>>({})
  const [notes, setNotes] = useState('')
  const [sim, setSim] = useState<Simulation | null>(null)
  const [busy, setBusy] = useState('')
  const [note, setNote] = useState('')
  const [error, setError] = useState<unknown>()
  const [saved, setSaved] = useState('')

  useEffect(() => {
    if (!d.data) return
    setVals(Object.fromEntries(fields.map(f => [f.key, String(d.data!.params[f.key])])))
    setNotes(d.data.policy.notes ?? '')
    setSim(d.data.simulation)
  }, [d.data, fields])

  const groups = useMemo(() => [...new Set(fields.map(f => f.group))], [fields])
  if (!d.data || !live.data) return <ErrorBox error={d.error ?? live.error} />
  const p = d.data.policy
  const editable = p.status === 'DRAFT' && user?.username === p.createdBy
  const canDecide = p.status === 'PENDING_APPROVAL' && user?.role === 'CRO' && user.username !== p.createdBy
  const dirty = fields.some(f => String(d.data!.params[f.key]) !== vals[f.key]) || notes !== (p.notes ?? '')

  async function act(name: string, fn: () => Promise<unknown>, after?: () => void) {
    setBusy(name)
    setError(undefined)
    setSaved('')
    try { await fn(); after?.(); } catch (e) { setError(e) } finally { setBusy('') }
  }
  const save = () => act('save', async () => {
    const params = Object.fromEntries(fields.map(f => [f.key, Number(vals[f.key])]))
    await api.put(`/api/policies/${id}`, { params, notes })
    setSim(null)
    setSaved('Saved. Run the simulation again before sending for approval.')
  }, () => d.reload())
  const simulate = () => act('simulate', async () => setSim(await api.post<Simulation>(`/api/policies/${id}/simulate`)), () => d.reload())

  return (
    <div data-policy-detail={p.status}>
      <div className="panel">
        <div className="head">
          <div>
            <h3 style={{ margin: 0 }}>{p.version} <Pill s={p.status} /></h3>
            <p className="small muted" style={{ margin: '4px 0 0' }}>
              Drafted by {p.createdBy} {when(p.createdAt)}{p.basedOn ? ` from ${p.basedOn}` : ''}
              {p.submittedAt && <> · sent for approval {when(p.submittedAt)}</>}
              {p.checker && <> · {p.status === 'LIVE' || p.status === 'RETIRED' ? 'approved' : 'reviewed'} by {p.checker} {when(p.decidedAt)}</>}
              {p.liveFrom && <> · live from {when(p.liveFrom)}</>}{p.retiredAt && <> · retired {when(p.retiredAt)}</>}
            </p>
          </div>
          <div className="row">
            {editable && <button className="btn" disabled={!dirty || !!busy} onClick={save} data-policy="save">Save</button>}
            {editable && <button className="btn" disabled={dirty || !!busy} onClick={simulate} data-policy="simulate">{busy === 'simulate' ? 'Simulating…' : 'Run simulation'}</button>}
            {editable && <button className="btn primary" disabled={dirty || !sim || !!busy} data-policy="submit"
              onClick={() => act('submit', () => api.post(`/api/policies/${id}/submit`), onChange)}>Send for approval</button>}
            {editable && <button className="btn danger" disabled={!!busy} onClick={() => act('discard', () => fetchDelete(`/api/policies/${id}`), onChange)}>Discard</button>}
          </div>
        </div>
        <ErrorBox error={error} />
        {saved && <div className="alert ok">{saved}</div>}
        {p.decisionNote && <div className="alert warn small">Reviewer note: {p.decisionNote}</div>}
        {editable
          ? <label className="fld" style={{ marginTop: 10 }}>Why this change (shown to the approver)<textarea name="policyNotes" rows={2} value={notes} onChange={e => setNotes(e.target.value)} /></label>
          : p.notes && <p className="small"><b>Why:</b> {p.notes}</p>}
      </div>

      {d.data.changes.length > 0 && (
        <div className="panel" data-policy-changes>
          <h3 style={{ marginTop: 0 }}>Changes from {p.basedOn}</h3>
          <Table head={['Parameter', 'From', 'To']} rows={d.data.changes.map(c => [c.label, show(c.from, c.unit), <b>{show(c.to, c.unit)}</b>])} />
        </div>
      )}

      {groups.map(g => (
        <div className="panel" key={g}>
          <h3 style={{ marginTop: 0 }}>{g}</h3>
          <Table head={['Parameter', editable ? 'Value' : 'This version', 'Live now', 'Allowed']} rows={fields.filter(f => f.group === g).map(f => {
            const changed = String(live.data![f.key]) !== vals[f.key]
            return [
              <span title={f.help}>{f.label}<br /><span className="small muted">{f.help}</span></span>,
              editable
                ? <input type="number" name={f.key} step={f.step} min={f.min} max={f.max} value={vals[f.key] ?? ''} style={{ maxWidth: 140 }}
                    className={changed ? 'changed' : ''} onChange={e => setVals({ ...vals, [f.key]: e.target.value })} />
                : <b className={changed ? 'changed-text' : ''}>{show(Number(vals[f.key]), f.unit)}</b>,
              <span className="small">{show(live.data![f.key], f.unit)}</span>,
              <span className="small muted">{show(f.min, f.unit)} – {show(f.max, f.unit)}</span>,
            ]
          })} />
        </div>
      ))}

      {sim && <SimulationView s={sim} />}

      {canDecide && (
        <div className="panel" data-policy-decide>
          <h3 style={{ marginTop: 0 }}>Your decision</h3>
          <p className="small muted">Approving makes this the live policy for every new decision. Applications already decided keep their result.</p>
          <label className="fld">Note (required)<textarea name="decisionNote" rows={2} value={note} onChange={e => setNote(e.target.value)} /></label>
          <div className="row" style={{ marginTop: 8 }}>
            <button className="btn primary" data-policy="approve" onClick={() => act('approve', () => api.post(`/api/policies/${id}/decision`, { approve: true, note }), onChange)}>Approve and make live</button>
            <button className="btn danger" data-policy="sendback" onClick={() => act('reject', () => api.post(`/api/policies/${id}/decision`, { approve: false, note }), onChange)}>Send back to drafter</button>
          </div>
        </div>
      )}
    </div>
  )
}

async function fetchDelete(url: string) {
  let token: string | null = null
  try { token = localStorage.getItem('rhythm_token') } catch { /* ignore */ }
  const r = await fetch(url, { method: 'DELETE', headers: token ? { Authorization: 'Bearer ' + token } : {} })
  if (!r.ok) throw new Error((await r.json().catch(() => ({})))?.message ?? 'Could not discard')
}

function delta(a: number, b: number, kind: 'pct' | 'inr' | 'n', goodWhenUp: boolean | null) {
  const d = b - a
  if (Math.abs(d) < 1e-9) return <span className="muted small">no change</span>
  const txt = kind === 'pct' ? `${d > 0 ? '+' : ''}${(d * 100).toFixed(1)} pts` : kind === 'inr' ? `${d > 0 ? '+' : '−'}${inr(Math.abs(d))}` : `${d > 0 ? '+' : ''}${d}`
  const cls = goodWhenUp === null ? '' : (d > 0) === goodWhenUp ? 'ok-text' : 'bad-text'
  return <b className={cls}>{txt}</b>
}

function SimulationView({ s }: { s: Simulation }) {
  const b = s.book, r = s.recent
  const rows = (live: Metrics, draft: Metrics, outcome: boolean) => [
    ['Approval rate', pct(live.approvalRate, 1), pct(draft.approvalRate, 1), delta(live.approvalRate, draft.approvalRate, 'pct', null)],
    ['Referred to a person', pct(live.referRate, 1), pct(draft.referRate, 1), delta(live.referRate, draft.referRate, 'pct', false)],
    ['Declined', pct(live.rejectRate, 1), pct(draft.rejectRate, 1), delta(live.rejectRate, draft.rejectRate, 'pct', null)],
    ['Decided without a person', pct(live.straightThroughRate, 1), pct(draft.straightThroughRate, 1), delta(live.straightThroughRate, draft.straightThroughRate, 'pct', true)],
    ['Amount approved', inr(live.approvedAmount), inr(draft.approvedAmount), delta(live.approvedAmount, draft.approvedAmount, 'inr', null)],
    ['Expected loss (PD × LGD)', `${inr(live.expectedLoss)} · ${pct(live.expectedLossRate, 2)}`, `${inr(draft.expectedLoss)} · ${pct(draft.expectedLossRate, 2)}`, delta(live.expectedLossRate, draft.expectedLossRate, 'pct', false)],
    ['Average PD of approved', pct(live.avgPdApproved, 2), pct(draft.avgPdApproved, 2), delta(live.avgPdApproved, draft.avgPdApproved, 'pct', false)],
    ...(outcome ? [['Actual bad rate of approved', pct(live.badRateApproved ?? 0, 2), pct(draft.badRateApproved ?? 0, 2), delta(live.badRateApproved ?? 0, draft.badRateApproved ?? 0, 'pct', false)]] : []),
  ]
  return (
    <div data-policy-simulation>
      <div className="panel">
        <h3 style={{ marginTop: 0 }}>Simulation: {s.liveVersion} (live) vs {s.draftVersion}</h3>
        <p className="small muted">
          {b.loans} past loans with known 12-month outcomes{b.source === 'SYNTHETIC' ? ' (synthetic demo book until your own history is imported)' : ''}, and the latest decision on {r.applications} recent applications,
          re-run through the decision engine with each policy. {s.simulatedAt && <>Run {when(s.simulatedAt)} by {s.simulatedBy}.</>}
        </p>
        {b.loans > 0 && (
          <>
            <h4>Past loans ({b.loans})</h4>
            <Table head={['', 'Live policy', 'Draft', 'Change']} rows={rows(b.live, b.draft, true)} />
            <div className="kpis" style={{ marginTop: 12 }}>
              <div className="kpi"><span>Newly approved (swap-in)</span><b data-sim="swapIn">{s.swaps.swapIn}</b><span>{s.swaps.swapIn ? `bad rate ${pct(s.swaps.swapInBadRate, 1)}` : ''}</span></div>
              <div className="kpi"><span>No longer approved (swap-out)</span><b data-sim="swapOut">{s.swaps.swapOut}</b><span>{s.swaps.swapOut ? `bad rate ${pct(s.swaps.swapOutBadRate, 1)}` : ''}</span></div>
            </div>
            <Table head={['Segment', 'Loans', 'Approved: live → draft', 'Bad rate of approved: live → draft']} rows={Object.entries(s.segments).map(([k, v]) => [
              k.toLowerCase(), v.loans ?? 0, `${v.liveApproved ?? 0} → ${v.draftApproved ?? 0}`,
              `${pct((v.liveBad ?? 0) / Math.max(1, v.liveApproved ?? 0), 1)} → ${pct((v.draftBad ?? 0) / Math.max(1, v.draftApproved ?? 0), 1)}`,
            ])} />
          </>
        )}
      </div>
      <div className="grid2">
        <div className="panel">
          <h3 style={{ marginTop: 0 }}>Recent applications ({r.applications})</h3>
          <Table head={['', 'Live', 'Draft', 'Change']} rows={rows(r.live, r.draft, false).slice(0, 5)} />
          {r.changed.length > 0 && (
            <>
              <h4>Applications whose result would change</h4>
              <Table head={['Application', 'Live', 'Draft']} rows={r.changed.map(c => [
                <a href={`/applications/${c.applicationId}`}>{c.appNo} · {c.name}</a>,
                <span className="small">{c.live.replace(/_/g, ' ').toLowerCase()} · {inr(c.liveAmount)} @ {c.liveRate}%</span>,
                <span className="small"><b>{c.draft.replace(/_/g, ' ').toLowerCase()}</b> · {inr(c.draftAmount)} @ {c.draftRate}%</span>,
              ])} />
            </>
          )}
        </div>
        <div className="panel">
          <h3 style={{ marginTop: 0 }}>Rules that stopped auto-approval (past loans)</h3>
          <Table head={['Rule', 'Live', 'Draft']} rows={s.ruleHits.map(h => [`${h.rule} ${h.label}`, h.live, <b className={h.draft !== h.live ? 'changed-text' : ''}>{h.draft}</b>])} empty="No rules triggered." />
        </div>
      </div>
    </div>
  )
}
