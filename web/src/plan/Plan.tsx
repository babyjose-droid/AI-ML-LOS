import { useEffect, useState } from 'react'
import { api, getToken } from '../api'
import { ErrorBox, Head, KpiStrip, Table, useLoad } from '../components/ui'
import { inr, label, pct, when } from '../format'

// Loan plans: what a housing, education or farm loan pays for (with documents read by AI), the plan checks, and the tranche
// releases after disbursement (construction stages certified by an inspection; term fees paid to the institution).

interface Check { id: string; label: string; result: string; reason: string; ruling?: string }
interface Tranche { id: number; seq: number; label: string; amount: number; stagePct?: number; payeeKind: string; payee?: string; status: string
  certifiedPct?: number; certifiedBy?: string; certifiedAt?: string; certificateNote?: string; paidAmount?: number; paidAt?: string; paidBy?: string; utr?: string
  paidTo?: string; release?: string; releaseChecks?: Check[] }
interface Doc { id: number; docType: string; fileName: string; readConfidence?: number; engine?: string; trancheSeq?: number; uploadedBy: string; uploadedAt: string
  fields: Record<string, unknown> }
interface Party { id: number; code: string; name: string; kind: string; category?: string; accreditation?: string; gstin?: string; city?: string; accountName?: string
  account?: string; ifsc?: string; empanelled: boolean; status: string; notes?: string; paid: number; releases: number }
interface View { kind: 'HOUSING' | 'EDUCATION' | 'AGRI'; product: string; applicant: string; loan: number; tenureMonths: number; sanctioned?: number
  details: Record<string, unknown>; status: string; checks: Check[]; conditions: string[]; documents: Doc[]; tranches: Tranche[]; parties: Party[]; docTypes: string[]
  canEdit: boolean; canUpload: boolean; canCertify: boolean; canRelease: boolean; canRule: boolean; disbursed: boolean }

const ST: Record<string, string> = { PASS: 'ok', REFER: 'warn', FAIL: 'bad', PENDING: 'muted', PAID: 'ok', READY: 'warn', PLANNED: 'muted', ACTIVE: 'ok', SUSPENDED: 'bad' }
const St = ({ s }: { s?: string }) => <span className={'pill ' + (ST[s ?? ''] ?? 'muted')}>{(s ?? '—').replace(/_/g, ' ').toLowerCase()}</span>
const DOC: Record<string, string> = { CONSTRUCTION_ESTIMATE: "Engineer's construction estimate", STAGE_CERTIFICATE: "Engineer's stage certificate", INSURANCE: 'Insurance policy',
  ADMISSION_LETTER: 'Admission letter', FEE_LETTER: 'Fee structure letter', FEE_DEMAND: 'Fee demand for a term', LAND_RECORD_712: 'Land record (7/12 extract)' }
export const PLAN_TITLE: Record<string, string> = { HOUSING: 'Property & stages', EDUCATION: 'Course & fees', AGRI: 'Farm & activity' }

type F = { key: string; label: string; type?: 'text' | 'num' | 'date' | 'select'; options?: [string, string][] }
const FORMS: Record<string, F[]> = {
  HOUSING: [
    { key: 'purpose', label: 'Purpose', type: 'select', options: [['CONSTRUCTION', 'Construction on own plot'], ['PURCHASE', 'Purchase of a house or flat'], ['EXTENSION', 'Extension or improvement']] },
    { key: 'estimate', label: 'Cost (estimate or agreement value, ₹)', type: 'num' }, { key: 'ownContribution', label: 'Own contribution (₹)', type: 'num' },
    { key: 'carpetAreaSqft', label: 'Carpet area (sq ft)', type: 'num' }, { key: 'builtUpAreaSqft', label: 'Built-up area (sq ft)', type: 'num' },
    { key: 'planNo', label: 'Approved plan / building permission no.' }, { key: 'propertyAddress', label: 'Property address' }],
  EDUCATION: [
    { key: 'studentName', label: 'Student' }, { key: 'studentDob', label: "Student's date of birth", type: 'date' },
    { key: 'relationship', label: 'Borrower is the student’s', type: 'select', options: [['PARENT', 'Parent'], ['GUARDIAN', 'Guardian'], ['SPOUSE', 'Spouse'], ['SELF', 'Self (the student)']] },
    { key: 'institutionId', label: 'Institution', type: 'select' }, { key: 'course', label: 'Course' }, { key: 'courseStart', label: 'Course start', type: 'date' },
    { key: 'durationMonths', label: 'Duration (months)', type: 'num' }, { key: 'admissionNo', label: 'Admission no.' }, { key: 'academicYear', label: 'Academic year' }],
  AGRI: [
    { key: 'activity', label: 'Activity', type: 'select', options: [['CROP', 'Crop cultivation'], ['HORTICULTURE', 'Horticulture'], ['DAIRY', 'Dairy'], ['POULTRY', 'Poultry'], ['FARM_EQUIPMENT', 'Farm equipment']] },
    { key: 'crop', label: 'Crops' }, { key: 'landAreaAcres', label: 'Land (acres)', type: 'num' }, { key: 'surveyNo', label: 'Gat / survey no.' }, { key: 'village', label: 'Village' },
    { key: 'scalePerUnit', label: 'Scale of finance (₹ per acre or unit)', type: 'num' }, { key: 'units', label: 'Units (animals, machines)', type: 'num' },
    { key: 'ownContribution', label: 'Own contribution (₹)', type: 'num' }, { key: 'harvestMonths', label: 'Harvest months (repayment)' }, { key: 'insuranceRef', label: 'Insurance (PMFBY / livestock) ref.' }],
}

async function upload(url: string, form: FormData) {
  const t = getToken()
  const r = await fetch(url, { method: 'POST', body: form, headers: t ? { Authorization: 'Bearer ' + t } : {} })
  const b = await r.json().catch(() => ({}))
  if (!r.ok) throw new Error(b.message ?? 'Upload failed')
  return b
}
async function openFile(url: string) {
  const t = getToken()
  const r = await fetch(url, { headers: t ? { Authorization: 'Bearer ' + t } : {} })
  if (r.ok) window.open(URL.createObjectURL(await r.blob()), '_blank')
}

type Row = { label: string; amount: string; stagePct: string }

export function PlanTab({ id, rev, onChange }: { id: string; rev: number; onChange: () => void }) {
  const [r, setR] = useState(0)
  const v = useLoad(() => api.get<View>(`/api/applications/${id}/plan`), [id, rev, r])
  const [form, setForm] = useState<Record<string, string>>({})
  const [rows, setRows] = useState<Row[]>([])
  const [docType, setDocType] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState('')
  const [error, setError] = useState<unknown>()
  const [rule, setRule] = useState<{ check: string; note: string } | null>(null)
  useEffect(() => {
    const d = v.data
    if (!d) return
    setForm(Object.fromEntries(Object.entries(d.details).map(([k, x]) => [k, x == null ? '' : String(x)])))
    setRows(d.tranches.map(t => ({ label: t.label, amount: String(t.amount), stagePct: t.stagePct == null ? '' : String(t.stagePct) })))
    setDocType(x => x || d.docTypes.slice().sort()[0] || '')
  }, [v.data])
  const d = v.data
  if (!d) return v.error ? <ErrorBox error={v.error} /> : <div className="empty">Loading…</div>
  const run = async (f: () => Promise<unknown>, m: string) => {
    setBusy(true)
    try { await f(); setMsg(m); setError(undefined); setR(x => x + 1); onChange() } catch (x) { setError(x) } finally { setBusy(false) }
  }
  const fields = FORMS[d.kind]
  const save = () => {
    const details: Record<string, unknown> = {}
    fields.forEach(f => { const s = (form[f.key] ?? '').trim(); details[f.key] = s === '' ? null : f.type === 'num' ? Number(s.replace(/,/g, '')) : f.key === 'institutionId' ? Number(s) : s })
    const tranches = d.kind === 'AGRI' ? undefined : rows.filter(x => Number(x.amount) > 0).map(x => ({ label: x.label, amount: Number(x.amount), stagePct: x.stagePct === '' ? null : Number(x.stagePct) }))
    return run(() => api.put(`/api/applications/${id}/plan`, { details, tranches }), 'Plan saved and checked')
  }
  const send = (type: string, seq?: number) => {
    if (!file) return
    const f = new FormData(); f.append('file', file); f.append('docType', type)
    if (seq) f.append('trancheSeq', String(seq))
    run(() => upload(`/api/applications/${id}/plan/documents`, f).then(() => setFile(null)), (DOC[type] ?? label(type)) + ' uploaded and read')
  }
  const released = d.tranches.filter(t => t.status === 'PAID').reduce((s, t) => s + t.amount, 0)
  const total = d.sanctioned ?? d.loan
  const inst = d.parties.find(p => String(p.id) === form.institutionId)
  const fld = (f: F) => {
    const val = form[f.key] ?? ''
    const set = (x: string) => setForm({ ...form, [f.key]: x })
    if (f.type === 'select') {
      const opts: [string, string][] = f.key === 'institutionId' ? d.parties.map(p => [String(p.id), p.name + (p.status !== 'ACTIVE' ? ' (' + p.status.toLowerCase() + ')' : '')]) : f.options ?? []
      return <label key={f.key} className="fld">{f.label}<select name={f.key} value={val} disabled={!d.canEdit} onChange={e => set(e.target.value)}><option value="">Choose…</option>{opts.map(([k, l]) => <option key={k} value={k}>{l}</option>)}</select></label>
    }
    return <label key={f.key} className="fld">{f.label}<input name={f.key} type={f.type === 'date' ? 'date' : 'text'} inputMode={f.type === 'num' ? 'decimal' : undefined} value={val} disabled={!d.canEdit} onChange={e => set(e.target.value)} /></label>
  }

  return (
    <div data-plan>
      <ErrorBox error={error} />
      {msg && <div className="alert ok" role="status">{msg}</div>}
      <KpiStrip items={[
        ['Plan', PLAN_TITLE[d.kind], <span data-plan-status={d.status}><St s={d.status} /></span>],
        [d.sanctioned ? 'Sanctioned' : 'Loan asked', inr(total), d.kind === 'AGRI' ? 'half-yearly after harvest' : undefined],
        ...(d.tranches.length ? [['Released', <span data-released={released}>{inr(released)}</span>, total ? pct(released / total) : undefined] as [string, React.ReactNode, React.ReactNode]] : []),
        ...(d.tranches.length ? [['Releases', `${d.tranches.filter(t => t.status === 'PAID').length} of ${d.tranches.length}`]] as [string, React.ReactNode][] : []),
        ['Documents', String(d.documents.length)],
      ]} />

      <div className="panel">
        <h3>{PLAN_TITLE[d.kind]}</h3>
        <div className="grid3">{fields.map(fld)}</div>
        {inst && <p className="small muted">{inst.name} · {inst.accreditation ?? 'not accredited'} · fees paid to {inst.account ?? 'no account on record'} ({inst.ifsc ?? '—'})</p>}
        {d.kind !== 'AGRI' && <>
          <h4 style={{ margin: '14px 0 6px' }}>{d.kind === 'HOUSING' ? 'Construction stages (released with progress)' : 'Term-wise fee releases (paid to the institution)'}</h4>
          <Table head={d.kind === 'HOUSING' ? ['Stage', 'Amount (₹)', 'Progress needed (%)', ''] : ['Release', 'Amount (₹)', '']} rows={rows.map((x, i) => [
            <input aria-label={'Stage ' + (i + 1)} name={'tr-label-' + (i + 1)} value={x.label} disabled={!d.canEdit} onChange={e => setRows(rows.map((y, j) => j === i ? { ...y, label: e.target.value } : y))} />,
            <input aria-label={'Amount ' + (i + 1)} name={'tr-amount-' + (i + 1)} inputMode="decimal" style={{ width: 120 }} value={x.amount} disabled={!d.canEdit} onChange={e => setRows(rows.map((y, j) => j === i ? { ...y, amount: e.target.value.replace(/[^0-9.]/g, '') } : y))} />,
            ...(d.kind === 'HOUSING' ? [<input aria-label={'Progress ' + (i + 1)} name={'tr-pct-' + (i + 1)} inputMode="decimal" style={{ width: 80 }} value={x.stagePct} disabled={!d.canEdit} onChange={e => setRows(rows.map((y, j) => j === i ? { ...y, stagePct: e.target.value.replace(/[^0-9.]/g, '') } : y))} />] : []),
            d.canEdit ? <button className="btn sm" onClick={() => setRows(rows.filter((_, j) => j !== i))}>Remove</button> : ''])} />
          {d.canEdit && <button className="btn sm" style={{ marginTop: 6 }} data-action="add-tranche" onClick={() => setRows([...rows, { label: d.kind === 'EDUCATION' ? `Term ${rows.length + 1} fee` : `Stage ${rows.length + 1}`, amount: '', stagePct: '' }])}>Add {d.kind === 'HOUSING' ? 'stage' : 'release'}</button>}
        </>}
        {d.canEdit && <div className="row" style={{ marginTop: 12 }}><button className="btn primary" disabled={busy} onClick={save}>Save and check</button></div>}
      </div>

      <div className="panel">
        <h3>Plan documents</h3>
        <p className="small muted">Document AI reads each document and fills blank plan fields; the checks compare what was read with the plan.</p>
        <Table head={['Document', 'File', 'Read', 'Uploaded']} empty="No plan documents yet" rows={d.documents.map(x => [
          <span data-plan-doc={x.docType}>{DOC[x.docType] ?? label(x.docType)}{x.trancheSeq ? ` (term ${x.trancheSeq})` : ''}</span>,
          <a href="#" onClick={e => { e.preventDefault(); openFile(`/api/plan-documents/${x.id}/content`) }}>{x.fileName}</a>,
          x.readConfidence != null ? pct(x.readConfidence, 0) + (x.engine ? ' · ' + x.engine : '') : '—', `${x.uploadedBy} · ${when(x.uploadedAt)}`])} />
        {d.canUpload && <div className="row" style={{ marginTop: 10 }}>
          <select aria-label="Plan document type" value={docType} onChange={e => setDocType(e.target.value)}>{d.docTypes.slice().sort().map(t => <option key={t} value={t}>{DOC[t] ?? label(t)}</option>)}</select>
          <input type="file" aria-label="Plan document" accept=".pdf,.jpg,.jpeg,.png" onChange={e => setFile(e.target.files?.[0] ?? null)} />
          <button className="btn" disabled={!file || busy} onClick={() => send(docType)}>Upload</button>
        </div>}
      </div>

      {d.checks.length > 0 && <div className="panel">
        <h3>Plan checks</h3>
        <Table head={['Check', 'Result', 'Why', '']} rows={d.checks.map(c => [
          <span><b>{c.id}</b> {c.label}</span>, <span data-plan-check={c.id} data-result={c.result}><St s={c.result} />{c.ruling && <span className="small muted"> · cleared</span>}</span>,
          <span className="small">{c.reason}{c.ruling && <><br /><em>{c.ruling}</em></>}</span>,
          d.canRule && c.result === 'REFER' && !c.ruling ? <button className="btn sm" onClick={() => setRule({ check: c.id, note: '' })}>Approve exception</button> : ''])} />
        {rule && <div className="row" style={{ marginTop: 8 }}>
          <input aria-label="Exception reason" style={{ flex: 1 }} placeholder={`Why ${rule.check} can be accepted (15+ characters)`} value={rule.note} onChange={e => setRule({ ...rule, note: e.target.value })} />
          <button className="btn primary" disabled={rule.note.trim().length < 15 || busy} onClick={() => run(() => api.post(`/api/applications/${id}/plan/exceptions`, rule).then(() => setRule(null)), 'Exception recorded')}>Record</button>
        </div>}
        {d.conditions.length > 0 && <><h4>Conditions</h4><ul className="small">{d.conditions.map(x => <li key={x}>{x}</li>)}</ul></>}
      </div>}

      {d.disbursed && d.tranches.length > 0 && <div className="panel" data-releases>
        <h3>Releases</h3>
        {d.tranches.map(t => <ReleaseRow key={t.id + ':' + r} t={t} d={d} busy={busy} run={run} upload={(seq) => send('FEE_DEMAND', seq)} file={file} setFile={setFile} />)}
      </div>}
    </div>
  )
}

function ReleaseRow({ t, d, busy, run, upload, file, setFile }: { t: Tranche; d: View; busy: boolean; run: (f: () => Promise<unknown>, m: string) => Promise<void>
  upload: (seq: number) => void; file: File | null; setFile: (f: File | null) => void }) {
  const [p, setP] = useState('')
  const [note, setNote] = useState('')
  const next = d.tranches.find(x => x.status !== 'PAID')?.id === t.id
  return (
    <div className="panel" data-tranche={t.seq} style={{ marginTop: 8 }}>
      <div className="row" style={{ justifyContent: 'space-between' }}>
        <b>{t.seq}. {t.label} · {inr(t.amount)}{t.stagePct ? ` · at ${t.stagePct}% progress` : ''}</b>
        <span data-tranche-status={t.status}><St s={t.status} /></span>
      </div>
      {t.status === 'PAID' && <p className="small" style={{ margin: '6px 0 0' }}>Paid {inr(t.paidAmount ?? t.amount)} to {t.paidTo} · UTR {t.utr} · {t.paidBy} {when(t.paidAt)}</p>}
      {t.certifiedPct != null && <p className="small" style={{ margin: '6px 0 0' }} data-certified>Inspection: {t.certifiedPct}% complete · {t.certifiedBy} {when(t.certifiedAt)} · {t.certificateNote}</p>}
      {t.releaseChecks && <Table head={['Check', 'Result', 'Why']} rows={t.releaseChecks.map(c => [<span><b>{c.id}</b> {c.label}</span>, <span data-release-check={c.id} data-result={c.result}><St s={c.result} /></span>, <span className="small">{c.reason}</span>])} />}
      {next && t.status !== 'PAID' && <div className="row" style={{ marginTop: 8, flexWrap: 'wrap' }}>
        {d.kind === 'HOUSING' && d.canCertify && <>
          <input aria-label="Progress certified (%)" inputMode="decimal" style={{ width: 90 }} placeholder="%" value={p} onChange={e => setP(e.target.value.replace(/[^0-9.]/g, ''))} />
          <input aria-label="Inspection note" style={{ flex: 1 }} placeholder="What was seen at the site (10+ characters)" value={note} onChange={e => setNote(e.target.value)} />
          <button className="btn" disabled={!Number(p) || note.trim().length < 10 || busy} onClick={() => run(() => api.post(`/api/tranches/${t.id}/certify`, { percent: Number(p), note }), 'Inspection recorded')}>Record inspection</button>
        </>}
        {d.kind === 'EDUCATION' && d.canUpload && t.release !== 'PASS' && <>
          <input type="file" aria-label="Fee demand" accept=".pdf,.jpg,.jpeg,.png" onChange={e => setFile(e.target.files?.[0] ?? null)} />
          <button className="btn" disabled={!file || busy} onClick={() => upload(t.seq)}>Upload fee demand</button>
        </>}
        {d.canRelease && t.release === 'PASS' && <button className="btn primary" disabled={busy} onClick={() => run(() => api.post(`/api/tranches/${t.id}/release`), 'Release paid')}>Release {inr(t.amount)}</button>}
      </div>}
    </div>
  )
}

// ---------------------------------------------------------------- Institutions & builders (Admin)

type PF = Record<string, string>
const EMPTY: PF = { code: '', name: '', kind: 'INSTITUTION', category: '', accreditation: '', gstin: '', city: '', accountName: '', accountNo: '', ifsc: '', empanelled: 'true', status: 'ACTIVE', notes: '' }

export function InstitutionsPage() {
  const [r, setR] = useState(0)
  const list = useLoad(() => api.get<Party[]>('/api/plan-parties'), [r])
  const [f, setF] = useState<(PF & { id?: string }) | null>(null)
  const [error, setError] = useState<unknown>()
  const save = async () => {
    if (!f) return
    const body = { ...f, empanelled: f.empanelled === 'true', accountNo: f.accountNo || undefined }
    try { if (f.id) await api.put(`/api/plan-parties/${f.id}`, body); else await api.post('/api/plan-parties', body); setF(null); setError(undefined); setR(x => x + 1) } catch (x) { setError(x) }
  }
  const txt = (k: string, l: string) => <label className="fld">{l}<input name={k} value={f![k] ?? ''} onChange={e => setF({ ...f!, [k]: e.target.value })} /></label>
  const sel = (k: string, l: string, o: string[]) => <label className="fld">{l}<select name={k} value={f![k]} onChange={e => setF({ ...f!, [k]: e.target.value })}>{o.map(x => <option key={x}>{x}</option>)}</select></label>
  return (
    <>
      <Head title="Institutions & builders" sub="Approved institutions that education loans pay fees to, and builders that housing loans can pay. Releases go only to approved, active payees with an account on record." />
      <ErrorBox error={error ?? list.error} />
      <div className="row" style={{ marginBottom: 10 }}><button className="btn primary" onClick={() => setF({ ...EMPTY })}>Add institution or builder</button></div>
      {f && <div className="panel">
        <h3>{f.id ? 'Edit ' + f.name : 'New institution or builder'}</h3>
        <div className="grid3">
          {!f.id && txt('code', 'Code')}{txt('name', 'Name')}{sel('kind', 'Kind', ['INSTITUTION', 'BUILDER'])}{txt('category', 'Category')}{txt('accreditation', 'Accreditation / RERA no.')}
          {txt('gstin', 'GSTIN')}{txt('city', 'City')}{txt('accountName', 'Account name')}{txt('accountNo', f.id ? 'Account no (blank keeps current)' : 'Account no')}{txt('ifsc', 'IFSC')}
          {sel('empanelled', 'Approved', ['true', 'false'])}{sel('status', 'Status', ['ACTIVE', 'SUSPENDED'])}
        </div>
        {txt('notes', 'Notes')}
        <div className="row" style={{ marginTop: 10 }}><button className="btn primary" onClick={save}>Save</button><button className="btn" onClick={() => setF(null)}>Cancel</button></div>
      </div>}
      <Table head={['Name', 'Kind', 'Accreditation', 'Pays to', 'Released', 'Status', '']} empty="None yet" rows={(list.data ?? []).map(p => [
        <span>{p.name}<br /><span className="small muted">{p.code} · {p.city ?? ''}</span></span>, `${label(p.kind)}${p.category ? ' · ' + label(p.category) : ''}`, p.accreditation ?? '—',
        p.account ? `${p.account} · ${p.ifsc}` : '—', `${inr(p.paid)} · ${p.releases} release(s)`,
        <span><St s={p.status} />{!p.empanelled && <span className="pill warn">not approved</span>}</span>,
        <button className="btn sm" onClick={() => setF({ ...EMPTY, ...Object.fromEntries(Object.entries(p).map(([k, v]) => [k, v == null ? '' : String(v)])), accountNo: '' })}>Edit</button>])} />
    </>
  )
}
