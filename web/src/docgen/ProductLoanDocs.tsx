import { useState } from 'react'
import { api } from '../api'
import { useAuth } from '../auth'
import { ErrorBox, Table, useLoad } from '../components/ui'
import { CONDITION_LABEL, SIGNER_LABEL, STAGE_LABEL } from './common'

// Products → Signing pack: which approved templates this product uses, when they are made, who signs, and whether
// disbursement waits until they are signed.

interface Line { templateCode: string; stage: string; signers: string[]; condition: string; required: boolean; name?: string; languages?: string[] }
interface View { product: string; gate: boolean; documents: Line[]; stages: string[]; conditions: string[]; signers: string[] }
interface Lib { templates: { code: string; name: string; docType: string; versions: { status: string }[] }[] }

export function ProductLoanDocs({ code }: { code: string }) {
  const { user } = useAuth()
  const [r, setR] = useState(0)
  const v = useLoad(() => api.get<View>(`/api/products/${code}/documents`), [code, r])
  const canEdit = !!user && ['ADMIN', 'CRO', 'COMPLIANCE'].includes(user.role)
  const lib = useLoad(() => canEdit ? api.get<Lib>('/api/doc-templates') : Promise.resolve({ templates: [] } as Lib), [canEdit])
  const [lines, setLines] = useState<Line[] | null>(null)
  const [gate, setGate] = useState<boolean | null>(null)
  const [note, setNote] = useState('')
  const [add, setAdd] = useState('')
  const [error, setError] = useState<unknown>()
  const [msg, setMsg] = useState<string>()
  if (!v.data) return <ErrorBox error={v.error} />
  const d = v.data
  const rows = lines ?? d.documents
  const g = gate ?? d.gate
  const edit = (i: number, patch: Partial<Line>) => setLines(rows.map((x, j) => j === i ? { ...x, ...patch } : x))
  const available = (lib.data?.templates ?? []).filter(t => t.docType !== 'CLAUSE' && t.versions.some(x => x.status === 'APPROVED') && !rows.some(x => x.templateCode === t.code))
  async function save() {
    setError(undefined); setMsg(undefined)
    try {
      await api.put(`/api/products/${code}/documents`, { gate: g, documents: rows.map(x => ({ templateCode: x.templateCode, stage: x.stage, signers: x.signers, condition: x.condition, required: x.required })), note })
      setLines(null); setGate(null); setNote(''); setR(x => x + 1); setMsg('Saved')
    } catch (e) { setError(e) }
  }
  return (
    <div data-testid="product-loan-docs">
      <p className="small muted">Documents made from the approved templates (Admin → Document templates) for every application of this product, in the borrower's language when a translation is approved.</p>
      <label className="check"><input type="checkbox" name="docGate" disabled={!canEdit} checked={g} onChange={e => setGate(e.target.checked)} /> Disbursement waits until the required documents are made, up to date and signed</label>
      <Table head={['Document', 'When', 'Who signs', 'Only', 'Required', '']} rows={rows.map((x, i) => [
        <span><b>{x.name ?? x.templateCode}</b><div className="small muted mono">{x.templateCode}{x.languages?.length ? ' · ' + x.languages.join(', ') : ''}</div></span>,
        canEdit ? <select aria-label={`When ${x.templateCode}`} value={x.stage} onChange={e => edit(i, { stage: e.target.value, signers: ['POST_DISBURSE', 'CLOSURE'].includes(e.target.value) ? [] : x.signers })}>
          {d.stages.map(s => <option key={s} value={s}>{STAGE_LABEL[s] ?? s}</option>)}</select> : STAGE_LABEL[x.stage],
        canEdit ? <span className="row" style={{ gap: 6 }}>{['BORROWER', 'CO_APPLICANT', 'GUARANTOR'].map(s => <label key={s} className="check small">
          <input type="checkbox" disabled={['POST_DISBURSE', 'CLOSURE'].includes(x.stage)} checked={x.signers.includes(s)}
            onChange={e => edit(i, { signers: e.target.checked ? [...x.signers, s] : x.signers.filter(y => y !== s) })} /> {SIGNER_LABEL[s]}</label>)}</span>
          : x.signers.map(s => SIGNER_LABEL[s]).join(', ') || '—',
        canEdit ? <select aria-label={`Condition ${x.templateCode}`} value={x.condition} onChange={e => edit(i, { condition: e.target.value })}>
          {d.conditions.map(c => <option key={c} value={c}>{CONDITION_LABEL[c] ?? c}</option>)}</select> : CONDITION_LABEL[x.condition],
        canEdit ? <input type="checkbox" aria-label={`Required ${x.templateCode}`} checked={x.required} onChange={e => edit(i, { required: e.target.checked })} /> : x.required ? 'Yes' : 'No',
        canEdit ? <button className="btn sm danger" onClick={() => setLines(rows.filter((_, j) => j !== i))}>Remove</button> : '',
      ])} empty="No documents for this product." />
      {canEdit && <>
        <div className="row" style={{ marginTop: 8 }}>
          <select aria-label="Add a document" value={add} onChange={e => setAdd(e.target.value)}><option value="">Add a document…</option>
            {available.map(t => <option key={t.code} value={t.code}>{t.name}</option>)}</select>
          <button className="btn sm" disabled={!add} onClick={() => { setLines([...rows, { templateCode: add, name: available.find(t => t.code === add)?.name, stage: 'PRE_DISBURSE', signers: ['BORROWER'], condition: 'ALWAYS', required: true }]); setAdd('') }}>Add</button>
        </div>
        <label className="fld" style={{ marginTop: 8 }}>What changed and why<input name="docsNote" value={note} onChange={e => setNote(e.target.value)} /></label>
        <button className="btn primary" style={{ marginTop: 8 }} data-action="saveProductDocs" disabled={lines === null && gate === null} onClick={save}>Save documents</button>
      </>}
      <ErrorBox error={error} />
      {msg && <div className="alert ok" role="status">{msg}</div>}
    </div>
  )
}
