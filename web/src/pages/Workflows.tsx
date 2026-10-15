import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api } from '../api'
import { useAuth } from '../auth'
import { ErrorBox, Head, Pill, Table, useLoad } from '../components/ui'
import { label, when } from '../format'
import type { LibraryStage, ProductWorkflows, Stage, WorkflowDetail } from '../types'

// Workflow designer: the stage sequence of each product, with role, SLA, mode, failure action, skip rule and
// integrations per stage. Edited as a draft, checked, approved by the CRO (not the maker) and published as a new
// version. Applications already in flight keep the version they started on.

const ROLES = ['SALES', 'OPERATIONS', 'FIELD_OFFICER', 'CREDIT_OFFICER', 'CREDIT_MANAGER', 'CRO', 'FRAUD_ANALYST', 'COMPLIANCE', 'LEGAL_REVIEWER', 'VALUER']
const MODE_LABEL: Record<string, string> = { AUTO: 'Automatic', AUTO_REVIEW: 'Automatic, person confirms', MANUAL: 'Manual' }
const FAIL_LABEL: Record<string, string> = { QUEUE: 'Send to queue', ESCALATE: 'Escalate to next level', REJECT: 'Reject application', PAUSE: 'Pause and notify borrower' }
const MAKERS = ['ADMIN', 'CREDIT_MANAGER']

interface Library { stages: LibraryStage[]; conditions: { fields: Record<string, string>; examples: string[] } }

export default function Workflows() {
  const { user } = useAuth()
  const [params, setParams] = useSearchParams()
  const [rev, setRev] = useState(0)
  const overview = useLoad(() => api.get<ProductWorkflows[]>('/api/workflows'), [rev])
  const lib = useLoad(() => api.get<Library>('/api/workflows/library'), [])
  const [error, setError] = useState<unknown>()
  const products = overview.data ?? []
  const code = params.get('product') ?? products[0]?.productCode
  const product = products.find(p => p.productCode === code)
  const versionParam = params.get('v')
  const versionId = versionParam ? Number(versionParam)
    : product?.versions.find(v => ['DRAFT', 'PENDING_APPROVAL'].includes(v.status))?.id ?? product?.published?.id ?? product?.versions[0]?.id
  const canEdit = !!user && MAKERS.includes(user.role)
  const hasOpen = !!product?.versions.some(v => ['DRAFT', 'PENDING_APPROVAL'].includes(v.status))

  const pick = (p: string, v?: number) => setParams(v ? { product: p, v: String(v) } : { product: p })

  async function newDraft() {
    setError(undefined)
    try {
      const d = await api.post<WorkflowDetail>('/api/workflows/drafts', { productCode: code })
      setRev(r => r + 1)
      pick(code!, d.id)
    } catch (e) { setError(e) }
  }

  return (
    <>
      <Head title="Workflow designer" sub="Each product's stages, in order: who does each one, its SLA, whether it runs automatically, what happens if it fails, and when it can be skipped. Changes are approved by the CRO and apply to new applications only.">
        {canEdit && product && !hasOpen && <button className="btn primary" data-wf="new-draft" onClick={newDraft}>Edit {product.productCode} (new version)</button>}
      </Head>
      <ErrorBox error={error ?? overview.error ?? lib.error} />
      <div className="chips" role="tablist" aria-label="Products">
        {products.map(p => (
          <button key={p.productCode} role="tab" aria-selected={p.productCode === code} className={p.productCode === code ? 'on' : ''} data-wf-product={p.productCode} onClick={() => pick(p.productCode)}>
            <b>{p.productCode}</b> <span className="small">{p.published ? `v${p.published.version} · ${p.published.stageCount} stages` : 'default stages'}</span>
            {p.versions.some(v => v.status === 'PENDING_APPROVAL') && <span className="pill warn" style={{ marginLeft: 6 }}>approval</span>}
            {p.versions.some(v => v.status === 'DRAFT') && <span className="pill muted" style={{ marginLeft: 6 }}>draft</span>}
          </button>
        ))}
      </div>
      {product && lib.data && (
        versionId
          ? <VersionEditor key={versionId + ':' + rev} id={versionId} product={product} lib={lib.data} onChange={(v) => { setRev(r => r + 1); if (v !== undefined) pick(product.productCode, v || undefined) }} />
          : <div className="panel empty">{product.productName} uses the default stages. {canEdit ? 'Create a version to change them.' : ''}</div>
      )}
      {product && product.versions.length > 0 && (
        <div className="panel flush">
          <Table head={['Version', 'Status', 'Stages', 'Applications on it', 'Made by', 'Approved by', 'Change']} onRow={i => pick(product.productCode, product.versions[i].id)} rows={product.versions.map(v => [
            <b className={v.id === versionId ? '' : 'muted'}>v{v.version}</b>, <Pill s={v.status} />, v.stageCount,
            <span className="small" data-wf-usage={v.version}>{v.applications ?? 0}{v.inFlight ? ` · ${v.inFlight} in progress` : ''}</span>, <span className="small">{v.createdBy} {when(v.createdAt)}</span>,
            <span className="small">{v.approvedBy ? `${v.approvedBy} ${when(v.approvedAt)}` : '—'}</span>, <span className="small">{v.notes ?? ''}</span>,
          ])} />
        </div>
      )}
    </>
  )
}

function VersionEditor({ id, product, lib, onChange }: { id: number; product: ProductWorkflows; lib: Library; onChange: (selectVersion?: number) => void }) {
  const { user } = useAuth()
  const d = useLoad(() => api.get<WorkflowDetail>(`/api/workflows/${id}`), [id])
  const [stages, setStages] = useState<Stage[]>([])
  const [notes, setNotes] = useState('')
  const [sel, setSel] = useState(0)
  const [dirty, setDirty] = useState(false)
  const [issues, setIssues] = useState<string[]>([])
  const [changes, setChanges] = useState<string[]>([])
  const [error, setError] = useState<unknown>()
  const [saved, setSaved] = useState<string>()
  const [decisionNote, setDecisionNote] = useState('')
  const [add, setAdd] = useState('')

  useEffect(() => {
    if (!d.data) return
    setStages(d.data.stages)
    setNotes(d.data.notes ?? '')
    setIssues(d.data.issues)
    setChanges(d.data.changes ?? [])
    setDirty(false)
  }, [d.data])

  const defs = useMemo(() => Object.fromEntries(lib.stages.map(s => [s.key, s])), [lib])
  if (!d.data) return <ErrorBox error={d.error} />
  const v = d.data
  const editable = v.status === 'DRAFT' && !!user && MAKERS.includes(user.role)
  const canDecide = v.status === 'PENDING_APPROVAL' && user?.role === 'CRO' && user.username !== v.createdBy
  const available = lib.stages.filter(s => !stages.some(x => x.key === s.key))
  const cur = stages[Math.min(sel, stages.length - 1)]
  const curDef = cur ? defs[cur.key] : undefined
  const localIssues = localCheck(stages, defs)

  function change(i: number, patch: Partial<Stage>) {
    setStages(ss => ss.map((s, j) => j === i ? { ...s, ...patch } : s))
    setDirty(true)
    setSaved(undefined)
  }
  function move(i: number, by: number) {
    const j = i + by
    if (j < 0 || j >= stages.length) return
    const ss = [...stages];
    [ss[i], ss[j]] = [ss[j], ss[i]]
    setStages(ss)
    setSel(j)
    setDirty(true)
  }
  function remove(i: number) {
    setStages(ss => ss.filter((_, j) => j !== i))
    setSel(Math.max(0, i - 1))
    setDirty(true)
  }
  function addStage() {
    const l = defs[add]
    if (!l) return
    const s: Stage = { key: l.key, name: l.name, role: l.role, sla: l.sla, mode: l.mode, onFailure: 'QUEUE', skip: '', integrations: l.integrations }
    // insert before sanction (or before the last stage) so the new stage lands in a sensible place
    const at = Math.max(1, stages.findIndex(x => x.key === 'sanction') >= 0 ? stages.findIndex(x => x.key === 'sanction') : stages.length - 1)
    const ss = [...stages]
    ss.splice(at, 0, s)
    setStages(ss)
    setSel(at)
    setAdd('')
    setDirty(true)
  }
  async function save(): Promise<boolean> {
    setError(undefined)
    try {
      const r = await api.put<WorkflowDetail>(`/api/workflows/${id}`, { stages, notes })
      setIssues(r.issues)
      setChanges(r.changes ?? [])
      setDirty(false)
      setSaved('Draft saved')
      return true
    } catch (e) { setError(e); return false }
  }
  async function submit() {
    if (dirty && !(await save())) return
    try { await api.post(`/api/workflows/${id}/submit`); onChange() } catch (e) { setError(e) }
  }
  async function decide(approve: boolean) {
    try { await api.post(`/api/workflows/${id}/decision`, { approve, note: decisionNote }); onChange() } catch (e) { setError(e) }
  }
  async function discard() {
    if (!window.confirm(`Delete draft v${v.version}?`)) return
    try { await api.del(`/api/workflows/${id}`); onChange(0) } catch (e) { setError(e) }
  }

  return (
    <div data-wf-version={v.version} data-wf-status={v.status}>
      <div className="panel">
        <div className="row" style={{ justifyContent: 'space-between' }}>
          <h3 style={{ margin: 0 }}>{product.productName} · v{v.version} <Pill s={v.status} /></h3>
          <span className="small muted">
            Made by {v.createdBy} {when(v.createdAt)}
            {v.approvedBy && <> · {v.status === 'REJECTED' ? 'rejected' : 'approved'} by {v.approvedBy} {when(v.approvedAt)}{v.decisionNote ? `: ${v.decisionNote}` : ''}</>}
          </span>
        </div>
        {v.status === 'PUBLISHED' && <p className="small muted" style={{ marginBottom: 0 }}>Every new {product.productCode} application is fixed to this version when it is created. Applications created on earlier versions keep following those versions until they close.</p>}
        {v.status === 'RETIRED' && <p className="small muted" style={{ marginBottom: 0 }}>No longer used for new applications. {v.inFlight ? `${v.inFlight} application(s) created on it are still in progress and keep following it.` : ''}</p>}
        {v.status === 'PENDING_APPROVAL' && <p className="small muted" style={{ marginBottom: 0 }}>Waiting for the CRO. Change described as: {v.notes}</p>}
      </div>

      <div className="wf-layout">
        <div className="panel" style={{ padding: 12 }}>
          <ol className="wf-stages" aria-label="Stages in order">
            {stages.map((s, i) => {
              const l = defs[s.key]
              return (
                <li key={s.key} className={i === sel ? 'on' : ''} data-wf-stage={s.key} onClick={() => setSel(i)}>
                  <span className="wf-n" title={s.parallel ? 'Runs alongside the stage above' : undefined}>{s.parallel ? '∥' : i + 1}</span>
                  <div style={{ minWidth: 0, flex: 1 }}>
                    <b>{s.name}</b>
                    <div className="small muted">{label(s.role)} · {s.sla} h · {MODE_LABEL[s.mode] ?? s.mode}{s.skip ? ' · has skip rule' : ''}{s.parallel ? ' · alongside the stage above' : ''}{l?.kind === 'RECORDED' ? ' · recorded by a person' : ''}</div>
                  </div>
                  {editable && (
                    <span className="row" style={{ gap: 2, flexWrap: 'nowrap' }} onClick={e => e.stopPropagation()}>
                      <button className="btn sm" aria-label={`Move ${s.name} up`} disabled={i === 0} onClick={() => move(i, -1)}>↑</button>
                      <button className="btn sm" aria-label={`Move ${s.name} down`} disabled={i === stages.length - 1} onClick={() => move(i, 1)}>↓</button>
                      <button className="btn sm" aria-label={`Remove ${s.name}`} disabled={l?.mandatory} onClick={() => remove(i)}>✕</button>
                    </span>
                  )}
                </li>
              )
            })}
          </ol>
          {editable && available.length > 0 && (
            <div className="row" style={{ marginTop: 10 }}>
              <select aria-label="Stage to add" name="addStage" value={add} onChange={e => setAdd(e.target.value)} style={{ flex: 1, minWidth: 0 }}>
                <option value="">Add a stage from the library…</option>
                {available.map(s => <option key={s.key} value={s.key}>{s.name}</option>)}
              </select>
              <button className="btn sm" data-wf="add-stage" disabled={!add} onClick={addStage}>Add</button>
            </div>
          )}
        </div>

        <div style={{ minWidth: 0 }}>
          {cur && curDef && (
            <div className="panel" data-wf-editor={cur.key}>
              <h3 style={{ marginTop: 0 }}>{cur.name}</h3>
              <p className="small muted" style={{ marginTop: 0 }}>{curDef.help}{curDef.dataFields.length > 0 && ` · records: ${curDef.dataFields.join(', ')}`}</p>
              <div className="form-grid">
                <label className="fld">Name<input name="stageName" value={cur.name} disabled={!editable} onChange={e => change(sel, { name: e.target.value })} /></label>
                <label className="fld">Done by<select name="stageRole" value={cur.role} disabled={!editable} onChange={e => change(sel, { role: e.target.value })}>
                  {ROLES.map(r => <option key={r} value={r}>{label(r)}</option>)}</select></label>
                <label className="fld">SLA (hours)<input name="stageSla" type="number" min={1} max={720} value={cur.sla} disabled={!editable} onChange={e => change(sel, { sla: Number(e.target.value) })} /></label>
                <label className="fld">Mode<select name="stageMode" value={cur.mode} disabled={!editable} onChange={e => change(sel, { mode: e.target.value })}>
                  {curDef.modes.map(m => <option key={m} value={m}>{MODE_LABEL[m] ?? m}</option>)}</select></label>
                <label className="fld">If it fails or breaches its SLA<select name="stageOnFailure" value={cur.onFailure} disabled={!editable} onChange={e => change(sel, { onFailure: e.target.value })}>
                  {Object.entries(FAIL_LABEL).map(([k, t]) => <option key={k} value={k}>{t}</option>)}</select></label>
                <label className="check fld wide"><span><input type="checkbox" name="stageParallel" checked={!!cur.parallel} disabled={!editable || sel === 0 || ['lead', 'uw', 'sanction', 'disb'].includes(cur.key)}
                  onChange={e => change(sel, { parallel: e.target.checked })} /> Runs alongside the stage above (both start together; the next stage waits for both)</span></label>
                <label className="fld wide">Skip this stage when
                  <input name="stageSkip" value={cur.skip} disabled={!editable || curDef.mandatory} placeholder={curDef.mandatory ? 'Mandatory stage: cannot be skipped' : 'e.g. segment = SALARIED AND amount <= 5,00,000 AND incomeVerifiedViaAA = true'}
                    onChange={e => change(sel, { skip: e.target.value })} />
                </label>
              </div>
              <details className="small" style={{ marginTop: 8 }}>
                <summary>Fields you can use in a skip rule</summary>
                <ul style={{ margin: '6px 0', paddingLeft: 18 }}>
                  {Object.entries(lib.conditions.fields).map(([k, t]) => <li key={k}><span className="mono">{k}</span>: {t}</li>)}
                </ul>
                Combine with AND / OR; operators = != &lt; &lt;= &gt; &gt;=; amounts can be written 5,00,000 or 5L. Examples: {lib.conditions.examples.map(x => <code key={x} style={{ display: 'block' }}>{x}</code>)}
              </details>
              <div className="small" style={{ marginTop: 10 }}>
                Integrations: {cur.integrations.length ? cur.integrations.map(x => <span key={x} className="pill muted" style={{ marginRight: 4 }}>{x}</span>) : '—'}
              </div>
            </div>
          )}

          <div className="panel">
            <h3 style={{ marginTop: 0 }}>Checks</h3>
            {(dirty ? localIssues : issues).length === 0
              ? <div className="alert ok" data-wf-valid>Ready: starts with Lead, ends with Disbursement, KYC before Underwriting before Sanction, SLAs and rules valid.</div>
              : <ul className="wf-issues" data-wf-issues>{(dirty ? localIssues : issues).map(x => <li key={x}>{x}</li>)}</ul>}
            {dirty && <p className="small muted">Unsaved changes: the server checks skip rules again when you save.</p>}
            {changes.length > 0 && <>
              <h4 style={{ marginBottom: 4 }}>Changes from the published version</h4>
              <ul className="small" data-wf-changes style={{ marginTop: 0 }}>{changes.map(x => <li key={x}>{x}</li>)}</ul>
            </>}
          </div>

          {editable && (
            <div className="panel">
              <ErrorBox error={error} />
              {saved && <div className="alert ok" role="status">{saved}</div>}
              <label className="fld">What changed and why (sent to the CRO)<textarea name="wfNotes" rows={2} value={notes} onChange={e => { setNotes(e.target.value); setDirty(true) }} /></label>
              <div className="row" style={{ marginTop: 8 }}>
                <button className="btn" data-wf="save" onClick={save}>Save draft</button>
                <button className="btn primary" data-wf="submit" onClick={submit}>Send for approval</button>
                <button className="btn danger" onClick={discard}>Delete draft</button>
              </div>
            </div>
          )}
          {canDecide && (
            <div className="panel">
              <h3 style={{ marginTop: 0 }}>Your decision</h3>
              <ErrorBox error={error} />
              <label className="fld">Note (required)<textarea name="wfDecisionNote" rows={2} value={decisionNote} onChange={e => setDecisionNote(e.target.value)} /></label>
              <div className="row" style={{ marginTop: 8 }}>
                <button className="btn primary" data-wf="approve" onClick={() => decide(true)}>Approve and publish</button>
                <button className="btn danger" data-wf="reject" onClick={() => decide(false)}>Reject</button>
              </div>
            </div>
          )}
          {v.status === 'PENDING_APPROVAL' && user?.role === 'CRO' && user.username === v.createdBy && <div className="alert warn">You made this version, so another CRO must approve it.</div>}
        </div>
      </div>
    </div>
  )
}

/** Same shape checks as the server, so the designer reacts while editing. */
function localCheck(stages: Stage[], defs: Record<string, LibraryStage>): string[] {
  const e: string[] = []
  if (!stages.length) return ['Add at least the mandatory stages']
  const keys = stages.map(s => s.key)
  const name = (k: string) => defs[k]?.name ?? k
  if (keys[0] !== 'lead') e.push('The workflow must start with Lead & application')
  if (keys[keys.length - 1] !== 'disb') e.push('The workflow must end with Disbursement')
  for (const m of ['kyc', 'uw', 'sanction']) if (!keys.includes(m)) e.push(`${name(m)} is mandatory`)
  const before: [string, string][] = [['kyc', 'uw'], ['docs', 'uw'], ['uw', 'sanction'], ['cam', 'sanction'], ['uw', 'cam'], ['cgt', 'grt'],
    ['field', 'sanction'], ['cgt', 'sanction'], ['grt', 'sanction'], ['val', 'sanction'], ['gold', 'sanction'], ['legal', 'sanction']]
  for (const [a, b] of before) {
    const i = keys.indexOf(a), j = keys.indexOf(b)
    if (i >= 0 && j >= 0 && i > j) e.push(`${name(a)} must come before ${name(b)}`)
  }
  for (const s of stages) {
    if (!s.name?.trim()) e.push(`${name(s.key)}: give the stage a name`)
    if (!(s.sla >= 1 && s.sla <= 720)) e.push(`${name(s.key)}: SLA must be between 1 and 720 hours`)
    if (s.skip?.trim() && defs[s.key]?.mandatory) e.push(`${name(s.key)} is mandatory and cannot have a skip rule`)
  }
  return e
}
