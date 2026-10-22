// New application in short steps, as on the web. Works offline: the application, its documents and the submission are
// queued in order and sent when the signal returns. A draft is kept on the phone between steps.
import AsyncStorage from '@react-native-async-storage/async-storage'
import React, { useEffect, useState } from 'react'
import { Text, View } from 'react-native'
import { ApiError, OfflineError, api, getCached, message } from '../lib/api'
import { position } from '../lib/capture'
import type { LocalFile } from '../lib/files'
import { add, newId, resultOf } from '../lib/outbox'
import { useSession } from '../session'
import { Btn, C, Card, Check, Choice, Feedback, Input, Muted, Note, Row, Screen, Select, inr, label, useNav, useRun } from '../ui'
import { Capture, Picked, typesFor } from './Documents'
import { EMPTY_PARTY, PartyFields, partyBody, type PartyDraft, type PartyRules } from './Parties'

interface Product { code: string; name: string; status: string; minAmount: number; maxAmount: number; minTenure: number; maxTenure: number; requiredDocs: string; segment: string }
type Form = Record<string, string | boolean>

const EMPTY: Form = {
  productCode: 'BL-UNS', applicantName: '', pan: '', mobile: '', email: '', dob: '', gender: '', address: '', city: '', pincode: '',
  businessName: '', businessVintageYears: '3', declaredMonthlyIncome: '', essentialExpenses: '', loanAmount: '', tenureMonths: '24',
  purpose: '', bankAccountNo: '', bankIfsc: '', consentBureau: false, consentAa: false, consentKyc: false, preferredLanguage: 'en', industry: '', addressLat: '', addressLng: '',
}
const STEPS = ['Loan', 'Applicant', 'Income', 'People', 'Account & consent', 'Documents', 'Review']
const STEP_FIELDS = [['productCode', 'loanAmount', 'tenureMonths', 'purpose'], ['applicantName', 'pan', 'mobile', 'email', 'dob', 'gender', 'city', 'pincode', 'address'],
 ['businessName', 'industry', 'businessVintageYears', 'declaredMonthlyIncome', 'essentialExpenses'], [], ['bankAccountNo', 'bankIfsc', 'consentKyc', 'consentAa', 'consentBureau', 'preferredLanguage'], [], []]
const INDUSTRIES = ['KIRANA', 'DAIRY', 'TRANSPORT', 'MANUFACTURING', 'SERVICES', 'TRADING', 'AGRI', 'SALARIED', 'OTHER']
const DRAFT = 'draft:new-application'

function body(f: Form) {
  return {
    ...f,
    pan: String(f.pan).toUpperCase().trim(), bankIfsc: String(f.bankIfsc).toUpperCase().trim(), applicantName: String(f.applicantName).trim(),
    businessVintageYears: Number(f.businessVintageYears || 0), declaredMonthlyIncome: Number(f.declaredMonthlyIncome), essentialExpenses: Number(f.essentialExpenses || 0),
    loanAmount: Number(f.loanAmount), tenureMonths: Number(f.tenureMonths), email: f.email || null, gender: f.gender || null, industry: f.industry || null,
    addressLat: f.addressLat ? Number(f.addressLat) : null, addressLng: f.addressLng ? Number(f.addressLng) : null, businessName: f.businessName || null,
  }
}

export default function NewApplication({ id }: { id?: number }) {
  const nav = useNav()
  const { online, syncNow } = useSession()
  const [products, setProducts] = useState<Product[]>([])
  const [f, setF] = useState<Form>(EMPTY)
  const [step, setStep] = useState(0)
  const [docs, setDocs] = useState<{ type: string; file: LocalFile }[]>([])
  const [docType, setDocType] = useState('')
  const [bad, setBad] = useState<Record<string, string>>({})
  const [people, setPeople] = useState<PartyDraft[]>([])
  const [rules, setRules] = useState<PartyRules | null>(null)
  const r = useRun()

  useEffect(() => {
    getCached<Product[]>('/api/products').then(x => setProducts(x.data.filter(p => p.status === 'LIVE'))).catch(e => r.setError(message(e)))
    if (id) api.get<Record<string, unknown>>(`/api/applications/${id}/draft`).then(a => {
      const g: Form = { ...EMPTY }
      for (const k of Object.keys(EMPTY)) { const v = a[k]; if (v !== undefined && v !== null) g[k] = typeof v === 'boolean' ? v : String(v) }
      setF(g)
    }).catch(e => r.setError(message(e)))
    else AsyncStorage.getItem(DRAFT).then(s => { if (s) { setF({ ...EMPTY, ...JSON.parse(s) }); r.setOk('Draft restored from this phone') } })
  }, [id]) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => { if (!id) AsyncStorage.setItem(DRAFT, JSON.stringify(f)).catch(() => undefined) }, [f, id])

  const p = products.find(x => x.code === f.productCode)
  useEffect(() => {
    getCached<Partial<PartyRules>>(`/api/products/${f.productCode}/party-rules`).then(x => {
      const r = x.data ?? {}
      const slot = { mode: 'NOT_ALLOWED' as const, max: 0, requiredWhen: '' }
      setRules({ coApplicant: { ...slot, ...r.coApplicant }, guarantor: { ...slot, ...r.guarantor }, incomeClubbing: { allowed: false, relationships: [], ...r.incomeClubbing },
        relationships: r.relationships?.length ? r.relationships : ['SPOUSE', 'PARENT', 'CHILD', 'SIBLING', 'BUSINESS_PARTNER', 'DIRECTOR', 'RELATIVE', 'FRIEND', 'OTHER'], note: r.note })
    }).catch(() => setRules(null))
  }, [f.productCode])
  const set = (k: string) => (v: string | boolean) => setF(x => ({ ...x, [k]: v }))
  const txt = (k: string, l: string, extra: Record<string, unknown> = {}) =>
    <Input label={l} value={String(f[k] ?? '')} onChangeText={set(k)} error={bad[k]} testID={'f-' + k} {...extra} />

  function check(): string | null {
    const e: Record<string, string> = {}
    if (step === 0) {
      if (!Number(f.loanAmount)) e.loanAmount = 'Enter the amount'
      else if (p && (Number(f.loanAmount) < p.minAmount || Number(f.loanAmount) > p.maxAmount)) e.loanAmount = `Between ${inr(p.minAmount)} and ${inr(p.maxAmount)}`
      if (p && (Number(f.tenureMonths) < p.minTenure || Number(f.tenureMonths) > p.maxTenure)) e.tenureMonths = `${p.minTenure}–${p.maxTenure} months`
    }
    if (step === 1) {
      if (String(f.applicantName).trim().length < 3) e.applicantName = 'Full name as on PAN'
      if (!/^[A-Z]{3}P[A-Z][0-9]{4}[A-Z]$/.test(String(f.pan).toUpperCase())) e.pan = 'A personal PAN, like ABCPK1234L'
      if (!/^[6-9][0-9]{9}$/.test(String(f.mobile))) e.mobile = '10-digit mobile number'
      if (!/^\d{4}-\d{2}-\d{2}$/.test(String(f.dob))) e.dob = 'YYYY-MM-DD'
      if (f.pincode && !/^\d{6}$/.test(String(f.pincode))) e.pincode = '6 digits'
    }
    if (step === 2 && !Number(f.declaredMonthlyIncome)) e.declaredMonthlyIncome = 'Monthly income'
    if (step === 3) people.forEach((x, i) => {
      if (x.name.trim().length < 3) e['party' + i] = `Person ${i + 1}: full name`
      else if (!/^[A-Z]{5}[0-9]{4}[A-Z]$/.test(x.pan.toUpperCase().trim())) e['party' + i] = `Person ${i + 1}: PAN like ABCPK1234L`
      else if (!/^[6-9][0-9]{9}$/.test(x.mobile)) e['party' + i] = `Person ${i + 1}: their own 10-digit mobile`
      else if (x.dob && !/^\d{4}-\d{2}-\d{2}$/.test(x.dob)) e['party' + i] = `Person ${i + 1}: date as YYYY-MM-DD`
    })
    if (step === 4 && !(f.consentKyc && f.consentBureau && f.consentAa)) e.consent = 'All three consents are needed for a decision'
    setBad(e)
    return Object.keys(e).length ? Object.values(e)[0] : null
  }

  function next() { if (!check()) setStep(s => Math.min(s + 1, STEPS.length - 1)) }

  async function locate() {
    await r.run(async () => {
      const pos = await position()
      if (!pos) throw new Error('No GPS position: turn on location and try again')
      setF(x => ({ ...x, addressLat: pos.lat.toFixed(6), addressLng: pos.lng.toFixed(6) }))
    }, 'Business location saved')
  }

  async function save(submit: boolean) {
    const b = body(f)
    const appJob = newId()
    const name = `${b.applicantName} · ${inr(b.loanAmount)}`
    await r.run(async () => {
      if (id) {
        await api.put(`/api/applications/${id}`, b)
        for (const x of people) await add({ id: newId(), kind: 'upload', label: `${x.name} (${x.role === 'GUARANTOR' ? 'guarantor' : 'co-applicant'}) for ${name}`, method: 'POST', path: `/api/applications/${id}/parties`, json: partyBody(x) })
        for (const d of docs) await add({ id: newId(), kind: 'document', label: `${label(d.type)} for ${name}`, method: 'POST', path: `/api/applications/${id}/documents`, fields: [{ name: 'type', value: d.type }, { name: 'file', file: d.file }] })
        if (submit) await add({ id: newId(), kind: 'submit', label: `Submit ${name}`, method: 'POST', path: `/api/applications/${id}/submit`, json: {} })
        await syncNow()
        nav.replace('app', { id })
        return
      }
      let made: number | undefined
      if (online) {
        try { made = (await api.post<{ id: number }>('/api/applications', b)).id } catch (e) {
          if (!(e instanceof OfflineError)) {
            const fields = (e as ApiError).fields ?? {}
            const at = STEP_FIELDS.findIndex(st => st.some(k => k in fields))
            if (at >= 0) { setStep(at); setBad(fields) }
            throw e
          }
        }
      }
      if (made) {
        for (const x of people) await add({ id: newId(), kind: 'upload', label: `${x.name} (${x.role === 'GUARANTOR' ? 'guarantor' : 'co-applicant'}) for ${name}`, method: 'POST', path: `/api/applications/${made}/parties`, json: partyBody(x) })
        for (const d of docs) await add({ id: newId(), kind: 'document', label: `${label(d.type)} for ${name}`, method: 'POST', path: `/api/applications/${made}/documents`, fields: [{ name: 'type', value: d.type }, { name: 'file', file: d.file }] })
        if (submit) await add({ id: newId(), kind: 'submit', label: `Submit ${name}`, method: 'POST', path: `/api/applications/${made}/submit`, json: {} })
        await AsyncStorage.removeItem(DRAFT)
        await syncNow()
        nav.replace('app', { id: made })
        return
      }
      await add({ id: appJob, kind: 'application', label: `New application: ${name}`, method: 'POST', path: '/api/applications', json: b })
      for (const x of people) await add({ id: newId(), kind: 'upload', label: `${x.name} (${x.role === 'GUARANTOR' ? 'guarantor' : 'co-applicant'}) for ${name}`, method: 'POST', path: '/api/applications/:app/parties', dependsOn: appJob, json: partyBody(x) })
      for (const d of docs) await add({ id: newId(), kind: 'document', label: `${label(d.type)} for ${name}`, method: 'POST', path: '/api/applications/:app/documents', dependsOn: appJob, fields: [{ name: 'type', value: d.type }, { name: 'file', file: d.file }] })
      if (submit) await add({ id: newId(), kind: 'submit', label: `Submit ${name}`, method: 'POST', path: '/api/applications/:app/submit', dependsOn: appJob, json: {} })
      await AsyncStorage.removeItem(DRAFT)
      const s = await syncNow()
      const created = await resultOf(appJob)
      if (created) { nav.replace('app', { id: created }); return }
      if (s && s.failed.length) { nav.replace('outbox'); return }
      setF(EMPTY); setDocs([]); setPeople([]); setStep(0)
      return 'Saved on this phone. It will be created and submitted when you are back online (see Outbox).'
    }, x => typeof x === 'string' ? x : 'Saved')
  }

  const types = p ? typesFor(p.requiredDocs) : []
  const dt = docType || types[0] || 'PAN'

  return (
    <Screen title={id ? 'Edit application' : 'New application'} sub={`Step ${step + 1} of ${STEPS.length}: ${STEPS[step]}`} testID="new-app">
      <View style={{ flexDirection: 'row', gap: 4, marginBottom: 12 }}>
        {STEPS.map((s, i) => <View key={s} style={{ flex: 1, height: 5, borderRadius: 3, backgroundColor: i <= step ? C.brand : C.line }} />)}
      </View>
      {!online && <Note kind="warn">Offline: the application will be saved on this phone and sent when you are back online.</Note>}
      <Feedback error={r.error ?? Object.values(bad)[0]} ok={r.ok} />

      {step === 0 && <Card title="Product and loan">
        <Select label="Product" value={String(f.productCode)} options={products.map(x => [x.code, x.name])} onChange={set('productCode')} testID="f-product" />
        {p ? <Muted>{inr(p.minAmount)}–{inr(p.maxAmount)} · {p.minTenure}–{p.maxTenure} months · documents {p.requiredDocs.replace(/,/g, ', ').replace(/\|/g, ' or ')}</Muted> : null}
        {txt('loanAmount', 'Loan amount (₹)', { keyboardType: 'numeric' })}
        {txt('tenureMonths', 'Tenure (months)', { keyboardType: 'numeric' })}
        {txt('purpose', 'Purpose')}
      </Card>}

      {step === 1 && <Card title="Applicant">
        {txt('applicantName', 'Full name (as on PAN)')}
        {txt('pan', 'PAN', { autoCapitalize: 'characters', maxLength: 10, placeholder: 'ABCPK1234L' })}
        {txt('mobile', 'Mobile', { keyboardType: 'phone-pad', maxLength: 10 })}
        {txt('email', 'Email (optional)', { keyboardType: 'email-address', autoCapitalize: 'none' })}
        {txt('dob', 'Date of birth (YYYY-MM-DD)', { placeholder: '1985-06-15', maxLength: 10 })}
        <Choice label="Gender" value={String(f.gender)} options={[['', 'Not said'], ['FEMALE', 'Female'], ['MALE', 'Male'], ['OTHER', 'Other']]} onChange={set('gender')} />
        {txt('address', 'Address', { multiline: true })}
        {txt('city', 'City')}
        {txt('pincode', 'PIN code', { keyboardType: 'number-pad', maxLength: 6 })}
        <Btn small title={f.addressLat ? `Location saved (${f.addressLat}, ${f.addressLng})` : "I'm at the business: save its location"} onPress={locate} busy={r.busy} testID="locate" />
      </Card>}

      {step === 2 && <Card title="Income">
        {txt('businessName', 'Business or employer')}
        <Choice label="Industry" value={String(f.industry)} options={INDUSTRIES.map(x => [x, label(x)])} onChange={set('industry')} testID="f-industry" />
        {txt('businessVintageYears', 'Years in business / job', { keyboardType: 'numeric' })}
        {txt('declaredMonthlyIncome', 'Monthly income (₹)', { keyboardType: 'numeric' })}
        {txt('essentialExpenses', 'Monthly household expenses (₹)', { keyboardType: 'numeric' })}
        <Muted>Bank, bureau and GST data are fetched after consent, so they are not typed here.</Muted>
      </Card>}

      {step === 3 && <Card title="Co-applicants and guarantors" testID="form-parties">
        {!rules || (rules.coApplicant.mode === 'NOT_ALLOWED' && rules.guarantor.mode === 'NOT_ALLOWED') ? <Muted>This product takes no co-applicants or guarantors.</Muted> : <>
          <Muted>Co-applicant: {label(rules.coApplicant.mode).toLowerCase()}{rules.coApplicant.requiredWhen ? ` (required when ${rules.coApplicant.requiredWhen})` : ''} · guarantor: {label(rules.guarantor.mode).toLowerCase()}{rules.guarantor.requiredWhen ? ` (required before sanction when ${rules.guarantor.requiredWhen})` : ''}{rules.note ? '. ' + rules.note : ''}</Muted>
          {people.map((x, i) => (
            <View key={i} style={{ borderTopWidth: 1, borderTopColor: C.line, paddingTop: 10, marginTop: 6 }}>
              <Text style={{ fontWeight: '700', color: C.ink, marginBottom: 6 }}>{x.role === 'GUARANTOR' ? 'Guarantor' : 'Co-applicant'} {i + 1}</Text>
              <PartyFields n={i + 1} p={x} rules={rules} relationships={rules.relationships} onChange={y => setPeople(people.map((z, j) => j === i ? y : z))} />
              <Btn small title="Remove" onPress={() => setPeople(people.filter((_, j) => j !== i))} />
            </View>
          ))}
          <Btn title="Add a person" testID="party-form-add" onPress={() => setPeople([...people, { ...EMPTY_PARTY, role: rules.coApplicant.mode !== 'NOT_ALLOWED' ? 'CO_APPLICANT' : 'GUARANTOR', relationship: rules.relationships[0] ?? 'SPOUSE' }])} />
          {people.length > 0 && <Muted>Each person gives their own consent by OTP after saving (in person or by invite link).</Muted>}
        </>}
      </Card>}

      {step === 4 && <Card title="Account and consent">
        {txt('bankAccountNo', 'Bank account number', { keyboardType: 'number-pad' })}
        {txt('bankIfsc', 'IFSC', { autoCapitalize: 'characters', maxLength: 11 })}
        <Choice label="Borrower's language" value={String(f.preferredLanguage)} options={[['en', 'English'], ['hi', 'Hindi'], ['mr', 'Marathi'], ['ta', 'Tamil'], ['ml', 'Malayalam'], ['kn', 'Kannada'], ['te', 'Telugu'], ['bn', 'Bengali'], ['gu', 'Gujarati']]} onChange={set('preferredLanguage')} />
        <Check label="KYC: the borrower agrees to verify PAN and Aadhaar (masked to the last 4 digits)" value={!!f.consentKyc} onChange={set('consentKyc')} />
        <Check label="Credit bureau: the borrower agrees to a bureau check" value={!!f.consentBureau} onChange={set('consentBureau')} />
        <Check label="Account Aggregator: the borrower agrees to share bank statements" value={!!f.consentAa} onChange={set('consentAa')} />
      </Card>}

      {step === 5 && <Card title="Documents (optional now)">
        <Muted>Capture what the borrower has with them; the rest can be added later from the application.</Muted>
        <Choice label="Type" value={dt} options={types.map(t => [t, label(t)])} onChange={setDocType} testID="new-doc-type" />
        <Capture name={dt.toLowerCase()} onFile={file => setDocs(d => [...d.filter(x => x.type !== dt), { type: dt, file }])} testID="new-doc" />
        {docs.map(d => <View key={d.type}><Text style={{ fontWeight: '600', color: C.ink, marginTop: 6 }}>{label(d.type)}</Text><Picked f={d.file} /></View>)}
      </Card>}

      {step === 6 && <Card title="Review">
        <Row k="Product" v={p?.name ?? String(f.productCode)} />
        <Row k="Loan" v={`${inr(Number(f.loanAmount))} · ${f.tenureMonths} months`} />
        <Row k="Applicant" v={String(f.applicantName)} />
        <Row k="PAN" v={String(f.pan).toUpperCase().replace(/^(.{2}).{5}/, '$1•••••')} />
        <Row k="Mobile" v={String(f.mobile)} />
        <Row k="Income" v={inr(Number(f.declaredMonthlyIncome))} />
        <Row k="Business location" v={f.addressLat ? 'Saved' : 'Not saved'} />
        <Row k="Co-applicants / guarantors" v={people.length ? people.map(x => x.name).join(', ') : 'None'} />
        <Row k="Documents" v={docs.length ? docs.map(d => label(d.type)).join(', ') : 'None yet'} />
        <Btn title={online ? 'Save and submit' : 'Save and submit when online'} kind="primary" busy={r.busy} onPress={() => save(true)} testID="save-submit" />
        <Btn title="Save as draft" busy={r.busy} onPress={() => save(false)} testID="save-draft" />
      </Card>}

      <View style={{ flexDirection: 'row', gap: 8 }}>
        {step > 0 && <Btn style={{ flex: 1 }} title="Back" onPress={() => setStep(s => s - 1)} testID="prev" />}
        {step < STEPS.length - 1 && <Btn style={{ flex: 1 }} kind="primary" title="Next" onPress={next} testID="next" />}
      </View>
    </Screen>
  )
}
