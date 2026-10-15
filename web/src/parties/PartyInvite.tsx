import { useEffect, useState } from 'react'
import { getTenant } from '../api'
import { inr, label, when } from '../format'

// The page a co-applicant or guarantor opens from the invite link (/party/<token>): OTP on their own phone, what they
// are agreeing to, their own consent and documents, and later their signature. No staff sign-in.

interface View {
  name: string; role: string; relationship: string; mobile: string; applicant: string; appNo: string; product: string; amount: number; tenureMonths: number
  liability: string; needsAa?: boolean; consented: boolean; kycState: string; signed: boolean; canSign: boolean; terms?: string
  documents?: { docType: string; fileName: string; status: string; uploadedAt: string }[]; documentTypes?: string[]
}

const token = window.location.pathname.split('/')[2] ?? ''
const SKEY = 'rhythm_party_session_' + token.slice(0, 12)
const getSession = () => { try { return sessionStorage.getItem(SKEY) } catch { return null } }
const setSession = (s: string | null) => { try { if (s) sessionStorage.setItem(SKEY, s); else sessionStorage.removeItem(SKEY) } catch { /* storage unavailable */ } }

async function call<T>(method: string, path: string, body?: unknown, form?: FormData): Promise<T> {
  const headers: Record<string, string> = {}
  const org = getTenant()
  if (org) headers['X-Tenant'] = org
  const s = getSession()
  if (s) headers['X-Party-Session'] = s
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const r = await fetch(`/api/party/c/${encodeURIComponent(token)}${path}`, { method, headers, body: form ?? (body !== undefined ? JSON.stringify(body) : undefined) })
  const text = await r.text()
  let data: any
  try { data = text ? JSON.parse(text) : undefined } catch { data = undefined }
  if (r.status === 401) { setSession(null); throw new Error('Please verify with an OTP again') }
  if (!r.ok) throw new Error(data?.message ?? (r.status === 413 ? 'The file is larger than 10 MB' : 'Something went wrong. Please try again.'))
  return data as T
}

const DOC: Record<string, string> = { PAN: 'PAN card', AADHAAR: 'Aadhaar (masked: hide the first 8 digits)', VOTER_ID: 'Voter ID', DRIVING_LICENCE: 'Driving licence', PASSPORT: 'Passport', INCOME_PROOF: 'Income proof (salary slip or ITR)', BANK_STATEMENT: 'Bank statement (last 12 months, PDF)', BOARD_RESOLUTION: 'Board resolution', PARTNERSHIP_DEED: 'Partnership deed', LLP_AGREEMENT: 'LLP agreement', TRUST_DEED: 'Trust deed', INCORPORATION_CERTIFICATE: 'Certificate of incorporation' }

export default function PartyInvite() {
  const [v, setV] = useState<View>()
  const [error, setError] = useState<string>()
  const [signedIn, setSignedIn] = useState(!!getSession())
  const [otpSent, setOtpSent] = useState(false)
  const [otp, setOtp] = useState('')
  const [kyc, setKyc] = useState(false)
  const [bureau, setBureau] = useState(false)
  const [aa, setAa] = useState(false)
  const [lang, setLang] = useState('en')
  const [type, setType] = useState('PAN')
  const [file, setFile] = useState<File>()
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState<string>()

  const load = () => call<View>('GET', '').then(x => { setV(x); setSignedIn(!!getSession() && x.documents !== undefined) }).catch(e => setError(e.message))
  useEffect(() => { load() }, [])

  async function run(fn: () => Promise<unknown>, done?: string) {
    setBusy(true); setError(undefined); setMsg(undefined)
    try { await fn(); if (done) setMsg(done); await load() } catch (e) { setError((e as Error).message) } finally { setBusy(false) }
  }

  const role = v?.role === 'GUARANTOR' ? 'guarantor' : 'co-applicant'
  return (
    <div className="borrower">
      <header className="borrower-top"><div className="brand"><i /> <div>Rhythm demo NBFC<small>{v ? `You are named as ${role}` : 'Loan invitation'}</small></div></div></header>
      <main className="borrower-main">
        {error && <div className="alert bad" role="alert">{error}</div>}
        {msg && <div className="alert ok" role="status">{msg}</div>}
        {!v ? (!error && <p>Loading…</p>) : <>
          <div className="panel">
            <h2 style={{ marginTop: 0 }}>Hello {v.name.split(' ')[0]}</h2>
            <p>{v.applicant} has named you as <b>{role}</b> ({label(v.relationship).toLowerCase()}) for a {v.product.toLowerCase()} of <b>{inr(v.amount)}</b> over {v.tenureMonths} months (application {v.appNo}).</p>
            <p className="small">{v.liability}</p>
            <p className="small muted">If you do not know about this loan, do not continue and call the lender.</p>
          </div>

          {!signedIn ? <div className="panel">
            <h3>Verify it is you</h3>
            {!otpSent ? <button className="btn primary" disabled={busy} onClick={() => run(async () => { await call('POST', '/otp'); setOtpSent(true) })}>Send OTP to {v.mobile}</button> : <>
              <label className="fld">OTP sent to {v.mobile} (demo: 123456)<input name="otp" inputMode="numeric" maxLength={6} value={otp} onChange={e => setOtp(e.target.value)} /></label>
              <button className="btn primary" style={{ marginTop: 8 }} disabled={busy || otp.length < 4} onClick={() => run(async () => {
                const r = await call<{ session: string }>('POST', '/verify', { otp }); setSession(r.session); setSignedIn(true)
              })}>Verify</button>
            </>}
          </div> : <>
            {!v.consented ? <div className="panel">
              <h3>Your consent</h3>
              <div className="borrower-consents">
                <label><input type="checkbox" checked={kyc} onChange={e => setKyc(e.target.checked)} /> Verify my identity using my PAN and the identity documents I upload</label>
                <label><input type="checkbox" checked={bureau} onChange={e => setBureau(e.target.checked)} /> Check my credit history with a credit bureau (one report)</label>
                {v.needsAa && <label><input type="checkbox" checked={aa} onChange={e => setAa(e.target.checked)} /> Check my income from 12 months of my bank account (Account Aggregator, or the bank statement I upload below)</label>}
              </div>
              <label className="fld" style={{ maxWidth: 240, marginTop: 8 }}>My language<select value={lang} onChange={e => setLang(e.target.value)}>
                {[['en', 'English'], ['hi', 'हिन्दी'], ['mr', 'मराठी'], ['ta', 'தமிழ்'], ['ml', 'മലയാളം'], ['kn', 'ಕನ್ನಡ'], ['te', 'తెలుగు'], ['bn', 'বাংলা'], ['gu', 'ગુજરાતી']].map(([c, l]) => <option key={c} value={c}>{l}</option>)}</select></label>
              <button className="btn primary" style={{ marginTop: 10 }} disabled={busy || !kyc || !bureau || (!!v.needsAa && !aa)} onClick={() => run(() => call('POST', '/consent', { consentKyc: kyc, consentBureau: bureau, consentAa: aa, language: lang }), 'Thank you: your consent is recorded.')}>I agree</button>
            </div> : <div className="panel"><p style={{ margin: 0 }}>Consent given. Identity check: <b>{label(v.kycState)}</b>.</p></div>}

            <div className="panel">
              <h3>Your documents</h3>
              {(v.documents ?? []).length === 0 ? <p className="small muted">Nothing uploaded yet. Upload your PAN and your Aadhaar (masked) or voter ID, driving licence or passport.</p>
                : <ul className="small">{(v.documents ?? []).map((d, i) => <li key={i}>{DOC[d.docType] ?? label(d.docType)}: {label(d.status)} · {when(d.uploadedAt)}</li>)}</ul>}
              <div className="grid2">
                <label className="fld">Document<select value={type} onChange={e => setType(e.target.value)}>{(v.documentTypes ?? []).map(t => <option key={t} value={t}>{DOC[t] ?? label(t)}</option>)}</select></label>
                <label className="fld">Photo or PDF<input type="file" accept=".pdf,.jpg,.jpeg,.png" capture="environment" onChange={e => setFile(e.target.files?.[0])} /></label>
              </div>
              <button className="btn" style={{ marginTop: 8 }} disabled={busy || !file} onClick={() => run(async () => {
                const f = new FormData(); f.append('file', file!); f.append('docType', type); await call('POST', '/documents', undefined, f); setFile(undefined)
              }, 'Uploaded.')}>{busy ? 'Uploading…' : 'Upload'}</button>
            </div>

            {v.terms && <div className="panel">
              <h3>{v.role === 'GUARANTOR' ? 'Guarantee' : 'Loan agreement'}</h3>
              <p className="small">{v.terms}</p>
              {v.signed ? <p><b>Signed.</b> Thank you.</p>
                : v.canSign && <button className="btn primary" disabled={busy} onClick={() => run(() => call('POST', '/sign'), 'Signed. Thank you.')}>I agree and sign</button>}
            </div>}
          </>}
        </>}
      </main>
    </div>
  )
}
