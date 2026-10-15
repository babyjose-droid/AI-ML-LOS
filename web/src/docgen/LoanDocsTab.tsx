import { useState } from 'react'
import { api } from '../api'
import { useAuth } from '../auth'
import { ErrorBox, Modal, useLoad } from '../components/ui'
import { when } from '../format'
import { STAGE_LABEL, frameDoc, openPdf } from './common'

// The application's loan documents: made from the product's templates at each stage, viewed, downloaded and signed.

interface Signer { role: string; partyId?: number; name: string; signedAt?: string; channel?: string }
interface Doc { id: number; templateCode: string; templateVersion: number; language: string; name: string; sha256: string; bytes: number; generatedBy: string; generatedAt: string }
interface Row { templateCode: string; name: string; stage: string; required: boolean; notYet?: string | null; signers: Signer[]; document?: Doc; outdated?: boolean; missing?: string[]; complete: boolean }
interface Pack { documents: Row[]; gate: boolean; blockers: string[]; stages: string[]; renderer: string; superseded: number }

export function LoanDocsTab({ id, onChange }: { id: string; onChange: () => void }) {
  const { user } = useAuth()
  const [r, setR] = useState(0)
  const p = useLoad(() => api.get<Pack>(`/api/applications/${id}/documents-pack`), [id, r])
  const [view, setView] = useState<{ name: string; html: string } | null>(null)
  const [sign, setSign] = useState<{ doc: Doc; s: Signer } | null>(null)
  const [error, setError] = useState<unknown>()
  const [busy, setBusy] = useState(false)
  const role = user?.role ?? ''
  const canMake = ['ADMIN', 'SALES', 'OPERATIONS', 'CREDIT_OFFICER', 'CREDIT_MANAGER', 'CRO'].includes(role)
  const canSign = ['ADMIN', 'SALES', 'OPERATIONS', 'CREDIT_OFFICER'].includes(role)
  async function run(fn: () => Promise<unknown>) {
    setError(undefined); setBusy(true)
    try { await fn(); setR(x => x + 1); onChange() } catch (e) { setError(e) } finally { setBusy(false) }
  }
  if (!p.data) return <ErrorBox error={p.error} />
  const d = p.data
  const stages = d.stages.filter(s => d.documents.some(x => x.stage === s))
  return (
    <div data-testid="loan-docs">
      <ErrorBox error={error} />
      {d.renderer === 'BASIC-PDF' && <div className="alert warn small">PDFs are plain text until the AI document service is connected.</div>}
      {d.gate && <div className={'alert ' + (d.blockers.length ? 'warn' : 'ok')} data-testid="loan-docs-gate">
        {d.blockers.length ? <><b>Disbursement waits for:</b><ul style={{ margin: 0 }}>{d.blockers.map(b => <li key={b}>{b}</li>)}</ul></> : 'Every required document is made and signed.'}</div>}
      {stages.length === 0 && <p className="small muted">This product has no loan documents set up (Products → Loan documents).</p>}
      {stages.map(s => {
        const rows = d.documents.filter(x => x.stage === s)
        const blocked = rows.find(x => x.notYet)?.notYet
        return <div className="panel" key={s} data-doc-stage={s}>
          <div className="row" style={{ justifyContent: 'space-between' }}>
            <h3 style={{ margin: 0 }}>{STAGE_LABEL[s] ?? s}</h3>
            {canMake && <button className="btn sm primary" data-action={`make-${s}`} disabled={busy || !!blocked} title={blocked ? `Can be made ${blocked}` : undefined}
              onClick={() => run(() => api.post(`/api/applications/${id}/documents-pack/generate`, { stage: s }))}>{rows.some(x => x.document) ? 'Make again' : 'Make documents'}</button>}
          </div>
          {blocked && <p className="small muted">Can be made {blocked}.</p>}
          <table><tbody>{rows.map(x => <tr key={x.templateCode} data-doc={x.templateCode}>
            <td style={{ width: '32%' }}><b>{x.name}</b>{!x.required && <span className="small muted"> (optional)</span>}
              {x.document && <div className="small muted">v{x.document.templateVersion} · {x.document.language} · {when(x.document.generatedAt)} by {x.document.generatedBy}</div>}
              {x.document && x.outdated && <div className="small warn-text">Out of date: the application changed. Make it again before signing.</div>}
              {x.missing && x.missing.length > 0 && <div className="small muted">Left blank: {x.missing.join(', ')}</div>}</td>
            <td>{x.signers.length === 0 ? <span className="small muted">No signature</span> : x.signers.map(sg => <div key={sg.role + (sg.partyId ?? '')} className="small">
              {sg.signedAt ? <span className="pill ok">signed</span> : <span className="pill muted">to sign</span>} {sg.name}
              {sg.signedAt ? <span className="muted"> · {sg.channel === 'WET_INK' ? 'wet ink' : 'OTP'} {when(sg.signedAt)}</span>
                : canSign && x.document && !x.outdated && <button className="btn sm" style={{ marginLeft: 6 }} data-action={`sign-${x.templateCode}-${sg.role}`} onClick={() => setSign({ doc: x.document!, s: sg })}>Sign</button>}
            </div>)}</td>
            <td style={{ whiteSpace: 'nowrap' }}>{x.document ? <span className="row" style={{ gap: 4 }}>
              <button className="btn sm" onClick={() => run(async () => setView(await api.get(`/api/app-documents/${x.document!.id}/html`)))}>View</button>
              <button className="btn sm" onClick={() => run(() => openPdf(`/api/app-documents/${x.document!.id}/pdf`))}>PDF</button>
              {x.complete && <span className="pill ok">complete</span>}
            </span> : <span className="small muted">not made</span>}</td>
          </tr>)}</tbody></table>
        </div>
      })}
      {d.superseded > 0 && <p className="small muted">{d.superseded} earlier version(s) are kept with their signatures in the audit trail.</p>}
      {view && <Modal title={view.name} onClose={() => setView(null)}>
        <iframe title={view.name} sandbox="" srcDoc={frameDoc(view.html)} style={{ width: '100%', height: '70vh', border: 0, background: '#fff' }} />
      </Modal>}
      {sign && <SignDialog doc={sign.doc} s={sign.s} wetInk={['OPERATIONS', 'ADMIN'].includes(role)} onClose={() => setSign(null)} onDone={() => { setSign(null); setR(x => x + 1); onChange() }} />}
    </div>
  )
}

function SignDialog({ doc, s, wetInk, onClose, onDone }: { doc: Doc; s: Signer; wetInk: boolean; onClose: () => void; onDone: () => void }) {
  const [otp, setOtp] = useState('')
  const [channel, setChannel] = useState<'OTP' | 'WET_INK'>('OTP')
  const [error, setError] = useState<unknown>()
  return (
    <Modal title={`${s.name} signs: ${doc.name}`} onClose={onClose}>
      <ErrorBox error={error} />
      <p className="small">Show {s.name} the document (View or PDF) first. They confirm with the OTP sent to their own mobile (demo OTP 123456 until SMS and Aadhaar e-sign are connected).
        The signature is tied to this exact file (fingerprint {doc.sha256.slice(0, 12)}…).</p>
      {wetInk && <label className="check small"><input type="checkbox" checked={channel === 'WET_INK'} onChange={e => setChannel(e.target.checked ? 'WET_INK' : 'OTP')} /> Signed on paper: I have checked the signed original</label>}
      {channel === 'OTP' && <label className="fld" style={{ maxWidth: 200 }}>OTP<input name="docOtp" inputMode="numeric" maxLength={6} value={otp} onChange={e => setOtp(e.target.value.replace(/\D/g, ''))} /></label>}
      <button className="btn primary" style={{ marginTop: 10 }} data-action="confirmSign" disabled={channel === 'OTP' && otp.length < 4}
        onClick={async () => { try { await api.post(`/api/app-documents/${doc.id}/sign`, { role: s.role, partyId: s.partyId ?? null, otp, channel }); onDone() } catch (e) { setError(e) } }}>Record signature</button>
    </Modal>
  )
}
