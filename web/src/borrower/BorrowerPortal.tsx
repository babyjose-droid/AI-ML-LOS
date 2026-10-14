import { getTenant } from '../api'
import { useEffect, useState, type ReactNode } from 'react'
import { inr } from '../format'

// Borrower self-service portal at /apply: sign in with mobile OTP, apply, upload documents, follow progress,
// accept the Key Fact Statement. Uses its own token, separate from staff sign-in.

const KEY = 'rhythm_borrower_token'
const getToken = () => { try { return localStorage.getItem(KEY) } catch { return null } }
const setTok = (t: string | null) => { try { if (t) localStorage.setItem(KEY, t); else localStorage.removeItem(KEY) } catch { /* ignore */ } }

async function call<T>(method: string, url: string, body?: unknown, form?: FormData): Promise<T> {
  const t = getToken()
  const headers: Record<string, string> = {}
  if (t) headers.Authorization = 'Bearer ' + t
  const org = getTenant()
  if (org) headers['X-Tenant'] = org
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const r = await fetch(url, { method, headers, body: form ?? (body !== undefined ? JSON.stringify(body) : undefined) })
  const text = await r.text()
  let data: any
  try { data = text ? JSON.parse(text) : undefined } catch { data = undefined }
  if (r.status === 401) { setTok(null); throw Object.assign(new Error('Please sign in again'), { signOut: true }) }
  if (!r.ok) {
    const fields = data?.fields ? ' (' + Object.entries(data.fields).map(([k, v]) => `${k}: ${v}`).join('; ') + ')' : ''
    throw new Error((data?.message ?? (r.status === 413 ? 'The file is larger than 10 MB' : 'Something went wrong. Please try again.')) + fields)
  }
  return data as T
}

interface Product { code: string; name: string; minAmount: number; maxAmount: number; minTenure: number; maxTenure: number; documents: string[] }
interface Mine { id: number; appNo: string; product: string; amount: number; stage: string; createdAt: string }
interface View {
  id: number; appNo: string; product: string; productCode: string; amount: number; tenureMonths: number; applicantName: string
  stage: { index: number; label: string; steps: string[]; note: string }
  missingDocuments: string[]; requiredDocuments: string[]; canUpload: boolean; canAcceptKfs: boolean
  documents: { docType: string; fileName: string; status: string; message: string; uploadedAt: string }[]
  kfs?: Record<string, any>
}

const DOC_LABEL: Record<string, string> = {
  PAN: 'PAN card', AADHAAR: 'Aadhaar (masked: hide the first 8 digits)', VOTER_ID: 'Voter ID', DRIVING_LICENCE: 'Driving licence', PASSPORT: 'Passport',
  UDYAM: 'Udyam registration', SALARY_SLIP: 'Latest salary slip', BANK_STATEMENT: 'Bank statement (last 6 months)', GST_CERT: 'GST certificate', PHOTO: 'Photograph',
}

export default function BorrowerPortal() {
  const [signedIn, setSignedIn] = useState(!!getToken())
  const [page, setPage] = useState<{ name: 'home' } | { name: 'apply' } | { name: 'app'; id: number }>({ name: 'home' })
  const signOut = () => { setTok(null); setSignedIn(false); setPage({ name: 'home' }) }
  return (
    <div className="borrower">
      <header className="borrower-top">
        <div className="brand"><i /> <div>Rhythm demo NBFC<small>Apply for a loan</small></div></div>
        {signedIn && <button className="btn sm" onClick={signOut}>Sign out</button>}
      </header>
      <main className="borrower-main">
        {!signedIn ? <SignIn onDone={() => setSignedIn(true)} />
          : page.name === 'apply' ? <Apply onDone={id => setPage({ name: 'app', id })} onCancel={() => setPage({ name: 'home' })} onSignOut={signOut} />
          : page.name === 'app' ? <AppView id={page.id} onBack={() => setPage({ name: 'home' })} onSignOut={signOut} />
          : <Home onOpen={id => setPage({ name: 'app', id })} onApply={() => setPage({ name: 'apply' })} onSignOut={signOut} />}
      </main>
      <footer className="small muted borrower-foot">Grievance redressal: grievance@example-nbfc.in · Your data is used only for your loan, with your consent.</footer>
    </div>
  )
}

function Card({ children }: { children: ReactNode }) { return <div className="panel">{children}</div> }
function Err({ e }: { e: string }) { return e ? <div className="alert bad" role="alert">{e}</div> : null }

function SignIn({ onDone }: { onDone: () => void }) {
  const [mobile, setMobile] = useState('')
  const [otp, setOtp] = useState('')
  const [sent, setSent] = useState<{ demo: boolean } | null>(null)
  const [err, setErr] = useState('')
  async function send() {
    setErr('')
    try { setSent(await call<{ demo: boolean }>('POST', '/api/borrower/otp', { mobile })) } catch (e) { setErr((e as Error).message) }
  }
  async function verify() {
    setErr('')
    try {
      const r = await call<{ token: string }>('POST', '/api/borrower/otp/verify', { mobile, otp })
      setTok(r.token)
      onDone()
    } catch (e) { setErr((e as Error).message) }
  }
  return (
    <Card>
      <h2 style={{ marginTop: 0 }}>Sign in with your mobile number</h2>
      <p className="small muted">Use the mobile number you want on your loan account. We will send a one-time password.</p>
      <Err e={err} />
      <label className="fld">Mobile number<input name="mobile" inputMode="numeric" maxLength={10} value={mobile} onChange={e => setMobile(e.target.value.replace(/\D/g, ''))} /></label>
      {!sent ? <button className="btn primary" style={{ marginTop: 10 }} disabled={mobile.length !== 10} onClick={send}>Send OTP</button> : (
        <>
          <label className="fld" style={{ marginTop: 10 }}>OTP<input name="otp" inputMode="numeric" maxLength={6} value={otp} onChange={e => setOtp(e.target.value.replace(/\D/g, ''))} /></label>
          {sent.demo && <p className="small muted">Demo: the OTP is 123456 until SMS is connected.</p>}
          <div className="row" style={{ marginTop: 10 }}>
            <button className="btn primary" disabled={otp.length !== 6} onClick={verify}>Verify and continue</button>
            <button className="btn" onClick={send}>Resend</button>
          </div>
        </>
      )}
    </Card>
  )
}

function Home({ onOpen, onApply, onSignOut }: { onOpen: (id: number) => void; onApply: () => void; onSignOut: () => void }) {
  const [list, setList] = useState<Mine[] | null>(null)
  const [err, setErr] = useState('')
  useEffect(() => { call<Mine[]>('GET', '/api/borrower/applications').then(setList).catch(e => e.signOut ? onSignOut() : setErr(e.message)) }, [])
  return (
    <>
      <Card>
        <h2 style={{ marginTop: 0 }}>Your loans</h2>
        <Err e={err} />
        {list === null ? <p>Loading…</p> : list.length === 0 ? <p className="muted">You have no applications yet.</p> : (
          <div className="borrower-list">
            {list.map(a => (
              <button key={a.id} className="borrower-item" onClick={() => onOpen(a.id)} data-borrower-app={a.appNo}>
                <b>{a.appNo}</b> · {a.product} · {inr(a.amount)}<br /><span className="small">{a.stage}</span>
              </button>
            ))}
          </div>
        )}
        <button className="btn primary" style={{ marginTop: 12 }} onClick={onApply} data-borrower="apply">Apply for a new loan</button>
      </Card>
    </>
  )
}

function Apply({ onDone, onCancel, onSignOut }: { onDone: (id: number) => void; onCancel: () => void; onSignOut: () => void }) {
  const [products, setProducts] = useState<Product[]>([])
  const [f, setF] = useState<Record<string, string>>({ productCode: 'PL', tenureMonths: '24' })
  const [consent, setConsent] = useState({ consentKyc: false, consentAa: false, consentBureau: false })
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState(false)
  useEffect(() => { call<Product[]>('GET', '/api/borrower/products').then(setProducts).catch(e => setErr(e.message)) }, [])
  const p = products.find(x => x.code === f.productCode)
  const set = (k: string) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value })
  const input = (k: string, label: string, props: Record<string, unknown> = {}) => (
    <label className="fld">{label}<input name={k} value={f[k] ?? ''} onChange={set(k)} {...props} /></label>
  )
  async function submit() {
    setErr('')
    setBusy(true)
    try {
      const body = {
        ...f, ...consent, email: f.email || null, gender: f.gender || null, pan: (f.pan ?? '').toUpperCase(), bankIfsc: (f.bankIfsc ?? '').toUpperCase(), mobile: '9000000000',
        loanAmount: Number(f.loanAmount), tenureMonths: Number(f.tenureMonths), businessVintageYears: Number(f.businessVintageYears ?? 0),
        declaredMonthlyIncome: Number(f.declaredMonthlyIncome), essentialExpenses: Number(f.essentialExpenses ?? 0),
      }
      const v = await call<View>('POST', '/api/borrower/applications', body)
      onDone(v.id)
    } catch (e: any) { if (e.signOut) onSignOut(); else setErr(e.message) } finally { setBusy(false) }
  }
  return (
    <Card>
      <h2 style={{ marginTop: 0 }}>Apply for a loan</h2>
      <Err e={err} />
      <div className="form borrower-form">
        <label className="fld">Loan type<select name="productCode" value={f.productCode} onChange={set('productCode')}>{products.map(x => <option key={x.code} value={x.code}>{x.name}</option>)}</select></label>
        {input('loanAmount', p ? `Amount (₹${inr(p.minAmount).replace('₹', '')} to ${inr(p.maxAmount)})` : 'Amount (₹)', { inputMode: 'numeric' })}
        {input('tenureMonths', p ? `Months (${p.minTenure} to ${p.maxTenure})` : 'Months', { inputMode: 'numeric' })}
        {input('purpose', 'What is the loan for?')}
        {input('applicantName', 'Full name as on PAN')}
        {input('pan', 'PAN', { maxLength: 10, style: { textTransform: 'uppercase' } })}
        {input('dob', 'Date of birth', { type: 'date' })}
        {input('email', 'Email (optional)', { type: 'email' })}
        <label className="fld">Gender (optional)<select name="gender" value={f.gender ?? ''} onChange={set('gender')}><option value="">Prefer not to say</option><option value="FEMALE">Female</option><option value="MALE">Male</option><option value="OTHER">Other</option></select></label>
        {input('address', 'Address')}
        {input('city', 'City')}
        {input('pincode', 'PIN code', { inputMode: 'numeric', maxLength: 6 })}
        {input('businessName', 'Employer or business name')}
        {input('businessVintageYears', 'Years with this employer / in business', { inputMode: 'decimal' })}
        {input('declaredMonthlyIncome', 'Monthly income (₹)', { inputMode: 'numeric' })}
        {input('essentialExpenses', 'Monthly household expenses (₹)', { inputMode: 'numeric' })}
        {input('bankAccountNo', 'Bank account number (for the loan amount)', { inputMode: 'numeric' })}
        {input('bankIfsc', 'IFSC', { maxLength: 11, style: { textTransform: 'uppercase' } })}
      </div>
      <div className="borrower-consents">
        {([
          ['consentKyc', 'I agree that my identity documents are checked (KYC), including with the issuing authorities and the Central KYC registry.'],
          ['consentAa', 'I agree to share my bank statements through an RBI-licensed Account Aggregator, or by uploading them.'],
          ['consentBureau', 'I agree that my credit report is obtained from credit bureaus for this application.'],
        ] as const).map(([k, text]) => (
          <label key={k} className="small"><input type="checkbox" name={k} checked={consent[k]} onChange={e => setConsent({ ...consent, [k]: e.target.checked })} /> {text}</label>
        ))}
      </div>
      <div className="row" style={{ marginTop: 12 }}>
        <button className="btn primary" disabled={busy} onClick={submit} data-borrower="submit">{busy ? 'Submitting…' : 'Submit application'}</button>
        <button className="btn" onClick={onCancel}>Cancel</button>
      </div>
    </Card>
  )
}

function AppView({ id, onBack, onSignOut }: { id: number; onBack: () => void; onSignOut: () => void }) {
  const [v, setV] = useState<View | null>(null)
  const [err, setErr] = useState('')
  const [type, setType] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [password, setPassword] = useState('')
  const [otp, setOtp] = useState('')
  const [busy, setBusy] = useState(false)
  const load = () => call<View>('GET', `/api/borrower/applications/${id}`).then(x => { setV(x); setType(t => t || x.missingDocuments[0]?.split(' or ')[0] || x.requiredDocuments[0]?.split('|')[0] || 'PAN') })
    .catch(e => e.signOut ? onSignOut() : setErr(e.message))
  useEffect(() => { load() }, [id])

  async function upload() {
    if (!file) return
    setErr('')
    if (file.size > 10 * 1024 * 1024) { setErr('The file is larger than 10 MB'); return }
    const fd = new FormData()
    fd.append('type', type)
    fd.append('file', file)
    if (password) fd.append('password', password)
    setBusy(true)
    try { await call('POST', `/api/borrower/applications/${id}/documents`, undefined, fd); setFile(null); setPassword(''); await load() }
    catch (e: any) { if (e.signOut) onSignOut(); else setErr(e.message) } finally { setBusy(false) }
  }
  async function accept() {
    setErr('')
    try { setV(await call<View>('POST', `/api/borrower/applications/${id}/kfs/accept`, { otp })) } catch (e: any) { if (e.signOut) onSignOut(); else setErr(e.message) }
  }

  if (!v) return <Card><Err e={err} />Loading…</Card>
  const docTypes = [...new Set([...v.requiredDocuments.flatMap(g => g.split('|')), 'BANK_STATEMENT', 'PHOTO'])]
  const k = v.kfs
  return (
    <>
      <button className="btn sm" onClick={onBack} style={{ marginBottom: 10 }}>← Your loans</button>
      <Card>
        <h2 style={{ marginTop: 0 }}>{v.product} · {inr(v.amount)}</h2>
        <p className="small muted">{v.appNo} · {v.tenureMonths} months</p>
        <ol className="borrower-steps" data-borrower-stage={v.stage.label}>
          {v.stage.steps.map((s, i) => <li key={s} className={i < v.stage.index ? 'done' : i === v.stage.index ? 'now' : ''}>{s}</li>)}
        </ol>
        <div className={'alert ' + (v.stage.index < 0 ? 'bad' : 'ok')}><b>{v.stage.label}.</b> {v.stage.note}</div>
      </Card>
      <Err e={err} />
      {k && (
        <Card>
          <h3 style={{ marginTop: 0 }}>Key Fact Statement</h3>
          <dl className="dl" data-borrower-kfs>
            <dt>Loan amount</dt><dd>{inr(k.sanctionedAmount)}</dd>
            <dt>Interest rate</dt><dd>{k.ratePa}% a year (reducing balance)</dd>
            <dt>Annual percentage rate (all-in)</dt><dd><b>{k.aprPa}%</b></dd>
            <dt>EMI</dt><dd>{inr(k.emi)} × {k.tenureMonths} months</dd>
            <dt>Processing fee</dt><dd>{inr(k.processingFee)}{k.gstOnFee ? ` + GST ${inr(k.gstOnFee)}` : ''}</dd>
            <dt>Amount you receive</dt><dd>{inr(k.netDisbursed)}</dd>
            <dt>Total you repay</dt><dd>{inr(k.totalPayable)}</dd>
            <dt>Cooling-off</dt><dd>{k.coolingOffDays} days: you can exit without penalty, paying only interest for the days used</dd>
          </dl>
          {v.canAcceptKfs && (
            <div className="row" style={{ marginTop: 10 }}>
              <input name="kfsOtp" placeholder="OTP" inputMode="numeric" maxLength={6} value={otp} onChange={e => setOtp(e.target.value.replace(/\D/g, ''))} style={{ maxWidth: 140 }} />
              <button className="btn primary" disabled={otp.length !== 6} onClick={accept} data-borrower="acceptKfs">I accept these terms</button>
              <span className="small muted">Demo OTP: 123456</span>
            </div>
          )}
        </Card>
      )}
      <Card>
        <h3 style={{ marginTop: 0 }}>Documents</h3>
        {v.missingDocuments.length > 0 && <div className="alert warn small">Still needed: {v.missingDocuments.map(g => g.split(' or ').map(x => DOC_LABEL[x] ?? x).join(' or ')).join('; ')}</div>}
        {v.documents.map((d, i) => (
          <div key={i} className="borrower-doc"><b>{DOC_LABEL[d.docType] ?? d.docType}</b> <span className="small muted">{d.fileName}</span><br /><span className="small">{d.message}</span></div>
        ))}
        {v.canUpload && (
          <div className="form borrower-form" style={{ marginTop: 10 }}>
            <label className="fld">Document<select name="docType" value={type} onChange={e => setType(e.target.value)}>{docTypes.map(t => <option key={t} value={t}>{DOC_LABEL[t] ?? t}</option>)}</select></label>
            <label className="fld">File (photo or PDF, up to 10 MB)<input type="file" accept=".pdf,.jpg,.jpeg,.png" onChange={e => setFile(e.target.files?.[0] ?? null)} aria-label="File" /></label>
            {type === 'BANK_STATEMENT' && <label className="fld">PDF password, if any (used once, not stored)<input type="password" autoComplete="off" value={password} onChange={e => setPassword(e.target.value)} aria-label="PDF password" /></label>}
            <div><button className="btn primary" disabled={!file || busy} onClick={upload} data-borrower="upload">{busy ? 'Uploading…' : 'Upload'}</button></div>
          </div>
        )}
      </Card>
    </>
  )
}
