import { Fragment, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api } from '../api'
import { ErrorBox, Head, useLoad } from '../components/ui'
import { inr } from '../format'
import type { AppView, LoanApplication, Product } from '../types'
import { PartiesOnForm, partyBody, type PartyDraft } from '../parties/Parties'

type Form = Record<string, string | boolean>

/** Capture in five short steps, as in the prototype (SCR-010); every step can be saved as a draft. */
const STEPS: { name: string; fields: string[] }[] = [
  { name: 'Product & loan', fields: ['productCode', 'loanAmount', 'tenureMonths', 'purpose'] },
  { name: 'Applicant', fields: ['applicantName', 'pan', 'mobile', 'email', 'dob', 'gender', 'city', 'pincode', 'address'] },
  { name: 'Income & people', fields: ['businessName', 'industry', 'businessVintageYears', 'declaredMonthlyIncome', 'essentialExpenses'] },
  { name: 'Account & consent', fields: ['bankAccountNo', 'bankIfsc', 'consentKyc', 'consentAa', 'consentBureau', 'preferredLanguage'] },
  { name: 'Review & submit', fields: [] },
]

const EMPTY: Form = {
  productCode: 'BL-UNS', applicantName: '', pan: '', mobile: '', email: '', dob: '', gender: '', address: '', city: '', pincode: '',
  businessName: '', businessVintageYears: '3', declaredMonthlyIncome: '', essentialExpenses: '', loanAmount: '', tenureMonths: '24',
  purpose: '', bankAccountNo: '', bankIfsc: '', consentBureau: false, consentAa: false, consentKyc: false, preferredLanguage: 'en', industry: '', addressLat: '', addressLng: '',
}

function fromApp(a: LoanApplication): Form {
  const f: Form = { ...EMPTY }
  for (const k of Object.keys(EMPTY)) {
    const v = (a as unknown as Record<string, unknown>)[k]
    if (v !== undefined && v !== null) f[k] = typeof v === 'boolean' ? v : String(v)
  }
  return f
}

export default function NewApplication() {
  const { id } = useParams()
  const nav = useNavigate()
  const products = useLoad(() => api.get<Product[]>('/api/products'), [])
  const [f, setF] = useState<Form>(EMPTY)
  const [error, setError] = useState<unknown>()
  const [busy, setBusy] = useState(false)
  const [people, setPeople] = useState<PartyDraft[]>([])
  // once saved, later saves update the same application (for example after fixing a co-applicant's details)
  const [savedId, setSavedId] = useState<number>()
  const [step, setStep] = useState(0)

  useEffect(() => {
    // the draft endpoint returns PAN and account number in full for editing; everywhere else they are masked
    if (id) api.get<AppView['application']>('/api/applications/' + id + '/draft').then(a => setF(fromApp(a))).catch(setError)
  }, [id])

  const p = (products.data ?? []).find(x => x.code === f.productCode)
  const set = (k: string) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) =>
    setF({ ...f, [k]: e.target.type === 'checkbox' ? (e.target as HTMLInputElement).checked : e.target.value })
  const txt = (k: string, lbl: string, props: React.InputHTMLAttributes<HTMLInputElement> = {}) => (
    <label className="fld">{lbl}<input name={k} value={String(f[k] ?? '')} onChange={set(k)} {...props} /></label>
  )

  async function save(submit: boolean) {
    setBusy(true)
    setError(undefined)
    const body = {
      ...f,
      pan: String(f.pan).toUpperCase(), bankIfsc: String(f.bankIfsc).toUpperCase(),
      businessVintageYears: Number(f.businessVintageYears || 0), declaredMonthlyIncome: Number(f.declaredMonthlyIncome),
      essentialExpenses: Number(f.essentialExpenses || 0), loanAmount: Number(f.loanAmount), tenureMonths: Number(f.tenureMonths),
      email: f.email || null, gender: f.gender || null, industry: f.industry || null,
      addressLat: f.addressLat ? Number(f.addressLat) : null, addressLng: f.addressLng ? Number(f.addressLng) : null,
    }
    try {
      const target = id ?? savedId
      const a = target ? await api.put<LoanApplication>('/api/applications/' + target, body) : await api.post<LoanApplication>('/api/applications', body)
      setSavedId(a.id)
      const left = [...people]
      while (left.length) {
        await api.post(`/api/applications/${a.id}/parties`, partyBody(left[0]))
        left.shift()
        setPeople([...left])
      }
      if (submit) await api.post('/api/applications/' + a.id + '/submit')
      nav('/applications/' + a.id)
    } catch (e) {
      setError(e)
      const bad = Object.keys((e as { fields?: Record<string, string> })?.fields ?? {})
      const at = STEPS.findIndex(st => st.fields.some(k => bad.includes(k)))
      if (at >= 0) setStep(at)
      window.scrollTo(0, 0)
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <Head title={id ? 'Edit application' : 'New application'} sub="Minimum data for a first decision. Bank, bureau and GST data is fetched after the borrower's consent, so it is not typed here." />
      <ol className="phases steps5" aria-label="Steps">
        {STEPS.map((st, i) => (
          <li key={st.name}><button type="button" className={(i < step ? 'done' : i === step ? 'now' : 'todo') + (i === step ? ' sel' : '')} data-step={i + 1} onClick={() => setStep(i)}
            aria-current={i === step ? 'step' : undefined}><span className="sn">{i < step ? '✓' : i + 1}</span>{st.name}</button></li>
        ))}
      </ol>
      <ErrorBox error={error} />
      <div className="wizard">
      {step === 0 && <div className="panel">
        <h3>Product and loan</h3>
        <div className="form">
          <label className="fld">Product
            <select name="productCode" value={String(f.productCode)} onChange={set('productCode')}>
              {(products.data ?? []).filter(x => x.status === 'LIVE').map(x => <option key={x.code} value={x.code}>{x.name}</option>)}
            </select>
          </label>
          {txt('loanAmount', 'Loan amount (₹)', { type: 'number', min: 0 })}
          {txt('tenureMonths', 'Tenure (months)', { type: 'number', min: 1 })}
          {txt('purpose', 'Purpose')}
        </div>
        {p && <p className="small muted" style={{ marginBottom: 0 }}>{p.name}: {inr(p.minAmount)}–{inr(p.maxAmount)}, {p.minTenure}–{p.maxTenure} months, documents {p.requiredDocs.replace(/,/g, ', ').replace(/\|/g, ' or ')}.</p>}
      </div>}
      {step === 1 && <div className="panel">
        <h3>Applicant</h3>
        <div className="form">
          {txt('applicantName', 'Full name (as on PAN)')}
          {txt('pan', 'PAN', { placeholder: 'ABCPK1234L', maxLength: 10 })}
          {txt('mobile', 'Mobile', { inputMode: 'numeric', maxLength: 10 })}
          {txt('email', 'Email', { type: 'email' })}
          {txt('dob', 'Date of birth', { type: 'date' })}
          <label className="fld">Gender
            <select name="gender" value={String(f.gender)} onChange={set('gender')}>
              <option value="">Prefer not to say</option><option value="FEMALE">Female</option><option value="MALE">Male</option><option value="OTHER">Other</option>
            </select>
          </label>
          {txt('city', 'City')}
          {txt('pincode', 'PIN code', { inputMode: 'numeric', maxLength: 6 })}
          <label className="fld wide">Address<input name="address" value={String(f.address)} onChange={set('address')} /></label>
          <div className="fld wide small">
            {f.addressLat ? <>Business location saved ({Number(f.addressLat).toFixed(5)}, {Number(f.addressLng).toFixed(5)}): the field visit checks in within 150 m of it. </> : 'At the borrower\'s premises? Save the location so the field visit can be checked against it. '}
            <button type="button" className="btn sm" onClick={() => navigator.geolocation?.getCurrentPosition(
              pos => setF({ ...f, addressLat: String(pos.coords.latitude), addressLng: String(pos.coords.longitude) }),
              () => setError(new Error('Location is not available: allow location access in the browser')), { enableHighAccuracy: true, timeout: 15000 })}>Use my current location</button>
          </div>
        </div>
      </div>}
      {step === 2 && <><div className="panel">
        <h3>Income and business</h3>
        <div className="form">
          {txt('businessName', 'Business or employer')}
          <label className="fld">Industry (picks the scorecard)
            <select name="industry" value={String(f.industry)} onChange={set('industry')}>
              <option value="">—</option>
              {[['KIRANA', 'Kirana / retail store'], ['TRADING', 'Trading'], ['DAIRY', 'Dairy'], ['AGRI', 'Agriculture'], ['TRANSPORT', 'Transport'],
                ['MANUFACTURING', 'Manufacturing'], ['SERVICES', 'Services'], ['SALARIED', 'Salaried'], ['OTHER', 'Other']].map(([v, l]) => <option key={v} value={v}>{l}</option>)}
            </select>
          </label>
          {txt('businessVintageYears', 'Years in business / employment', { type: 'number', min: 0, step: 0.5 })}
          {txt('declaredMonthlyIncome', 'Declared monthly income (₹)', { type: 'number', min: 0 })}
          {txt('essentialExpenses', 'Essential household expenses (₹/month)', { type: 'number', min: 0 })}
        </div>
      </div>
      {f.productCode && <PartiesOnForm productCode={String(f.productCode)} list={people} onChange={setPeople} />}
      </>}
      {step === 3 && <><div className="panel">
        <h3>Disbursement account</h3>
        <div className="form">
          {txt('bankAccountNo', 'Bank account number', { inputMode: 'numeric' })}
          {txt('bankIfsc', 'IFSC', { maxLength: 11 })}
        </div>
      </div>
      <div className="panel">
        <h3>Borrower consent</h3>
        <p className="small muted" style={{ marginTop: -4 }}>We need the borrower's permission for each data source. They can withdraw it at any time.</p>
        <label className="check"><input type="checkbox" name="consentKyc" checked={!!f.consentKyc} onChange={set('consentKyc')} /> KYC verification (PAN, Aadhaar offline e-KYC or DigiLocker, CKYC)</label>
        <label className="check"><input type="checkbox" name="consentAa" checked={!!f.consentAa} onChange={set('consentAa')} /> Bank statements through the Account Aggregator for the last 12 months</label>
        <label className="check"><input type="checkbox" name="consentBureau" checked={!!f.consentBureau} onChange={set('consentBureau')} /> Credit bureau report</label>
        <label className="fld" style={{ maxWidth: 280, marginTop: 8 }}>Borrower's preferred language (consent text, KFS and messages)
          <select name="preferredLanguage" value={String(f.preferredLanguage)} onChange={set('preferredLanguage')}>
            {[['en', 'English'], ['hi', 'Hindi'], ['mr', 'Marathi'], ['ta', 'Tamil'], ['ml', 'Malayalam'], ['kn', 'Kannada'], ['te', 'Telugu'], ['bn', 'Bengali'], ['gu', 'Gujarati']].map(([v, l]) => <option key={v} value={v}>{l}</option>)}
          </select>
        </label>
      </div>
      </>}
      {step === 4 && <Review f={f} productName={p?.name} people={people.length} onEdit={setStep} />}
      <div className="row between">
        <div className="row" style={{ marginTop: 0 }}>
          {step > 0 && <button className="btn" disabled={busy} onClick={() => setStep(step - 1)}>Back</button>}
          <button className="btn" disabled={busy} onClick={() => save(false)}>Save draft</button>
        </div>
        {step < STEPS.length - 1
          ? <button className="btn primary" onClick={() => { setStep(step + 1); window.scrollTo(0, 0) }}>Continue</button>
          : <button className="btn primary" disabled={busy} onClick={() => save(true)}>Save and submit</button>}
      </div>
      </div>
    </>
  )
}

function Review({ f, productName, people, onEdit }: { f: Form; productName?: string; people: number; onEdit: (i: number) => void }) {
  const rows: [string, string, number][] = [
    ['Product', productName ?? String(f.productCode), 0], ['Amount', f.loanAmount ? inr(Number(f.loanAmount)) : '—', 0], ['Tenure', `${f.tenureMonths || '—'} months`, 0], ['Purpose', String(f.purpose || '—'), 0],
    ['Applicant', String(f.applicantName || '—'), 1], ['PAN', String(f.pan || '—').toUpperCase(), 1], ['Mobile', String(f.mobile || '—'), 1], ['City / PIN', [f.city, f.pincode].filter(Boolean).join(' · ') || '—', 1],
    ['Business or employer', String(f.businessName || '—'), 2], ['Monthly income', f.declaredMonthlyIncome ? inr(Number(f.declaredMonthlyIncome)) : '—', 2], ['Co-applicants and guarantors', people ? String(people) : 'None', 2],
    ['Disbursement account', f.bankAccountNo ? `${String(f.bankAccountNo).replace(/.(?=.{4})/g, 'X')} · ${String(f.bankIfsc).toUpperCase()}` : '—', 3],
    ['Consents', [f.consentKyc && 'KYC', f.consentAa && 'Account Aggregator', f.consentBureau && 'Bureau'].filter(Boolean).join(', ') || 'None yet', 3],
  ]
  return (
    <div className="panel" data-testid="application-review">
      <h3>Check and submit</h3>
      <p className="small muted" style={{ marginTop: -4 }}>Submitting runs the automated checks: KYC, bank and bureau data, documents, fraud and the decision engine.</p>
      <dl className="dl">{rows.map(([k, v, i]) => <Fragment key={k}><dt>{k}</dt><dd>{v} <button type="button" className="linkbtn small" onClick={() => onEdit(i)}>Change</button></dd></Fragment>)}</dl>
    </div>
  )
}
