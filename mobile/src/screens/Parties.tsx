// Co-applicants and guarantors on the phone: add them (people or, where the product allows, a company or firm as
// guarantor), their consent and signature by OTP on their own mobile, an invite link for their own phone, their KYC
// documents, KYC and bureau checks, income, and credit's requests and waivers. Same rules as the web console.
import * as Clipboard from 'expo-clipboard'
import React, { useCallback, useEffect, useState } from 'react'
import { Share, Text, View } from 'react-native'
import { api, getCached, isWeb, message, url } from '../lib/api'
import type { LocalFile } from '../lib/files'
import { add, newId } from '../lib/outbox'
import { useSession } from '../session'
import { Btn, C, Card, Check, Choice, Feedback, Input, Muted, Note, Pill, Row, Screen, Sheet, Spinner, inr, label, useRun, when } from '../ui'
import { Capture, Picked } from './Documents'
import { OfflineBanner } from './Lists'

interface Slot { mode: 'NOT_ALLOWED' | 'OPTIONAL' | 'MANDATORY'; max: number; requiredWhen: string }
export interface PartyRules { coApplicant: Slot; guarantor: Slot; incomeClubbing: { allowed: boolean; relationships: string[]; verify?: boolean }; relationships: string[]; entityGuarantors?: boolean; note?: string }
interface PartyRow {
  id: number; partyNo: string; role: string; relationship: string; name: string; pan: string; dob: string; monthlyIncome?: number; existingEmi?: number; incomeConsidered: boolean
  coOwner: boolean; status: string; consentAt?: string; kycState: string; kycNote?: string; bureauState: string; bureauScore?: number; signedAt?: string; signedDocument?: string
  removedReason?: string; partyType?: string; signatoryName?: string; consentAa?: boolean; verifiedIncome?: number
}
interface Requirement { id: number; role: string; reason: string; status: string; requestedBy: string; closeNote?: string }
interface View_ {
  rules: PartyRules; parties: { party: PartyRow; mobile: string; documents: { id: number; docType: string; status: string; masked: boolean }[]; inviteActive: boolean; incomeCounted: boolean; issues: string[]; vkyc?: string | null }[]
  requirements: Requirement[]; issues: { gate: string; message: string }[]; canChange: boolean; sanctioned: boolean; documentTypes: string[]; relationships: string[]; entityTypes?: string[]
  joint?: { coApplicants: number; guarantors: number; clubbedIncome: number; policyFails: string[] } | null
}
export const ENTITY_RELATIONSHIPS = ['GROUP_COMPANY', 'EMPLOYER', 'BUSINESS_ASSOCIATE', 'OTHER']
const GATE: Record<string, string> = { SUBMIT: 'before submission', DECISION: 'before the decision', SANCTION: 'before sanction', DISBURSE: 'before disbursement' }
const roleLabel = (r: string) => r === 'GUARANTOR' ? 'Guarantor' : 'Co-applicant'

export interface PartyDraft {
  role: string; relationship: string; name: string; pan: string; mobile: string; email: string; dob: string; gender: string; city: string; pincode: string
  occupation: string; monthlyIncome: string; existingEmi: string; incomeConsidered: boolean; coOwner: boolean; requirementId?: number
  partyType: string; signatoryName: string; signatoryDesignation: string; registrationNo: string; gstin: string
}
export const EMPTY_PARTY: PartyDraft = { role: 'CO_APPLICANT', relationship: 'SPOUSE', name: '', pan: '', mobile: '', email: '', dob: '', gender: '', city: '', pincode: '', occupation: '',
  monthlyIncome: '', existingEmi: '', incomeConsidered: false, coOwner: false, partyType: 'INDIVIDUAL', signatoryName: '', signatoryDesignation: '', registrationNo: '', gstin: '' }

export function partyBody(p: PartyDraft) {
  const entity = p.partyType !== 'INDIVIDUAL'
  return { ...p, partyType: p.partyType || 'INDIVIDUAL', signatoryName: entity ? p.signatoryName || null : null, signatoryDesignation: entity ? p.signatoryDesignation || null : null,
    registrationNo: entity ? p.registrationNo || null : null, gstin: entity ? p.gstin.toUpperCase() || null : null, gender: entity ? null : p.gender || null,
    incomeConsidered: entity ? false : p.incomeConsidered, coOwner: entity ? false : p.coOwner, pan: p.pan.toUpperCase().trim(), email: p.email || null, city: p.city || null,
    pincode: p.pincode || null, occupation: p.occupation || null, monthlyIncome: p.monthlyIncome === '' ? null : Number(p.monthlyIncome),
    existingEmi: p.existingEmi === '' ? null : Number(p.existingEmi), dob: p.dob || null, name: p.name.trim() }
}

/** The person's (or firm's) details; on the Parties screen and in the new-application wizard. */
export function PartyFields({ p, rules, relationships, entityTypes = [], onChange, n = 1 }: { p: PartyDraft; rules: PartyRules; relationships: string[]; entityTypes?: string[]; onChange: (p: PartyDraft) => void; n?: number }) {
  const entity = p.partyType !== 'INDIVIDUAL'
  const set = (k: keyof PartyDraft) => (v: string | boolean) => onChange({ ...p, [k]: v })
  const clubs = p.role === 'CO_APPLICANT' && rules.incomeClubbing.allowed && (rules.incomeClubbing.relationships.length === 0 || rules.incomeClubbing.relationships.includes(p.relationship))
  const roles: [string, string][] = ([['CO_APPLICANT', 'Co-applicant'], ['GUARANTOR', 'Guarantor']] as [string, string][]).filter(([r]) => (r === 'GUARANTOR' ? rules.guarantor : rules.coApplicant).mode !== 'NOT_ALLOWED')
  const t = (k: keyof PartyDraft, l: string, extra: Record<string, unknown> = {}) => <Input label={l} value={String(p[k] ?? '')} onChangeText={set(k)} testID={`party${n}-${k}`} {...extra} />
  return (
    <View>
      <Choice label="Role" value={p.role} options={roles} testID={`party${n}-role`}
        onChange={r => onChange({ ...p, role: r, partyType: r === 'GUARANTOR' ? p.partyType : 'INDIVIDUAL', relationship: r === 'GUARANTOR' && entity ? p.relationship : relationships[0] ?? 'SPOUSE' })} />
      {p.role === 'GUARANTOR' && entityTypes.length > 0 && <Choice label="Guarantor is" value={p.partyType} options={[['INDIVIDUAL', 'A person'], ...entityTypes.map(x => [x, label(x)] as [string, string])]}
        onChange={x => onChange({ ...p, partyType: x, relationship: x === 'INDIVIDUAL' ? relationships[0] ?? 'SPOUSE' : ENTITY_RELATIONSHIPS[0] })} />}
      <Choice label="Relationship to the applicant" value={p.relationship} options={(entity ? ENTITY_RELATIONSHIPS : relationships).map(r => [r, label(r)])} onChange={set('relationship')} testID={`party${n}-relationship`} />
      {t('name', entity ? 'Registered name (as on PAN)' : 'Full name (as on PAN)')}
      {t('pan', 'PAN', { autoCapitalize: 'characters', maxLength: 10, placeholder: entity ? 'ABCCK1234L' : 'ABCPK1234L' })}
      {t('mobile', entity ? "Signatory's mobile (OTPs go here)" : 'Their own mobile (OTPs go here)', { keyboardType: 'phone-pad', maxLength: 10 })}
      {t('dob', entity ? 'Date of incorporation (YYYY-MM-DD)' : 'Date of birth (YYYY-MM-DD)', { maxLength: 10 })}
      {entity && <>{t('signatoryName', 'Authorised signatory')}{t('signatoryDesignation', 'Signatory designation')}{t('registrationNo', 'CIN, LLPIN or registration no.')}{t('gstin', 'GSTIN (optional)', { autoCapitalize: 'characters', maxLength: 15 })}</>}
      {!entity && <Choice label="Gender" value={p.gender} options={[['', 'Not said'], ['FEMALE', 'Female'], ['MALE', 'Male'], ['OTHER', 'Other']]} onChange={set('gender')} testID={`party${n}-gender`} />}
      {t('city', 'City')}
      {!entity && t('occupation', 'Occupation or business')}
      {t('monthlyIncome', entity ? 'Monthly turnover or profit (₹)' : 'Monthly income (₹)', { keyboardType: 'numeric' })}
      {t('existingEmi', 'Their existing EMIs (₹/month)', { keyboardType: 'numeric' })}
      {!entity && clubs && <Check label={'Count this income for affordability' + (rules.incomeClubbing.verify ? ' (checked against their own bank data)' : '')} value={p.incomeConsidered} onChange={set('incomeConsidered')} />}
      {p.role === 'CO_APPLICANT' && <Check label="Co-owns the collateral" value={p.coOwner} onChange={set('coOwner')} />}
    </View>
  )
}

type Dlg = { kind: 'add' | 'request' } | { kind: 'consent' | 'sign' | 'docs' | 'remove' | 'kyc' | 'income' | 'invite'; p: PartyRow } | { kind: 'waive'; q: Requirement }

export default function PartiesScreen({ id }: { id: number }) {
  const { user } = useSession()
  const [d, setD] = useState<View_ | null>(null)
  const [savedAt, setSavedAt] = useState<number>()
  const [dlg, setDlg] = useState<Dlg | null>(null)
  const r = useRun()
  const load = useCallback(() => { getCached<View_>(`/api/applications/${id}/parties`).then(x => { setD(x.data); setSavedAt(x.savedAt) }).catch(e => r.setError(message(e))) }, [id]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { load() }, [load])
  const role = user.role
  const front = ['SALES', 'OPERATIONS', 'ADMIN', 'CREDIT_OFFICER', 'CREDIT_MANAGER'].includes(role)
  const canAdd = ['SALES', 'OPERATIONS', 'ADMIN', 'CREDIT_OFFICER'].includes(role)
  const credit = ['CREDIT_OFFICER', 'CREDIT_MANAGER', 'CRO', 'ADMIN'].includes(role)
  const done = (m: string) => { setDlg(null); r.setOk(m); load() }
  if (!d) return <Screen title="Co-applicants & guarantors">{r.error ? <Note kind="bad">{r.error}</Note> : <Spinner />}</Screen>
  const open = d.requirements.filter(q => q.status === 'OPEN')
  const allowed = d.rules.coApplicant.mode !== 'NOT_ALLOWED' || d.rules.guarantor.mode !== 'NOT_ALLOWED'
  return (
    <Screen title="Co-applicants & guarantors" testID="parties">
      <OfflineBanner savedAt={savedAt} />
      <Feedback error={r.error} ok={r.ok} />
      {!allowed ? <Card><Muted>This product takes no co-applicants or guarantors.</Muted></Card> : <>
        <Card title="Still needed" testID="party-issues">
          {d.issues.length === 0 ? <Muted>Nothing: every party rule is met.</Muted> : d.issues.map((i, k) => <Text key={k} style={{ color: C.ink, marginBottom: 4 }}>• {i.message} <Text style={{ color: C.muted }}>({GATE[i.gate] ?? i.gate})</Text></Text>)}
          {d.rules.note ? <Muted>{d.rules.note}</Muted> : null}
          {d.joint && d.joint.clubbedIncome > 0 ? <Muted>Income counted with the applicant: {inr(d.joint.clubbedIncome)}/month</Muted> : null}
        </Card>
        {(open.length > 0 || credit) && <Card title="Requests from credit">
          {d.requirements.length === 0 ? <Muted>No requests.</Muted> : d.requirements.map(q => <View key={q.id} style={{ marginBottom: 6 }}>
            <Text style={{ color: C.ink }}>{roleLabel(q.role)}: {q.reason}</Text>
            <View style={{ flexDirection: 'row', gap: 8, alignItems: 'center' }}><Pill s={q.status === 'OPEN' ? 'REFER' : 'PASS'} text={label(q.status)} /><Muted>by {q.requestedBy}{q.closeNote ? ' · ' + q.closeNote : ''}</Muted></View>
            {q.status === 'OPEN' && ['CREDIT_MANAGER', 'CRO'].includes(role) && <Btn small title="Waive" onPress={() => setDlg({ kind: 'waive', q })} />}
          </View>)}
          {credit && d.canChange && <Btn small title="Ask for a co-applicant or guarantor" onPress={() => setDlg({ kind: 'request' })} />}
        </Card>}
        {canAdd && d.canChange && <Btn title="Add co-applicant or guarantor" kind="primary" testID="party-add" onPress={() => setDlg({ kind: 'add' })} />}
        {['OPERATIONS', 'ADMIN', 'CREDIT_OFFICER'].includes(role) && d.parties.some(x => x.party.consentAt && x.party.status !== 'REMOVED') &&
          <Btn title="Run KYC and bureau" busy={r.busy} testID="party-checks" onPress={() => r.run(() => api.post<{ log: string[] }>(`/api/applications/${id}/parties/checks`), x => (x?.log ?? []).join(' · ') || 'Nothing to run').then(load)} />}
        {d.parties.length === 0 ? <Card><Muted>No co-applicants or guarantors on this application.</Muted></Card> : null}
        {d.parties.map(x => {
          const p = x.party
          const removed = p.status === 'REMOVED'
          return (
            <Card key={p.id} testID={'party-' + p.partyNo}>
              <Text style={{ fontSize: 16, fontWeight: '700', color: C.ink }}>{p.name} <Text style={{ color: C.muted, fontWeight: '400' }}>{p.partyNo}</Text></Text>
              <Muted>{roleLabel(p.role)} · {label(p.relationship)}{p.partyType && p.partyType !== 'INDIVIDUAL' ? ` · ${label(p.partyType)}, signs: ${p.signatoryName}` : ''}{p.coOwner ? ' · co-owner' : ''} · {x.mobile}</Muted>
              {removed ? <Note kind="warn">Removed: {p.removedReason}</Note> : <>
                <Row k="Consent" v={<Pill s={p.consentAt ? 'PASS' : 'PENDING'} text={p.consentAt ? 'given' : x.inviteActive ? 'invited' : 'not yet'} />} testID={'consent-' + p.partyNo} />
                <Row k="KYC" v={<Pill s={p.kycState === 'VERIFIED' ? 'PASS' : p.kycState === 'FAILED' ? 'FAIL' : p.kycState === 'REVIEW' ? 'REFER' : 'PENDING'} text={label(p.kycState)} />} testID={'kyc-' + p.partyNo} />
                {p.kycNote ? <Muted>{p.kycNote}</Muted> : null}
                {x.vkyc ? <Row k="Video KYC" v={label(x.vkyc)} /> : null}
                <Row k="Bureau" v={p.bureauState === 'FETCHED' ? String(p.bureauScore) : label(p.bureauState)} />
                <Row k="Income" v={(p.monthlyIncome ? inr(p.monthlyIncome) + '/mo' : '—') + (p.incomeConsidered ? (x.incomeCounted ? ' · counted' : ' · not countable yet') : '')} />
                <Row k="Documents" v={String(x.documents.length)} />
                <Row k="Signed" v={p.signedAt ? label(p.signedDocument ?? 'signed') : '—'} testID={'signed-' + p.partyNo} />
                {x.issues.length > 0 ? <Note kind="warn">{x.issues.join('; ')}</Note> : null}
                <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 6, marginTop: 6 }}>
                  {front && (!p.consentAt || (p.incomeConsidered && !p.consentAa && d.rules.incomeClubbing.verify)) && <Btn small title="Consent by OTP" testID={'consent-btn-' + p.partyNo} onPress={() => setDlg({ kind: 'consent', p })} />}
                  {front && !p.consentAt && <Btn small title="Invite link" onPress={() => setDlg({ kind: 'invite', p })} />}
                  {front && <Btn small title={`Documents (${x.documents.length})`} testID={'docs-btn-' + p.partyNo} onPress={() => setDlg({ kind: 'docs', p })} />}
                  {['OPERATIONS', 'ADMIN'].includes(role) && p.kycState === 'REVIEW' && <Btn small title="Resolve KYC" onPress={() => setDlg({ kind: 'kyc', p })} />}
                  {['OPERATIONS', 'ADMIN', 'CREDIT_OFFICER', 'CREDIT_MANAGER', 'CRO'].includes(role) && d.canChange && <Btn small title="Income" onPress={() => setDlg({ kind: 'income', p })} />}
                  {front && d.sanctioned && !p.signedAt && <Btn small kind="primary" title="Sign by OTP" testID={'sign-btn-' + p.partyNo} onPress={() => setDlg({ kind: 'sign', p })} />}
                  {canAdd && d.canChange && <Btn small kind="danger" title="Remove" onPress={() => setDlg({ kind: 'remove', p })} />}
                </View>
              </>}
            </Card>
          )
        })}
      </>}
      {dlg?.kind === 'add' && <AddSheet appId={id} d={d} open={open} onClose={() => setDlg(null)} onDone={done} />}
      {(dlg?.kind === 'consent' || dlg?.kind === 'sign') && <OtpSheet p={dlg.p} kind={dlg.kind} needsAa={dlg.p.incomeConsidered && !!d.rules.incomeClubbing.verify} onClose={() => setDlg(null)} onDone={done} />}
      {dlg?.kind === 'invite' && <InviteSheet p={dlg.p} onClose={() => setDlg(null)} />}
      {dlg?.kind === 'docs' && <DocsSheet p={dlg.p} types={d.documentTypes} docs={d.parties.find(x => x.party.id === dlg.p.id)?.documents ?? []} onClose={() => setDlg(null)} onDone={done} />}
      {dlg?.kind === 'remove' && <NoteSheet title={`Remove ${dlg.p.name}`} hint="Why is this person leaving the loan? (at least 10 characters)" path={`/api/parties/${dlg.p.id}/remove`} onClose={() => setDlg(null)} onDone={() => done('Removed')} />}
      {dlg?.kind === 'waive' && <NoteSheet title="Waive the request" hint={`Asked: ${dlg.q.reason}. Why is it not needed? (at least 15 characters)`} path={`/api/party-requests/${dlg.q.id}/waive`} onClose={() => setDlg(null)} onDone={() => done('Request waived')} />}
      {dlg?.kind === 'kyc' && <KycSheet p={dlg.p} onClose={() => setDlg(null)} onDone={() => done('KYC resolved')} />}
      {dlg?.kind === 'income' && <IncomeSheet p={dlg.p} onClose={() => setDlg(null)} onDone={() => done('Income saved')} />}
      {dlg?.kind === 'request' && <RequestSheet appId={id} rules={d.rules} onClose={() => setDlg(null)} onDone={() => done('Request sent to sales')} />}
    </Screen>
  )
}

function AddSheet({ appId, d, open, onClose, onDone }: { appId: number; d: View_; open: Requirement[]; onClose: () => void; onDone: (m: string) => void }) {
  const first = d.rules.coApplicant.mode !== 'NOT_ALLOWED' ? 'CO_APPLICANT' : 'GUARANTOR'
  const [p, setP] = useState<PartyDraft>({ ...EMPTY_PARTY, role: open[0]?.role ?? first, relationship: d.relationships[0] ?? 'SPOUSE', requirementId: open[0]?.id })
  const r = useRun()
  return (
    <Sheet visible title="Add co-applicant or guarantor" onClose={onClose}>
      <Feedback error={r.error} />
      {open.length > 0 && <Choice label="Meets the request from credit" value={String(p.requirementId ?? '')} options={[['', '—'], ...open.map(q => [String(q.id), `${roleLabel(q.role)}: ${q.reason}`] as [string, string])]}
        onChange={v => { const q = open.find(x => String(x.id) === v); setP({ ...p, requirementId: q?.id, role: q?.role ?? p.role }) }} />}
      <PartyFields p={p} rules={d.rules} relationships={d.relationships} entityTypes={d.rules.entityGuarantors ? d.entityTypes ?? [] : []} onChange={setP} />
      <Muted>They give their own consent by OTP on their mobile, are KYC- and bureau-checked on their own, and sign before disbursement.</Muted>
      <Btn title="Add" kind="primary" busy={r.busy} testID="party-save" onPress={() => r.run(() => api.post(`/api/applications/${appId}/parties`, partyBody(p))).then(x => { if (x) onDone(`${p.name} added`) })} />
    </Sheet>
  )
}

function OtpSheet({ p, kind, needsAa, onClose, onDone }: { p: PartyRow; kind: 'consent' | 'sign'; needsAa: boolean; onClose: () => void; onDone: (m: string) => void }) {
  const [sent, setSent] = useState<{ mobile: string; demo: boolean }>()
  const [otp, setOtp] = useState('')
  const [kyc, setKyc] = useState(false)
  const [bureau, setBureau] = useState(false)
  const [aa, setAa] = useState(false)
  const [lang, setLang] = useState('en')
  const r = useRun()
  const ready = !!sent && otp.length >= 4 && (kind === 'sign' || (kyc && bureau && (!needsAa || aa)))
  return (
    <Sheet visible title={kind === 'consent' ? `${p.name}: consent` : `${p.name}: sign the ${p.role === 'GUARANTOR' ? 'guarantee' : 'loan agreement and KFS'}`} onClose={onClose}>
      <Feedback error={r.error} />
      {kind === 'consent' ? <>
        <Muted>Read this to {p.name} in their language. As {p.role === 'GUARANTOR' ? 'guarantor they agree to repay if the borrower does not' : 'co-applicant they are jointly and severally liable for the whole loan'}.</Muted>
        <Check label="Verify my identity (PAN and the identity documents I give)" value={kyc} onChange={setKyc} />
        <Check label="Check my credit history with a credit bureau (one report)" value={bureau} onChange={setBureau} />
        {(needsAa || p.incomeConsidered) && <Check label="Check my income from 12 months of my bank account" value={aa} onChange={setAa} />}
        <Choice label="Language" value={lang} options={[['en', 'English'], ['hi', 'Hindi'], ['mr', 'Marathi'], ['ta', 'Tamil'], ['ml', 'Malayalam'], ['kn', 'Kannada'], ['te', 'Telugu'], ['bn', 'Bengali'], ['gu', 'Gujarati']]} onChange={setLang} />
      </> : <Muted>Show them the {p.role === 'GUARANTOR' ? 'guarantee deed' : 'KFS and loan agreement'} first; they confirm with the OTP sent to their own phone.</Muted>}
      <Btn title={sent ? 'Send again' : 'Send OTP to their mobile'} testID="party-otp-send" onPress={() => r.run(async () => setSent(await api.post(`/api/parties/${p.id}/otp`)))} />
      {sent ? <Muted>Sent to {sent.mobile}{sent.demo ? ' (demo OTP 123456 until SMS is integrated)' : ''}</Muted> : null}
      <Input label="OTP they read out" keyboardType="number-pad" maxLength={6} value={otp} onChangeText={setOtp} testID="party-otp" />
      <Btn title={kind === 'consent' ? 'Record consent' : 'Record signature'} kind="primary" disabled={!ready} busy={r.busy} testID="party-otp-confirm"
        onPress={() => r.run(() => kind === 'consent' ? api.post(`/api/parties/${p.id}/consent`, { otp, consentKyc: kyc, consentBureau: bureau, consentAa: aa, language: lang })
          : api.post(`/api/parties/${p.id}/sign`, { otp })).then(x => { if (x) onDone(kind === 'consent' ? `${p.name}: consent recorded` : `${p.name}: signed`) })} />
    </Sheet>
  )
}

function InviteSheet({ p, onClose }: { p: PartyRow; onClose: () => void }) {
  const [res, setRes] = useState<{ path: string; expiresAt: string; mobile: string; sms: string }>()
  const r = useRun()
  const link = res ? (isWeb ? window.location.origin : url('')) + res.path : ''
  return (
    <Sheet visible title={`Invite ${p.name}`} onClose={onClose}>
      <Feedback error={r.error} ok={r.ok} />
      {!res ? <>
        <Muted>A link {p.name} opens on their own phone: they verify with an OTP, read what they agree to, consent and upload their PAN and Aadhaar. Valid 7 days.</Muted>
        <Btn title="Create link" kind="primary" busy={r.busy} onPress={() => r.run(async () => setRes(await api.post(`/api/parties/${p.id}/invite`)))} />
      </> : <>
        <Text selectable style={{ color: C.ink, marginBottom: 8 }} testID="invite-link">{link}</Text>
        <Muted>Expires {when(res.expiresAt)} · for {res.mobile}</Muted>
        <Btn title="Copy link" onPress={() => Clipboard.setStringAsync(link).then(() => r.setOk('Copied'))} />
        {!isWeb && <Btn title="Share (SMS, WhatsApp…)" kind="primary" onPress={() => Share.share({ message: res.sms.replace('<link>', link) })} />}
      </>}
    </Sheet>
  )
}

function DocsSheet({ p, types, docs, onClose, onDone }: { p: PartyRow; types: string[]; docs: { id: number; docType: string; status: string; masked: boolean }[]; onClose: () => void; onDone: (m: string) => void }) {
  const { syncNow, online } = useSession()
  const [type, setType] = useState(types[0] ?? 'PAN')
  const [file, setFile] = useState<LocalFile | null>(null)
  const r = useRun()
  async function go() {
    if (!file) return
    await r.run(async () => {
      await add({ id: newId(), kind: 'upload', label: `${label(type)} of ${p.name}`, method: 'POST', path: `/api/parties/${p.id}/documents`, fields: [{ name: 'file', file }, { name: 'docType', value: type }] })
      const s = await syncNow()
      if (s?.failed.length) throw new Error('The server refused the document; see the outbox')
      onDone(s && s.sent.length ? `${label(type)} of ${p.name} uploaded and read` : 'Saved on this phone; it will upload when you are online')
    })
  }
  return (
    <Sheet visible title={`${p.name}: documents`} onClose={onClose}>
      <Feedback error={r.error} />
      {docs.map(x => <Row key={x.id} k={label(x.docType) + (x.masked ? ' (masked)' : '')} v={<Pill s={x.status === 'VERIFIED' ? 'PASS' : x.status === 'REJECTED' ? 'FAIL' : 'REFER'} text={label(x.status)} />} />)}
      <Choice label="Type" value={type} options={types.map(t => [t, label(t)])} onChange={setType} testID="party-doc-type" />
      <Capture name={`${p.partyNo}-${type}`.toLowerCase()} onFile={setFile} testID="party-doc" />
      {file && <Picked f={file} />}
      <Btn title={online ? 'Upload and read' : 'Save to send later'} kind="primary" disabled={!file} busy={r.busy} testID="party-doc-upload" onPress={go} />
      <Muted>KYC compares the PAN and Aadhaar read here with the details given. Aadhaar is kept only masked.</Muted>
    </Sheet>
  )
}

function NoteSheet({ title, hint, path, onClose, onDone }: { title: string; hint: string; path: string; onClose: () => void; onDone: () => void }) {
  const [note, setNote] = useState('')
  const r = useRun()
  return (
    <Sheet visible title={title} onClose={onClose}>
      <Feedback error={r.error} />
      <Input label={hint} multiline value={note} onChangeText={setNote} />
      <Btn title="Confirm" kind="primary" busy={r.busy} onPress={() => r.run(() => api.post(path, { note })).then(x => { if (x) onDone() })} />
    </Sheet>
  )
}

function KycSheet({ p, onClose, onDone }: { p: PartyRow; onClose: () => void; onDone: () => void }) {
  const [verified, setVerified] = useState('true')
  const [note, setNote] = useState('')
  const r = useRun()
  return (
    <Sheet visible title={`${p.name}: resolve KYC`} onClose={onClose}>
      <Feedback error={r.error} />
      {p.kycNote ? <Muted>{p.kycNote}</Muted> : null}
      <Choice label="Outcome" value={verified} options={[['true', 'Verified (originals seen)'], ['false', 'Failed']]} onChange={setVerified} />
      <Input label="What you checked (at least 10 characters)" multiline value={note} onChangeText={setNote} />
      <Btn title="Save" kind="primary" busy={r.busy} onPress={() => r.run(() => api.post(`/api/parties/${p.id}/kyc/resolve`, { verified: verified === 'true', note })).then(x => { if (x) onDone() })} />
    </Sheet>
  )
}

function IncomeSheet({ p, onClose, onDone }: { p: PartyRow; onClose: () => void; onDone: () => void }) {
  const [income, setIncome] = useState(p.monthlyIncome == null ? '' : String(p.monthlyIncome))
  const [emi, setEmi] = useState(p.existingEmi == null ? '' : String(p.existingEmi))
  const [counted, setCounted] = useState(p.incomeConsidered)
  const [note, setNote] = useState('')
  const r = useRun()
  return (
    <Sheet visible title={`${p.name}: income`} onClose={onClose}>
      <Feedback error={r.error} />
      <Input label="Monthly income (₹)" keyboardType="numeric" value={income} onChangeText={setIncome} />
      <Input label="Existing EMIs (₹/month)" keyboardType="numeric" value={emi} onChangeText={setEmi} />
      <Check label="Count this income for affordability" value={counted} onChange={setCounted} />
      <Input label="Checked against (salary slips, ITR, bank statement…)" multiline value={note} onChangeText={setNote} />
      <Btn title="Save" kind="primary" busy={r.busy} onPress={() => r.run(() => api.post(`/api/parties/${p.id}/income`, {
        monthlyIncome: income === '' ? null : Number(income), existingEmi: emi === '' ? null : Number(emi), incomeConsidered: counted, note })).then(x => { if (x) onDone() })} />
    </Sheet>
  )
}

function RequestSheet({ appId, rules, onClose, onDone }: { appId: number; rules: PartyRules; onClose: () => void; onDone: () => void }) {
  const roles = (['CO_APPLICANT', 'GUARANTOR'] as const).filter(x => (x === 'GUARANTOR' ? rules.guarantor : rules.coApplicant).mode !== 'NOT_ALLOWED')
  const [role, setRole] = useState<string>(roles[0] ?? 'GUARANTOR')
  const [reason, setReason] = useState('')
  const r = useRun()
  return (
    <Sheet visible title="Ask for a co-applicant or guarantor" onClose={onClose}>
      <Feedback error={r.error} />
      <Choice label="Ask for" value={role} options={roles.map(x => [x, roleLabel(x)])} onChange={setRole} />
      <Input label="Why (at least 15 characters)" multiline value={reason} onChangeText={setReason} />
      <Btn title="Send to sales" kind="primary" busy={r.busy} onPress={() => r.run(() => api.post(`/api/applications/${appId}/party-requests`, { role, reason })).then(x => { if (x) onDone() })} />
      <Muted>Sanction waits until a party is added and verified, or a credit manager waives the request.</Muted>
    </Sheet>
  )
}
