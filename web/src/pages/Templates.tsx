import { useState } from 'react'
import { api } from '../api'
import { useAuth } from '../auth'
import { ErrorBox, Head, Table, useLoad } from '../components/ui'
import { when } from '../format'
import { KFS_TEXT, LANGS } from '../i18n/kfs'

// The lender's wording: sanction letter and KFS text (per language) and customer messages. Admin and compliance edit;
// every save is a new version with a note, and the built-in wording comes back when a change is withdrawn.

interface Tpl { id: number; channel: string; code: string; language: string; subject?: string; body: string; version: number; note?: string; updatedBy: string; updatedAt: string }
interface ListView { templates: Tpl[]; messages: Record<string, string[]>; languages: string[]; channels: string[] }
interface Preview { text: string; length: number; smsParts: number; problems: string[] }

const MESSAGE_LABEL: Record<string, string> = {
  OTP_LOGIN: 'One-time password', PARTY_INVITE: 'Invite to a co-applicant or guarantor', VKYC_LINK: 'Video KYC link', SANCTION_SMS: 'Loan sanctioned',
  DISBURSED_SMS: 'Loan disbursed', EMI_REMINDER: 'EMI reminder', PTP_REMINDER: 'Promise-to-pay reminder',
}

export default function Templates() {
  const { user } = useAuth()
  const canEdit = !!user && ['ADMIN', 'COMPLIANCE'].includes(user.role)
  const [r, setR] = useState(0)
  const v = useLoad(() => api.get<ListView>('/api/templates'), [r])
  const [tab, setTab] = useState<'letter' | 'messages'>('letter')
  const [lang, setLang] = useState('en')
  const [edit, setEdit] = useState<{ channel: string; code: string; language: string; body: string; builtIn?: string } | null>(null)
  if (!v.data) return <ErrorBox error={v.error} />
  const d = v.data
  const find = (channel: string, code: string, language: string) => d.templates.find(t => t.channel === channel && t.code === code && t.language === language)
  const letterKeys = Object.keys(KFS_TEXT.en)
  return (
    <>
      <Head title="Templates" sub="Sanction letter and KFS wording in each language, and the messages customers receive. Every change is versioned and audited." />
      <div className="tabs" role="tablist">
        <button role="tab" aria-selected={tab === 'letter'} className={tab === 'letter' ? 'on' : ''} onClick={() => setTab('letter')}>Sanction letter & KFS</button>
        <button role="tab" aria-selected={tab === 'messages'} className={tab === 'messages' ? 'on' : ''} onClick={() => setTab('messages')}>Customer messages</button>
      </div>
      {tab === 'letter' && <div className="panel flush" data-testid="letter-templates">
        <div className="row" style={{ padding: '12px 14px 0' }}>
          <label className="small">Language <select value={lang} onChange={e => setLang(e.target.value)}>{Object.entries(LANGS).map(([c, n]) => <option key={c} value={c}>{n}</option>)}</select></label>
          <span className="small muted">Changed wording replaces the built-in text on every sanction letter and KFS shown in this language.</span>
        </div>
        <Table head={['Wording', 'Built-in text', 'Your text', '']} rows={letterKeys.map(k => {
          const own = find('LETTER', k, lang)
          const builtIn = KFS_TEXT[lang]?.[k] ?? KFS_TEXT.en[k]
          return [<span className="mono small">{k}</span>, <span className="small muted">{builtIn}</span>,
            own ? <span className="small"><b>{own.body}</b><div className="muted">v{own.version} · {own.updatedBy} · {when(own.updatedAt)}</div></span> : <span className="small muted">—</span>,
            canEdit ? <button className="btn sm" data-template={`LETTER-${k}`} onClick={() => setEdit({ channel: 'LETTER', code: k, language: lang, body: own?.body ?? builtIn, builtIn })}>Edit</button> : '']
        })} />
      </div>}
      {tab === 'messages' && <div className="panel flush" data-testid="message-templates">
        <div className="row" style={{ padding: '12px 14px 0' }}>
          <label className="small">Language <select value={lang} onChange={e => setLang(e.target.value)}>{Object.entries(LANGS).map(([c, n]) => <option key={c} value={c}>{n}</option>)}</select></label>
          <span className="small muted">Messages in a language without its own text go out in English. Sent once SMS and WhatsApp are connected; the text is used already where staff share links.</span>
        </div>
        <Table head={['Message', 'Text', 'Details it can use', '']} rows={Object.entries(d.messages).map(([code, vars]) => {
          const own = find('SMS', code, lang), en = find('SMS', code, 'en')
          return [<b>{MESSAGE_LABEL[code] ?? code}</b>,
            <span className="small">{own ? own.body : <span className="muted">{lang === 'en' ? '—' : `(English) ${en?.body ?? ''}`}</span>}{own && <div className="muted">v{own.version} · {own.updatedBy} · {when(own.updatedAt)}</div>}</span>,
            <span className="small mono">{vars.map(x => `{${x}}`).join(' ')}</span>,
            canEdit ? <button className="btn sm" data-template={`SMS-${code}`} onClick={() => setEdit({ channel: 'SMS', code, language: lang, body: own?.body ?? en?.body ?? '' })}>Edit</button> : '']
        })} />
      </div>}
      {edit && <Editor e={edit} vars={d.messages[edit.code]} hasOwn={!!find(edit.channel, edit.code, edit.language)} onClose={() => setEdit(null)} onDone={() => { setEdit(null); setR(x => x + 1) }} />}
    </>
  )
}

function Editor({ e, vars, hasOwn, onClose, onDone }: { e: { channel: string; code: string; language: string; body: string; builtIn?: string }; vars?: string[]; hasOwn: boolean; onClose: () => void; onDone: () => void }) {
  const [body, setBody] = useState(e.body)
  const [note, setNote] = useState('')
  const [p, setP] = useState<Preview>()
  const [error, setError] = useState<unknown>()
  const hist = useLoad(() => api.get<Tpl[]>(`/api/templates/history?channel=${e.channel}&code=${e.code}&language=${e.language}`), [e.code, e.language])
  const req = { channel: e.channel, code: e.code, language: e.language, body, note }
  async function run(fn: () => Promise<unknown>) { setError(undefined); try { await fn(); onDone() } catch (x) { setError(x) } }
  return (
    <div className="panel" style={{ marginTop: 12 }} data-testid="template-editor">
      <div className="row" style={{ justifyContent: 'space-between' }}>
        <h3 style={{ margin: 0 }}>{e.channel === 'LETTER' ? 'Letter wording' : MESSAGE_LABEL[e.code] ?? e.code} · {LANGS[e.language as keyof typeof LANGS] ?? e.language}</h3>
        <button className="btn sm" onClick={onClose}>Close</button>
      </div>
      {e.builtIn && <p className="small muted">Built-in: {e.builtIn}</p>}
      {vars && <p className="small">Details you can use: {vars.map(x => <code key={x} style={{ marginRight: 6 }}>{`{${x}}`}</code>)}</p>}
      <textarea name="templateBody" rows={e.channel === 'LETTER' ? 3 : 4} value={body} onChange={x => setBody(x.target.value)} style={{ width: '100%' }} />
      <label className="fld">What changed and why<input name="templateNote" value={note} onChange={x => setNote(x.target.value)} placeholder="e.g. Simpler wording approved by compliance" /></label>
      <div className="row" style={{ marginTop: 6 }}>
        <button className="btn" onClick={() => api.post<Preview>('/api/templates/preview', req).then(setP).catch(setError)}>Preview</button>
        <button className="btn primary" data-action="saveTemplate" disabled={note.trim().length < 5} onClick={() => run(() => api.post('/api/templates', req))}>Save</button>
        {hasOwn && <button className="btn" onClick={() => run(() => api.post('/api/templates/revert', req))}>{e.channel === 'LETTER' ? 'Withdraw (back to built-in or the earlier version)' : 'Back to the earlier version'}</button>}
      </div>
      <ErrorBox error={error} />
      {p && <div className="alert" style={{ marginTop: 8 }} data-testid="template-preview">{p.text}<div className="small muted">{p.length} characters{e.channel === 'SMS' ? ` · ${p.smsParts} SMS` : ''}</div>
        {p.problems.length > 0 && <ul className="small warn-text">{p.problems.map(x => <li key={x}>{x}</li>)}</ul>}</div>}
      {(hist.data ?? []).length > 0 && <details className="small" style={{ marginTop: 8 }}><summary>Versions</summary>
        <ul>{(hist.data ?? []).map(h => <li key={h.id}>v{h.version} · {h.updatedBy} · {when(h.updatedAt)} · {h.note}: {h.body}</li>)}</ul></details>}
    </div>
  )
}
