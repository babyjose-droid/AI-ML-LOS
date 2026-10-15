import { useEffect, useRef, useState } from 'react'
import { api } from '../api'
import { useAuth } from '../auth'
import { ErrorBox, Head, Modal, Pill, useLoad } from '../components/ui'
import { label, when } from '../format'
import { LANGS } from '../i18n/kfs'
import { TYPE_LABEL, frameDoc, openPdf } from './common'

// Admin → Document templates: the lender's loan documents and clause library. Draft in the browser or import a Word
// file with {{fields}} typed in, check, preview on a real application, send for approval (compliance or the CRO approves;
// the drafter cannot), and products use the approved version.

interface Version { id: number; language: string; version: number; status: string; source: string; createdBy: string; createdAt: string; decidedBy?: string; decidedAt?: string }
interface Entry { code: string; name: string; docType: string; products: number; versions: Version[] }
interface Field { path: string; label: string; sample?: string; items: string[] }
interface Library { templates: Entry[]; types: string[]; languages: string[]; fields: Field[]; builtIn: string[]; renderer: string }
interface Template { id: number; code: string; name: string; docType: string; language: string; version: number; status: string; body: string; source: string; notes?: string
  createdBy: string; createdAt: string; submittedAt?: string; decidedBy?: string; decidedAt?: string; decisionNote?: string }
interface Detail { template: Template; problems: string[]; history: Version[]; usedBy: string[] }

const BUILT_IN_LABEL: Record<string, string> = {
  KFS_STANDARD: "RBI's standard KFS table (fixed)", SCHEDULE_TABLE: 'Repayment schedule table', PARTIES_TABLE: 'Borrower, co-applicants and guarantors table',
  COLLATERAL_TABLE: 'Security table', SIGNATURES: 'Signature lines for everyone who signs',
}

export default function DocTemplates() {
  const { user } = useAuth()
  const [r, setR] = useState(0)
  const lib = useLoad(() => api.get<Library>('/api/doc-templates'), [r])
  const [sel, setSel] = useState<number | null>(null)
  const [dlg, setDlg] = useState<'new' | 'word' | null>(null)
  const [filter, setFilter] = useState<'docs' | 'clauses'>('docs')
  const canMake = !!user && ['ADMIN', 'COMPLIANCE', 'CREDIT_MANAGER', 'LEGAL_REVIEWER', 'CRO'].includes(user.role)
  if (!lib.data) return <ErrorBox error={lib.error} />
  const d = lib.data
  const shown = d.templates.filter(t => (t.docType === 'CLAUSE') === (filter === 'clauses'))
  const current = sel ?? shown[0]?.versions[0]?.id ?? null
  return (
    <>
      <Head title="Document templates" sub="Loan documents and clauses with fields that fill in from each application. Compliance or the CRO approves every version; products use the approved one.">
        {canMake && <button className="btn" data-doctpl="word" onClick={() => setDlg('word')}>Upload Word template</button>}
        {canMake && <button className="btn primary" data-doctpl="new" onClick={() => setDlg('new')}>New template</button>}
      </Head>
      {d.renderer === 'BASIC-PDF' && <div className="alert warn">The AI document service is not connected: PDFs are plain text and Word upload is off. Set DOCUMENT_AI_URL.</div>}
      <div className="policy-layout">
        <div className="panel flush">
          <div className="tabs" role="tablist" style={{ padding: '8px 10px 0' }}>
            <button role="tab" aria-selected={filter === 'docs'} className={filter === 'docs' ? 'on' : ''} onClick={() => { setFilter('docs'); setSel(null) }}>Documents</button>
            <button role="tab" aria-selected={filter === 'clauses'} className={filter === 'clauses' ? 'on' : ''} onClick={() => { setFilter('clauses'); setSel(null) }}>Clause library</button>
          </div>
          <ul className="doc-list" data-testid="doc-template-list" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
            {shown.map(t => <li key={t.code} style={{ padding: '8px 12px', borderTop: '1px solid var(--line, #e5e5e5)' }}>
              <b>{t.name}</b> <span className="small muted mono">{t.code}</span>
              <div className="small muted">{TYPE_LABEL[t.docType] ?? t.docType}{t.docType !== 'CLAUSE' ? ` · ${t.products} product(s)` : ''}</div>
              <div className="row" style={{ gap: 4, marginTop: 4 }}>{t.versions.map(v => <button key={v.id} data-doctpl-version={`${t.code}-${v.language}-${v.version}`}
                className={'btn sm' + (v.id === current ? ' primary' : '')} onClick={() => setSel(v.id)}>{v.language} v{v.version} · {label(v.status).toLowerCase()}</button>)}</div>
            </li>)}
          </ul>
        </div>
        <div style={{ minWidth: 0 }}>{current && <TemplateEditor key={current + ':' + r} id={current} lib={d} onChange={id => { if (id) setSel(id); setR(x => x + 1) }} />}</div>
      </div>
      {dlg === 'new' && <NewTemplate lib={d} onClose={() => setDlg(null)} onDone={id => { setDlg(null); setSel(id); setR(x => x + 1) }} />}
      {dlg === 'word' && <WordUpload lib={d} onClose={() => setDlg(null)} onDone={id => { setDlg(null); setSel(id); setR(x => x + 1) }} />}
    </>
  )
}

function TemplateEditor({ id, lib, onChange }: { id: number; lib: Library; onChange: (id?: number) => void }) {
  const { user } = useAuth()
  const det = useLoad(() => api.get<Detail>(`/api/doc-templates/${id}`), [id])
  const [body, setBody] = useState<string>()
  const [name, setName] = useState<string>()
  const [notes, setNotes] = useState<string>()
  const [mode, setMode] = useState<'visual' | 'source'>('visual')
  const [appId, setAppId] = useState('')
  const [preview, setPreview] = useState<{ html: string; missing: string[]; problems: string[]; with?: string }>()
  const [decision, setDecision] = useState('')
  const [error, setError] = useState<unknown>()
  const [msg, setMsg] = useState<string>()
  const visual = useRef<HTMLDivElement>(null)
  const source = useRef<HTMLTextAreaElement>(null)
  const t = det.data?.template
  useEffect(() => { if (t) { setBody(t.body); setName(t.name); setNotes(t.notes ?? '') } }, [t])
  useEffect(() => { if (mode === 'visual' && visual.current && body !== undefined && visual.current.innerHTML !== body) visual.current.innerHTML = body }, [mode, body === undefined, id])
  if (!det.data || !t || body === undefined) return <ErrorBox error={det.error} />
  const editable = ['DRAFT', 'REJECTED'].includes(t.status) && !!user && ['ADMIN', 'COMPLIANCE', 'CREDIT_MANAGER', 'LEGAL_REVIEWER', 'CRO'].includes(user.role)
  const checker = !!user && ['COMPLIANCE', 'CRO'].includes(user.role) && t.status === 'PENDING_APPROVAL' && user.username !== t.createdBy
  const canMake = !!user && ['ADMIN', 'COMPLIANCE', 'CREDIT_MANAGER', 'LEGAL_REVIEWER', 'CRO'].includes(user.role)

  const sync = () => { if (mode === 'visual' && visual.current) setBody(visual.current.innerHTML) }
  function insert(text: string) {
    if (!editable) return
    if (mode === 'source' && source.current) {
      const el = source.current, s = el.selectionStart, e = el.selectionEnd
      const next = body!.slice(0, s) + text + body!.slice(e)
      setBody(next)
      requestAnimationFrame(() => { el.focus(); el.selectionStart = el.selectionEnd = s + text.length })
    } else {
      visual.current?.focus()
      document.execCommand('insertText', false, text)
      sync()
    }
  }
  function cmd(c: string, v?: string) { visual.current?.focus(); document.execCommand(c, false, v); sync() }
  async function run(fn: () => Promise<unknown>, done?: string) {
    setError(undefined); setMsg(undefined)
    try { await fn(); if (done) setMsg(done) } catch (e) { setError(e) }
  }
  const saveRaw = async () => { sync(); await api.put(`/api/doc-templates/${id}`, { body: mode === 'visual' && visual.current ? visual.current.innerHTML : body, name, notes }) }
  const save = () => run(async () => { await saveRaw(); det.reload() }, 'Saved')
  const doPreview = () => run(async () => {
    sync()
    setPreview(await api.post('/api/doc-templates/preview', { templateId: id, body: mode === 'visual' && visual.current ? visual.current.innerHTML : body, applicationId: appId ? Number(appId) : null }))
  })
  const scalarFields = lib.fields.filter(f => f.items.length === 0 && f.path !== 'sanction.conditions')
  const lists = lib.fields.filter(f => f.items.length > 0 || f.path === 'sanction.conditions')
  const clauses = lib.templates.filter(x => x.docType === 'CLAUSE').map(x => x.code)

  return (
    <div className="panel" data-doctpl-editor={`${t.code}-${t.language}-${t.version}`}>
      <div className="row" style={{ justifyContent: 'space-between' }}>
        <h3 style={{ margin: 0 }}>{t.name} <span className="small muted mono">{t.code} · {t.language} v{t.version}</span> <Pill s={t.status} /></h3>
        <span className="small muted">{TYPE_LABEL[t.docType] ?? t.docType} · {t.source === 'WORD' ? 'from Word' : t.source === 'SYSTEM' ? 'starter template' : 'browser'} · by {t.createdBy} {when(t.createdAt)}
          {t.decidedBy && <> · {label(t.status).toLowerCase()} by {t.decidedBy} {when(t.decidedAt!)}</>}</span>
      </div>
      {det.data.usedBy.length > 0 && <p className="small muted" style={{ margin: '4px 0' }}>Used by {det.data.usedBy.join(', ')}</p>}
      {t.decisionNote && <p className="small">Decision note: {t.decisionNote}</p>}
      <ErrorBox error={error} />
      {msg && <div className="alert ok" role="status">{msg}</div>}
      {det.data.problems.length > 0 && <div className="alert warn" data-testid="doctpl-problems"><b>Fix before sending for approval:</b><ul style={{ margin: 0 }}>{det.data.problems.map(p => <li key={p}>{p}</li>)}</ul></div>}

      {editable && <label className="fld">Name<input name="tplName" value={name ?? ''} onChange={e => setName(e.target.value)} /></label>}
      {editable && <div className="row" style={{ gap: 4, margin: '8px 0', flexWrap: 'wrap' }} data-testid="doctpl-toolbar">
        {mode === 'visual' && <>
          <button className="btn sm" title="Bold" onClick={() => cmd('bold')}><b>B</b></button>
          <button className="btn sm" title="Italic" onClick={() => cmd('italic')}><i>I</i></button>
          <button className="btn sm" title="Underline" onClick={() => cmd('underline')}><u>U</u></button>
          <button className="btn sm" onClick={() => cmd('formatBlock', 'H1')}>Title</button>
          <button className="btn sm" onClick={() => cmd('formatBlock', 'H2')}>Heading</button>
          <button className="btn sm" onClick={() => cmd('formatBlock', 'P')}>Text</button>
          <button className="btn sm" onClick={() => cmd('insertOrderedList')}>1. List</button>
          <button className="btn sm" onClick={() => cmd('insertUnorderedList')}>• List</button>
          <button className="btn sm" onClick={() => cmd('insertHTML', '<table><tr><th>Item</th><th>Detail</th></tr><tr><td></td><td></td></tr></table><p></p>')}>Table</button>
          <button className="btn sm" onClick={() => cmd('insertHTML', '<div class="pagebreak"></div><p></p>')}>Page break</button>
        </>}
        <select aria-label="Insert a field" value="" onChange={e => { if (e.target.value) insert(e.target.value) }} style={{ maxWidth: 220 }}>
          <option value="">Insert a field…</option>
          {scalarFields.map(f => <option key={f.path} value={`{{${f.path}${/amount|Fee|gst|emi|insurance|netDisbursed|total|Income|value/i.test(f.path) && !/tenure|Days/.test(f.path) ? ' | rupees' : /date|dob|today/i.test(f.path) ? ' | date' : ''}}}`}>{f.label}</option>)}
        </select>
        <select aria-label="Insert a repeated section" value="" onChange={e => { const l = lists.find(x => x.path === e.target.value); if (l) insert(`{{#each ${l.path}}}${l.items.length ? l.items.slice(0, 2).map(i => `{{${i}}}`).join(' ') : '{{this}}'}{{/each}}`) }} style={{ maxWidth: 220 }}>
          <option value="">Insert a repeated section…</option>
          {lists.map(f => <option key={f.path} value={f.path}>{f.label}</option>)}
        </select>
        <select aria-label="Insert a condition" value="" onChange={e => { if (e.target.value) insert(e.target.value) }} style={{ maxWidth: 200 }}>
          <option value="">Insert a condition…</option>
          <option value={'{{#if guarantors}}Only when there is a guarantor{{/if}}'}>Only with a guarantor</option>
          <option value={'{{#if coApplicants}}Only when there is a co-applicant{{/if}}'}>Only with a co-applicant</option>
          <option value={'{{#if collaterals}}Only for secured loans{{else}}Only for unsecured loans{{/if}}'}>Secured / unsecured</option>
          <option value={'{{#if loan.amount > 1000000}}Only above ₹10 lakh{{/if}}'}>Above an amount</option>
        </select>
        <select aria-label="Insert a clause or block" value="" onChange={e => { if (e.target.value) insert(`{{> ${e.target.value}}}`) }} style={{ maxWidth: 220 }}>
          <option value="">Insert a clause or block…</option>
          {lib.builtIn.map(b => <option key={b} value={b}>{BUILT_IN_LABEL[b] ?? b}</option>)}
          {clauses.map(c => <option key={c} value={c}>{lib.templates.find(x => x.code === c)?.name ?? c}</option>)}
        </select>
        <button className="btn sm" onClick={() => { if (mode === 'visual') { sync(); setMode('source') } else setMode('visual') }}>{mode === 'visual' ? 'Edit HTML' : 'Visual editor'}</button>
      </div>}
      {mode === 'visual' || !editable
        ? <div ref={visual} className="doc-edit" contentEditable={editable} suppressContentEditableWarning data-testid="doctpl-visual" onBlur={sync}
            style={{ border: '1px solid var(--line, #ccc)', borderRadius: 6, padding: 16, minHeight: 280, maxHeight: 560, overflow: 'auto', background: 'var(--panel, #fff)' }}
            dangerouslySetInnerHTML={editable ? undefined : { __html: body }} />
        : <textarea ref={source} name="tplSource" className="mono" rows={22} value={body} onChange={e => setBody(e.target.value)} style={{ width: '100%', fontSize: 12 }} />}
      <p className="small muted">Fields look like <code>{'{{applicant.name}}'}</code>; formats: <code>| rupees</code> <code>| words</code> <code>| date</code> <code>| percent</code> <code>| upper</code>.
        Repeat with <code>{'{{#each coApplicants}}…{{/each}}'}</code>, show conditionally with <code>{'{{#if guarantors}}…{{else}}…{{/if}}'}</code>, include a clause with <code>{'{{> CL_INTEREST}}'}</code>.
        Repeated rows inside tables are easiest in "Edit HTML".</p>

      <div className="row" style={{ marginTop: 8 }}>
        <label className="small">Preview with application id <input name="previewApp" inputMode="numeric" style={{ width: 90 }} value={appId} onChange={e => setAppId(e.target.value.replace(/\D/g, ''))} placeholder="sample" /></label>
        <button className="btn" data-doctpl="preview" onClick={doPreview}>Preview</button>
        <button className="btn" onClick={() => run(() => openPdf('/api/doc-templates/preview.pdf', { templateId: id, body: mode === 'visual' && visual.current ? visual.current.innerHTML : body, applicationId: appId ? Number(appId) : null }))}>PDF</button>
      </div>
      {preview && <div style={{ marginTop: 8 }} data-testid="doctpl-preview">
        <p className="small muted">Filled with {preview.with ?? 'sample values'}.{preview.missing.length > 0 && <> Empty for this application: {preview.missing.join(', ')}.</>}</p>
        {preview.problems.length > 0 && <ul className="small warn-text">{preview.problems.map(p => <li key={p}>{p}</li>)}</ul>}
        {preview.html && <iframe title="Preview" sandbox="" srcDoc={frameDoc(preview.html)} style={{ width: '100%', height: 520, border: '1px solid var(--line, #ccc)', borderRadius: 6, background: '#fff' }} />}
      </div>}

      {editable && <>
        <label className="fld" style={{ marginTop: 10 }}>What changed and why (for the approver)<textarea name="tplNotes" rows={2} value={notes ?? ''} onChange={e => setNotes(e.target.value)} /></label>
        <div className="row" style={{ marginTop: 8 }}>
          <button className="btn" data-doctpl="save" onClick={save}>Save draft</button>
          <button className="btn primary" data-doctpl="submit" onClick={() => run(async () => { await saveRaw(); await api.post(`/api/doc-templates/${id}/submit`); det.reload() }, 'Sent for approval')}>Send for approval</button>
          <button className="btn danger" onClick={() => run(async () => { await api.del(`/api/doc-templates/${id}`); onChange() })}>Delete draft</button>
        </div>
      </>}
      {checker && <div className="panel" style={{ marginTop: 10 }}>
        <label className="fld">Your decision note<input name="tplDecision" value={decision} onChange={e => setDecision(e.target.value)} /></label>
        <div className="row" style={{ marginTop: 6 }}>
          <button className="btn primary" data-doctpl="approve" onClick={() => run(async () => { await api.post(`/api/doc-templates/${id}/decision`, { approve: true, note: decision }); onChange(id) })}>Approve</button>
          <button className="btn danger" onClick={() => run(async () => { await api.post(`/api/doc-templates/${id}/decision`, { approve: false, note: decision }); onChange(id) })}>Reject</button>
        </div>
      </div>}
      {!editable && canMake && ['APPROVED', 'RETIRED'].includes(t.status) && <div className="row" style={{ marginTop: 10 }}>
        <button className="btn" data-doctpl="new-version" onClick={() => run(async () => { const n = await api.post<Template>('/api/doc-templates', { fromId: id, notes: '' }); onChange(n.id) })}>Make a new version</button>
        <LanguageCopy id={id} lib={lib} onDone={onChange} onError={setError} />
      </div>}
      {det.data.history.length > 1 && <details className="small" style={{ marginTop: 10 }}><summary>Versions in {t.language}</summary>
        <ul>{det.data.history.map(h => <li key={h.id}>v{h.version} · {label(h.status).toLowerCase()} · {h.createdBy} {when(h.createdAt)}{h.decidedBy ? ` · ${h.decidedBy}` : ''}</li>)}</ul></details>}
    </div>
  )
}

function LanguageCopy({ id, lib, onDone, onError }: { id: number; lib: Library; onDone: (id?: number) => void; onError: (e: unknown) => void }) {
  const [lang, setLang] = useState('')
  return <span className="row" style={{ gap: 4 }}>
    <select aria-label="Translate into" value={lang} onChange={e => setLang(e.target.value)}><option value="">Another language…</option>
      {lib.languages.map(l => <option key={l} value={l}>{LANGS[l] ?? l}</option>)}</select>
    <button className="btn" disabled={!lang} onClick={async () => { try { const n = await api.post<Template>('/api/doc-templates', { fromId: id, language: lang, notes: '' }); onDone(n.id) } catch (e) { onError(e) } }}>Start a translation</button>
  </span>
}

function NewTemplate({ lib, onClose, onDone }: { lib: Library; onClose: () => void; onDone: (id: number) => void }) {
  const [f, setF] = useState({ code: '', name: '', docType: 'LOAN_AGREEMENT', language: 'en' })
  const [error, setError] = useState<unknown>()
  return (
    <Modal title="New template" onClose={onClose}>
      <ErrorBox error={error} />
      <div className="form-grid">
        <label className="fld">Type<select name="newType" value={f.docType} onChange={e => setF({ ...f, docType: e.target.value, code: e.target.value === 'CLAUSE' && !f.code.startsWith('CL_') ? 'CL_' : f.code })}>
          {lib.types.map(t => <option key={t} value={t}>{TYPE_LABEL[t] ?? t}</option>)}</select></label>
        <label className="fld">Code (capitals; clauses start CL_)<input name="newCode" value={f.code} onChange={e => setF({ ...f, code: e.target.value.toUpperCase().replace(/[^A-Z0-9_]/g, '') })} placeholder="LAP_AGREEMENT" /></label>
        <label className="fld">Name<input name="newName" value={f.name} onChange={e => setF({ ...f, name: e.target.value })} /></label>
        <label className="fld">Language<select value={f.language} onChange={e => setF({ ...f, language: e.target.value })}>{lib.languages.map(l => <option key={l} value={l}>{LANGS[l] ?? l}</option>)}</select></label>
      </div>
      <button className="btn primary" style={{ marginTop: 10 }} data-doctpl="create" onClick={async () => { try { onDone((await api.post<Template>('/api/doc-templates', f)).id) } catch (e) { setError(e) } }}>Create draft</button>
    </Modal>
  )
}

function WordUpload({ lib, onClose, onDone }: { lib: Library; onClose: () => void; onDone: (id: number) => void }) {
  const [f, setF] = useState({ code: '', name: '', docType: 'LOAN_AGREEMENT', language: 'en' })
  const [file, setFile] = useState<File>()
  const [res, setRes] = useState<{ template: Template; fields: string[]; messages: string[]; problems: string[] }>()
  const [error, setError] = useState<unknown>()
  async function upload() {
    if (!file) return
    const form = new FormData()
    form.append('file', file)
    const existing = lib.templates.find(t => t.code === f.code)
    Object.entries(f).forEach(([k, v]) => { if (v && !(existing && (k === 'docType' || k === 'name'))) form.append(k, v) })
    if (existing) form.append('fromId', String(existing.versions.find(v => v.language === f.language)?.id ?? existing.versions[0].id))
    try { setRes(await api.upload('/api/doc-templates/word', form)) } catch (e) { setError(e) }
  }
  return (
    <Modal title="Upload a Word template" onClose={onClose}>
      <ErrorBox error={error} />
      {!res ? <>
        <p className="small">Type the fields straight into your Word document, for example <code>{'{{applicant.name}}'}</code>, <code>{'{{loan.amount | rupees}}'}</code> or <code>{'{{#each coApplicants}}{{name}}{{/each}}'}</code>.
          Headings, bold, lists and tables come across. Use an existing code to make a new version of that template.</p>
        <div className="form-grid">
          <label className="fld">Word file (.docx)<input type="file" name="wordFile" accept=".docx" onChange={e => setFile(e.target.files?.[0])} /></label>
          <label className="fld">Code<input name="wordCode" list="tpl-codes" value={f.code} onChange={e => setF({ ...f, code: e.target.value.toUpperCase().replace(/[^A-Z0-9_]/g, '') })} />
            <datalist id="tpl-codes">{lib.templates.map(t => <option key={t.code} value={t.code}>{t.name}</option>)}</datalist></label>
          <label className="fld">Name<input value={f.name} onChange={e => setF({ ...f, name: e.target.value })} /></label>
          <label className="fld">Type<select value={f.docType} onChange={e => setF({ ...f, docType: e.target.value })}>{lib.types.map(t => <option key={t} value={t}>{TYPE_LABEL[t] ?? t}</option>)}</select></label>
          <label className="fld">Language<select value={f.language} onChange={e => setF({ ...f, language: e.target.value })}>{lib.languages.map(l => <option key={l} value={l}>{LANGS[l] ?? l}</option>)}</select></label>
        </div>
        <button className="btn primary" style={{ marginTop: 10 }} disabled={!file || !f.code} onClick={upload}>Upload</button>
      </> : <>
        <p className="small">Imported as {res.template.code} {res.template.language} v{res.template.version} (draft) with {res.fields.length} fields: {res.fields.join(', ') || 'none'}.</p>
        {res.problems.length > 0 && <div className="alert warn"><b>To fix in the editor:</b><ul style={{ margin: 0 }}>{res.problems.map(p => <li key={p}>{p}</li>)}</ul></div>}
        {res.messages.length > 0 && <p className="small muted">From Word: {res.messages.join('; ')}</p>}
        <button className="btn primary" onClick={() => onDone(res.template.id)}>Open in the editor</button>
      </>}
    </Modal>
  )
}
