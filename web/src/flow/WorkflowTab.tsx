import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'
import { ErrorBox, Modal, Pill, Table } from '../components/ui'
import { label, when } from '../format'
import type { AppStage, AppStages } from '../types'

const FIELD_LABEL: Record<string, string> = {
  groupName: 'Group name', members: 'Members', sessionsHeld: 'Training sessions held', membersPresent: 'Members present',
  registrationNo: 'Registration no.', valuation: 'Valuation (₹)', netWeightGrams: 'Net weight (g)', purityKarat: 'Purity (karat)',
  value: 'Value (₹)', titleClear: 'Title clear (yes/no)', marketValue: 'Market value (₹)',
}
const NUMERIC = ['members', 'sessionsHeld', 'membersPresent', 'valuation', 'netWeightGrams', 'purityKarat', 'value', 'marketValue']

function due(s: AppStage) {
  if (!s.dueAt || !['ACTIVE', 'REVIEW', 'FAILED'].includes(s.status)) return null
  const h = Math.round((new Date(s.dueAt).getTime() - Date.now()) / 36e5)
  return h >= 0 ? `due in ${h} h` : `${-h} h overdue`
}

/** Compact stage strip shown above the tabs on every application. */
export function StageStrip({ data, onOpen }: { data: AppStages; onOpen: () => void }) {
  const mine = data.stages.filter(s => s.canComplete || s.canReview)
  return (
    <div className="panel" data-testid="stage-strip">
      <div className="row" style={{ justifyContent: 'space-between', marginBottom: 8 }}>
        <b className="small">Workflow {data.product}{data.version ? ` v${data.version}` : ' (default)'}</b>
        {mine.length === 0 && data.stages.some(s => s.assignedToMe && s.kind === 'STATE') && (
          <span className="small" data-stage-assigned>Your stage: {data.stages.filter(s => s.assignedToMe).map(s => s.name).join(', ')}</span>
        )}
        {mine.length > 0 && <button className="btn sm primary" data-stage-mine onClick={onOpen}>Your turn: {mine.map(s => s.name).join(', ')}</button>}
      </div>
      <div className="stepper">
        {data.stages.map(s => (
          <button key={s.key} className={'s-' + s.status} data-stage={s.key} data-stage-status={s.status} onClick={onOpen} title={s.skipReason ? `Skipped: ${s.skipReason}` : s.help}>
            <b>{s.name}</b>
            <span>{label(s.status)}{s.breachedAt ? ' · SLA breached' : ''}{s.assignedToMe ? ' · you' : ''}</span>
          </button>
        ))}
      </div>
    </div>
  )
}

export default function WorkflowTab({ id, data, onChange }: { id: string; data: AppStages; onChange: () => void }) {
  const [open, setOpen] = useState<{ stage: AppStage; review: boolean } | null>(null)
  return (
    <>
      <div className="panel flush">
        <Table head={['#', 'Stage', 'Done by', 'Status', 'SLA', 'Details', '']} rows={data.stages.map((s, i) => [
          i + 1,
          <span><b>{s.name}</b><div className="small muted">{label(s.mode)}{s.kind === 'RECORDED' ? ' · recorded by a person' : ''}</div></span>,
          <span className="small">{label(s.escalatedTo ?? s.role)}{s.escalatedTo && <div className="muted">escalated from {label(s.role)}</div>}</span>,
          <span><Pill s={s.status} />{s.breachedAt && <div><span className="pill bad" style={{ marginTop: 4 }}>SLA breached · {label(s.breachAction ?? '')}</span></div>}</span>,
          <span className="small">{s.sla} h{due(s) ? <div className="muted">{due(s)}</div> : null}</span>,
          <span className="small">
            {s.skipReason && <>Skipped: <span className="mono">{s.skipReason}</span></>}
            {s.completedBy && s.status !== 'SKIPPED' && <>{s.outcome ? `${s.outcome} · ` : ''}{s.completedBy} {when(s.completedAt)}</>}
            {s.note && <div>{s.note}</div>}
            {s.data && <div className="muted">{Object.entries(s.data).map(([k, x]) => `${FIELD_LABEL[k] ?? k}: ${x}`).join(' · ')}</div>}
            {s.reviewedBy && <div className="muted">confirmed by {s.reviewedBy}</div>}
          </span>,
          s.canComplete ? <button className="btn sm primary" data-stage-complete={s.key} onClick={() => setOpen({ stage: s, review: false })}>Record</button>
            : s.canReview ? <button className="btn sm primary" data-stage-review={s.key} onClick={() => setOpen({ stage: s, review: true })}>Confirm</button> : '',
        ])} />
      </div>
      <p className="small muted">Stages follow the product's workflow version this application started on. <Link to={`/admin/workflows?product=${data.product}`}>Open the workflow designer</Link></p>
      {open && <StageDialog id={id} stage={open.stage} review={open.review} onClose={() => setOpen(null)} onDone={() => { setOpen(null); onChange() }} />}
    </>
  )
}

function StageDialog({ id, stage, review, onClose, onDone }: { id: string; stage: AppStage; review: boolean; onClose: () => void; onDone: () => void }) {
  const [outcome, setOutcome] = useState('PASS')
  const [note, setNote] = useState('')
  const [data, setData] = useState<Record<string, string>>({})
  const [error, setError] = useState<unknown>()
  const [busy, setBusy] = useState(false)
  async function go() {
    setBusy(true)
    setError(undefined)
    try {
      if (review) await api.post(`/api/applications/${id}/stages/${stage.key}/review`, { note })
      else {
        const d: Record<string, unknown> = {}
        for (const [k, x] of Object.entries(data)) if (x.trim()) d[k] = NUMERIC.includes(k) && !isNaN(Number(x)) ? Number(x) : x.trim()
        await api.post(`/api/applications/${id}/stages/${stage.key}/complete`, { outcome, note, data: d })
      }
      onDone()
    } catch (e) { setError(e) } finally { setBusy(false) }
  }
  return (
    <Modal title={review ? `Confirm: ${stage.name}` : stage.name} onClose={onClose}>
      <p className="small muted" style={{ marginTop: 0 }}>{stage.help}</p>
      <ErrorBox error={error} />
      <div className="form-grid">
        {!review && (
          <label className="fld">Outcome<select name="stageOutcome" value={outcome} onChange={e => setOutcome(e.target.value)}>
            <option value="PASS">Pass</option>
            <option value="FAIL">Fail ({label(stage.onFailure)})</option>
          </select></label>
        )}
        {!review && stage.dataFields.map(k => (
          <label key={k} className="fld">{FIELD_LABEL[k] ?? k}
            <input name={'stage_' + k} inputMode={NUMERIC.includes(k) ? 'decimal' : undefined} value={data[k] ?? ''} onChange={e => setData({ ...data, [k]: e.target.value })} />
          </label>
        ))}
        <label className="fld wide">Notes (at least 10 characters)<textarea name="stageNote" rows={3} value={note} onChange={e => setNote(e.target.value)} /></label>
      </div>
      {!review && outcome === 'FAIL' && stage.onFailure === 'REJECT' && <div className="alert bad" style={{ marginTop: 10 }}>Failing this stage rejects the application.</div>}
      <div className="row" style={{ marginTop: 12 }}>
        <button className="btn primary" disabled={busy} data-stage-save onClick={go}>{review ? 'Confirm' : 'Save'}</button>
        <button className="btn" onClick={onClose}>Cancel</button>
      </div>
    </Modal>
  )
}
