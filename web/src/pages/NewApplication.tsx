import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api } from '../api'
import { ErrorBox, Head, useLoad } from '../components/ui'
import { inr } from '../format'
import type { AppView, LoanApplication, Product } from '../types'

type Form = Record<string, string | boolean>

const EMPTY: Form = {
  productCode: 'BL-UNS', applicantName: '', pan: '', mobile: '', email: '', dob: '', gender: '', address: '', city: '', pincode: '',
  businessName: '', businessVintageYears: '3', declaredMonthlyIncome: '', essentialExpenses: '', loanAmount: '', tenureMonths: '24',
  purpose: '', bankAccountNo: '', bankIfsc: '', consentBureau: false, consentAa: false, consentKyc: false,
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

  useEffect(() => {
    if (id) api.get<AppView>('/api/applications/' + id).then(v => setF(fromApp(v.application))).catch(setError)
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
      email: f.email || null, gender: f.gender || null,
    }
    try {
      const a = id ? await api.put<LoanApplication>('/api/applications/' + id, body) : await api.post<LoanApplication>('/api/applications', body)
      if (submit) await api.post('/api/applications/' + a.id + '/submit')
      nav('/applications/' + a.id)
    } catch (e) {
      setError(e)
      window.scrollTo(0, 0)
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <Head title={id ? 'Edit application' : 'New application'} sub="Minimum data for a first decision. Bank, bureau and GST data is fetched after the borrower's consent, so it is not typed here." />
      <ErrorBox error={error} />
      <div className="panel">
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
      </div>
      <div className="panel">
        <h3>Applicant</h3>
        <div className="form">
          {txt('applicantName', 'Full name (as on PAN)')}
          {txt('pan', 'PAN', { placeholder: 'ABCPK1234L', maxLength: 10 })}
          {txt('mobile', 'Mobile', { inputMode: 'numeric', maxLength: 10 })}
          {txt('email', 'Email', { type: 'email' })}
          {txt('dob', 'Date of birth', { type: 'date' })}
          <label className="fld">Gender
            <select name="gender" value={String(f.gender)} onChange={set('gender')}>
              <option value="">Prefer not to say</option><option>Female</option><option>Male</option><option>Other</option>
            </select>
          </label>
          {txt('city', 'City')}
          {txt('pincode', 'PIN code', { inputMode: 'numeric', maxLength: 6 })}
          <label className="fld wide">Address<input name="address" value={String(f.address)} onChange={set('address')} /></label>
        </div>
      </div>
      <div className="panel">
        <h3>Income and business</h3>
        <div className="form">
          {txt('businessName', 'Business or employer')}
          {txt('businessVintageYears', 'Years in business / employment', { type: 'number', min: 0, step: 0.5 })}
          {txt('declaredMonthlyIncome', 'Declared monthly income (₹)', { type: 'number', min: 0 })}
          {txt('essentialExpenses', 'Essential household expenses (₹/month)', { type: 'number', min: 0 })}
        </div>
      </div>
      <div className="panel">
        <h3>Disbursement account</h3>
        <div className="form">
          {txt('bankAccountNo', 'Bank account number', { inputMode: 'numeric' })}
          {txt('bankIfsc', 'IFSC', { maxLength: 11 })}
        </div>
      </div>
      <div className="panel">
        <h3>Borrower consent</h3>
        <label className="check"><input type="checkbox" name="consentKyc" checked={!!f.consentKyc} onChange={set('consentKyc')} /> KYC verification (PAN, Aadhaar offline e-KYC or DigiLocker, CKYC)</label>
        <label className="check"><input type="checkbox" name="consentAa" checked={!!f.consentAa} onChange={set('consentAa')} /> Bank statements through the Account Aggregator for the last 12 months</label>
        <label className="check"><input type="checkbox" name="consentBureau" checked={!!f.consentBureau} onChange={set('consentBureau')} /> Credit bureau report</label>
      </div>
      <div className="row">
        <button className="btn" disabled={busy} onClick={() => save(false)}>Save draft</button>
        <button className="btn primary" disabled={busy} onClick={() => save(true)}>Save and submit</button>
      </div>
    </>
  )
}
