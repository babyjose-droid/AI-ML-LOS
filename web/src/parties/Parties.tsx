import { useState, type ReactNode } from 'react'
import { api, getToken } from '../api'
import { useAuth } from '../auth'
import { ErrorBox, Modal, Table, useLoad } from '../components/ui'
import { inr, label, when } from '../format'

// Co-applicants and guarantors: product rules editor, the application's Parties tab, and the rows on the new-application form.

export interface Slot { mode: 'NOT_ALLOWED' | 'OPTIONAL' | 'MANDATORY'; max: number; requiredWhen: string }
export interface PartyRules {
  coApplicant: Slot; guarantor: Slot; coOwnersMustJoin: boolean
  incomeClubbing: { allowed: boolean; maxEarners: number; relationships: string[]; verify?: boolean }
  relationships: string[]; minBureauScore: number; minAge: number; maxAgeAtMaturity: number; note: string; entityGuarantors?: boolean
}
export const ENTITY_TYPES = ['COMPANY', 'LLP', 'PARTNERSHIP', 'PROPRIETORSHIP', 'TRUST', 'HUF']
export const ENTITY_RELATIONSHIPS = ['GROUP_COMPANY', 'EMPLOYER', 'BUSINESS_ASSOCIATE', 'OTHER']
export const RELATIONSHIPS = ['SPOUSE', 'PARENT', 'CHILD', 'SIBLING', 'BUSINESS_PARTNER', 'DIRECTOR', 'RELATIVE', 'FRIEND', 'OTHER']
export const DEFAULT_RULES: PartyRules = {
  coApplicant: { mode: 'OPTIONAL', max: 2, requiredWhen: '' }, guarantor: { mode: 'OPTIONAL', max: 2, requiredWhen: '' }, coOwnersMustJoin: false,
  incomeClubbing: { allowed: false, maxEarners: 1, relationships: [], verify: false }, relationships: RELATIONSHIPS, minBureauScore: 0, minAge: 18, maxAgeAtMaturity: 70, note: '',
  entityGuarantors: false,
}
const roleLabel = (r: string) => r === 'GUARANTOR' ? 'Guarantor' : 'Co-applicant'
const relLabel = (r: string) => label(r)
const CHANNEL: Record<string, string> = { BRANCH_OTP: 'In branch (OTP)', INVITE_LINK: 'Invite link (OTP)' }
const channel = (c?: string) => c ? CHANNEL[c] ?? label(c) : ''

export function parseRules(json: unknown): PartyRules {
  if (!json) return DEFAULT_RULES
  try {
    const r = (typeof json === 'string' ? JSON.parse(json) : json) as Partial<PartyRules>
    return { ...DEFAULT_RULES, ...r, coApplicant: { ...DEFAULT_RULES.coApplicant, ...r.coApplicant }, guarantor: { ...DEFAULT_RULES.guarantor, ...r.guarantor },
      incomeClubbing: { ...DEFAULT_RULES.incomeClubbing, ...r.incomeClubbing }, relationships: r.relationships?.length ? r.relationships : RELATIONSHIPS }
  } catch { return DEFAULT_RULES }
}

/** One line per role, in plain words. */
export function describeRules(r: PartyRules): string[] {
  const slot = (s: Slot, who: string) => s.mode === 'NOT_ALLOWED' ? `${who}: not allowed`
    : `${who}: ${s.mode === 'MANDATORY' ? 'required' : 'optional'}, up to ${s.max}${s.requiredWhen ? `; required before sanction when ${s.requiredWhen}` : ''}`
  const out = [slot(r.coApplicant, 'Co-applicants'), slot(r.guarantor, 'Guarantors')]
  if (r.incomeClubbing.allowed) out.push(`Income counted: ${r.incomeClubbing.relationships.length ? r.incomeClubbing.relationships.map(relLabel).join(', ').toLowerCase() : 'any co-applicant'}, ${r.incomeClubbing.maxEarners} earners in all${r.incomeClubbing.verify ? '; only after it is checked against their own bank statement or Account Aggregator data' : ''}`)
  else out.push('Co-applicant income is not counted')
  if (r.entityGuarantors) out.push('A company, LLP, firm, trust or HUF may stand guarantor (board resolution or deed needed)')
  if (r.coOwnersMustJoin) out.push('Every co-owner of the collateral must join as a co-applicant')
  out.push(`Each party: age ${r.minAge}+ and ${r.maxAgeAtMaturity} or younger at maturity${r.minBureauScore ? `; bureau score ${r.minBureauScore}+ (below refers the case)` : ''}`)
  if (r.note) out.push(r.note)
  return out
}

// ---------------------------------------------------------------- product rules editor (Products → Co-applicants & guarantors)

export function PartyRulesEditor({ value, editable, onChange }: { value: unknown; editable: boolean; onChange: (r: PartyRules) => void }) {
  const r = parseRules(value)
  const slot = (key: 'coApplicant' | 'guarantor', title: string) => {
    const s = r[key]
    const set = (patch: Partial<Slot>) => onChange({ ...r, [key]: { ...s, ...patch } })
    return (
      <div className="panel" style={{ margin: 0 }} data-party-slot={key}>
        <h4 style={{ marginTop: 0 }}>{title}</h4>
        <div className="form">
          <label className="fld">Allowed<select name={`pr_${key}_mode`} disabled={!editable} value={s.mode}
            onChange={e => set({ mode: e.target.value as Slot['mode'], max: e.target.value === 'NOT_ALLOWED' ? 0 : Math.max(1, s.max), requiredWhen: e.target.value === 'NOT_ALLOWED' ? '' : s.requiredWhen })}>
            <option value="NOT_ALLOWED">Not allowed</option><option value="OPTIONAL">Optional</option><option value="MANDATORY">Mandatory (always)</option></select></label>
          <label className="fld">Maximum<input name={`pr_${key}_max`} type="number" min={0} max={5} disabled={!editable || s.mode === 'NOT_ALLOWED'} value={s.max} onChange={e => set({ max: Number(e.target.value) })} /></label>
          <label className="fld wide">Required before sanction when (optional rule, e.g. amount &gt; 500000 or score &lt; 700)
            <input name={`pr_${key}_when`} disabled={!editable || s.mode === 'NOT_ALLOWED'} value={s.requiredWhen} onChange={e => set({ requiredWhen: e.target.value })} placeholder="amount > 500000" /></label>
        </div>
      </div>
    )
  }
  const toggle = (list: string[], x: string) => list.includes(x) ? list.filter(y => y !== x) : [...list, x]
  return (
    <>
      <div className="grid2">{slot('coApplicant', 'Co-applicants (jointly and severally liable)')}{slot('guarantor', 'Guarantors (pay if the borrower does not)')}</div>
      <div className="form" style={{ marginTop: 12 }}>
        <label className="fld">Minimum bureau score of each party (0 = none)<input name="pr_score" type="number" min={0} max={900} disabled={!editable} value={r.minBureauScore} onChange={e => onChange({ ...r, minBureauScore: Number(e.target.value) })} /></label>
        <label className="fld">Minimum age<input name="pr_minAge" type="number" min={18} disabled={!editable} value={r.minAge} onChange={e => onChange({ ...r, minAge: Number(e.target.value) })} /></label>
        <label className="fld">Maximum age at maturity<input name="pr_maxAge" type="number" max={80} disabled={!editable} value={r.maxAgeAtMaturity} onChange={e => onChange({ ...r, maxAgeAtMaturity: Number(e.target.value) })} /></label>
        <label className="check fld"><span><input type="checkbox" name="pr_entity" disabled={!editable} checked={!!r.entityGuarantors} onChange={e => onChange({ ...r, entityGuarantors: e.target.checked })} /> Companies and firms may stand guarantor</span></label>
        <label className="check fld"><span><input type="checkbox" name="pr_coOwners" disabled={!editable} checked={r.coOwnersMustJoin} onChange={e => onChange({ ...r, coOwnersMustJoin: e.target.checked })} /> Co-owners of the collateral must join as co-applicants</span></label>
      </div>
      <h4>Relationships allowed</h4>
      <div className="row">{RELATIONSHIPS.map(x => <label key={x} className="check"><input type="checkbox" disabled={!editable} checked={r.relationships.includes(x)}
        onChange={() => onChange({ ...r, relationships: toggle(r.relationships, x) })} /> {relLabel(x)}</label>)}</div>
      <h4>Income clubbing</h4>
      <div className="form">
        <label className="check fld"><span><input type="checkbox" name="pr_club" disabled={!editable} checked={r.incomeClubbing.allowed}
          onChange={e => onChange({ ...r, incomeClubbing: { ...r.incomeClubbing, allowed: e.target.checked, maxEarners: e.target.checked ? Math.max(2, r.incomeClubbing.maxEarners) : 1 } })} /> Count co-applicants' income for affordability</span></label>
        {r.incomeClubbing.allowed && <label className="fld">Earners in all, the applicant included<input name="pr_earners" type="number" min={2} max={6} disabled={!editable} value={r.incomeClubbing.maxEarners}
          onChange={e => onChange({ ...r, incomeClubbing: { ...r.incomeClubbing, maxEarners: Number(e.target.value) } })} /></label>}
        {r.incomeClubbing.allowed && <label className="check fld"><span><input type="checkbox" name="pr_verify" disabled={!editable} checked={!!r.incomeClubbing.verify}
          onChange={e => onChange({ ...r, incomeClubbing: { ...r.incomeClubbing, verify: e.target.checked } })} /> Check their income against their own bank statement or Account Aggregator data (the lower of declared and found counts)</span></label>}
      </div>
      {r.incomeClubbing.allowed && <div className="row" style={{ marginTop: 8 }}>
        <span className="small muted">Whose income counts (none ticked = any co-applicant):</span>
        {RELATIONSHIPS.map(x => <label key={x} className="check"><input type="checkbox" disabled={!editable} checked={r.incomeClubbing.relationships.includes(x)}
          onChange={() => onChange({ ...r, incomeClubbing: { ...r.incomeClubbing, relationships: toggle(r.incomeClubbing.relationships, x) } })} /> {relLabel(x)}</label>)}
      </div>}
      <label className="fld" style={{ marginTop: 12 }}>Note shown to staff<input name="pr_note" disabled={!editable} value={r.note} onChange={e => onChange({ ...r, note: e.target.value })} /></label>
      <p className="small muted">Mandatory parties block submission. A "required when" rule must be met before sanction, so credit can bring the party in after appraisal. Rules use the workflow fields (amount, tenure, income, score, band…). Applications keep the product version they started on.</p>
    </>
  )
}

// ---------------------------------------------------------------- the application's Parties tab

interface PartyRow {
  id: number; partyNo: string; role: string; relationship: string; name: string; pan: string; email?: string; dob: string; city?: string
  occupation?: string; monthlyIncome?: number; existingEmi?: number; incomeConsidered: boolean; coOwner: boolean; status: string; addedStage: string
  consentAt?: string; consentChannel?: string; consentLanguage?: string; invitedAt?: string; kycState: string; kycScore?: number; kycNote?: string
  kycReviewedBy?: string; bureauState: string; bureauScore?: number; signedAt?: string; signedChannel?: string; signedDocument?: string
  removedReason?: string; addedBy: string; addedAt: string
  partyType?: string; signatoryName?: string; signatoryDesignation?: string; registrationNo?: string; gstin?: string
  consentAa?: boolean; verifiedIncome?: number; incomeSource?: string; incomeCheckedAt?: string
}
interface PartyDoc { id: number; docType: string; fileName: string; status: string; remarks?: string; masked: boolean; readConfidence?: number; uploadedBy: string; uploadedAt: string }
interface Issue { code: string; gate: string; message: string; role: string }
interface Requirement { id: number; role: string; reason: string; status: string; requestedBy: string; requestedAt: string; closedBy?: string; closeNote?: string; metByPartyId?: number }
interface PartiesView {
  rules: PartyRules; parties: { party: PartyRow; mobile: string; documents: PartyDoc[]; kycChecks: { check: string; score: number; expected?: string; found?: string }[]
    bureau?: Record<string, unknown>; inviteActive: boolean; incomeCounted: boolean; issues: string[]; vkyc?: string | null }[]
  requirements: Requirement[]; issues: Issue[]; coOwners: string[]
  joint?: { coApplicants: number; guarantors: number; earnersCounted: number; clubbedIncome: number; clubbedEmi: number; policyFails: string[] } | null
  canChange: boolean; sanctioned: boolean; documentTypes: string[]; documentsRead: boolean; relationships: string[]; vkycParties?: string
  entityTypes?: string[]; entityRelationships?: string[]
}
const GATE: Record<string, string> = { SUBMIT: 'before submission', DECISION: 'before the decision', SANCTION: 'before sanction', DISBURSE: 'before disbursement' }

async function openFile(url: string) {
  const t = getToken()
  const r = await fetch(url, { headers: t ? { Authorization: 'Bearer ' + t } : {} })
  if (r.ok) window.open(URL.createObjectURL(await r.blob()), '_blank')
}

type Dlg = { kind: 'add' } | { kind: 'consent' | 'sign' | 'docs' | 'remove' | 'kyc' | 'income' | 'invite'; p: PartyRow } | { kind: 'request' } | { kind: 'waive'; q: Requirement }

export function PartiesTab({ id, rev, onChange }: { id: string; rev: number; onChange: () => void }) {
  const { user } = useAuth()
  const [r2, setR2] = useState(0)
  const v = useLoad(() => api.get<PartiesView>(`/api/applications/${id}/parties`), [id, rev, r2])
  const [dlg, setDlg] = useState<Dlg | null>(null)
  const [log, setLog] = useState<string[]>()
  const [error, setError] = useState<unknown>()
  const role = user?.role ?? ''
  const front = ['SALES', 'OPERATIONS', 'ADMIN', 'CREDIT_OFFICER', 'CREDIT_MANAGER'].includes(role)
  const canAdd = ['SALES', 'OPERATIONS', 'ADMIN', 'CREDIT_OFFICER'].includes(role)
  const credit = ['CREDIT_OFFICER', 'CREDIT_MANAGER', 'CRO', 'ADMIN'].includes(role)
  const done = () => { setDlg(null); setR2(x => x + 1); onChange() }
  async function checks() {
    setError(undefined)
    try { setLog((await api.post<{ log: string[] }>(`/api/applications/${id}/parties/checks`)).log); setR2(x => x + 1); onChange() } catch (e) { setError(e) }
  }
  if (!v.data) return <ErrorBox error={v.error} />
  const d = v.data
  const open = d.requirements.filter(q => q.status === 'OPEN')
  return (
    <div data-testid="parties">
      <ErrorBox error={error} />
      <div className="grid2">
        <div className="panel">
          <h3>Product rules</h3>
          <ul className="small" style={{ margin: 0, paddingLeft: 18 }}>{describeRules(d.rules).map(x => <li key={x}>{x}</li>)}</ul>
        </div>
        <div className="panel">
          <h3>What is still needed</h3>
          {d.issues.length === 0 ? <p className="small">Nothing: every party rule is met.</p>
            : <ul className="small" style={{ margin: 0, paddingLeft: 18 }} data-testid="party-issues">{d.issues.map((i, k) => <li key={k}><span className="pill warn">{GATE[i.gate] ?? i.gate}</span> {i.message}</li>)}</ul>}
          {d.coOwners.length > 0 && <p className="small" style={{ marginBottom: 0 }}>Owners on the collateral: {d.coOwners.join(', ')}.</p>}
          {d.joint && <p className="small" style={{ marginBottom: 0 }}>
            Decision input: {d.joint.coApplicants} co-applicant(s), {d.joint.guarantors} guarantor(s)
            {d.joint.clubbedIncome > 0 && <>; {inr(d.joint.clubbedIncome)}/month income counted with {inr(d.joint.clubbedEmi)}/month of their repayments</>}
            {d.joint.policyFails.length > 0 && <>; <b>refers:</b> {d.joint.policyFails.join('; ')}</>}.
          </p>}
        </div>
      </div>

      {(open.length > 0 || credit) && <div className="panel">
        <div className="row" style={{ justifyContent: 'space-between' }}>
          <h3 style={{ margin: 0 }}>Requests from credit</h3>
          {credit && d.canChange && <button className="btn sm" onClick={() => setDlg({ kind: 'request' })}>Ask for a co-applicant or guarantor</button>}
        </div>
        <Table head={['Asked for', 'Why', 'By', 'Status', '']} empty="No requests." rows={d.requirements.map(q => [roleLabel(q.role), q.reason, <span className="small">{q.requestedBy} · {when(q.requestedAt)}</span>,
          <span className="small"><span className={'pill ' + (q.status === 'OPEN' ? 'warn' : 'ok')}>{q.status.toLowerCase()}</span>{q.closeNote && <div className="muted">{q.closeNote}</div>}</span>,
          q.status === 'OPEN' && ['CREDIT_MANAGER', 'CRO'].includes(role) ? <button className="btn sm" onClick={() => setDlg({ kind: 'waive', q })}>Waive</button> : ''])} />
      </div>}

      <div className="panel flush">
        <div className="row" style={{ justifyContent: 'space-between', padding: '12px 14px 0' }}>
          <h3 style={{ margin: 0 }}>Co-applicants and guarantors</h3>
          <div className="row">
            {['OPERATIONS', 'ADMIN', 'CREDIT_OFFICER'].includes(role) && d.parties.some(x => x.party.consentAt && x.party.status !== 'REMOVED') && <button className="btn sm" onClick={checks}>Run KYC and bureau</button>}
            {canAdd && d.canChange && <button className="btn sm primary" data-party="add" onClick={() => setDlg({ kind: 'add' })}>Add co-applicant or guarantor</button>}
          </div>
        </div>
        {log && <ul className="small" style={{ margin: '8px 14px' }}>{log.length ? log.map((x, i) => <li key={i}>{x}</li>) : <li>Nothing to run: each party must consent first.</li>}</ul>}
        <Table head={['Party', 'Contact', 'Consent', 'KYC', 'Bureau', 'Income', 'Signed', '']} empty="No co-applicants or guarantors on this application."
          rows={d.parties.map(x => {
            const p = x.party
            const removed = p.status === 'REMOVED'
            return [
              <span data-party-row={p.partyNo}><b>{p.name}</b> <span className="muted small">{p.partyNo}</span>
                <div className="small">{roleLabel(p.role)} · {relLabel(p.relationship)}{p.coOwner ? ' · co-owner' : ''}</div>
                {p.partyType && p.partyType !== 'INDIVIDUAL' && <div className="small" data-party-entity={p.partyNo}>{label(p.partyType)} · signs: {p.signatoryName}{p.signatoryDesignation ? `, ${p.signatoryDesignation}` : ''}</div>}
                {removed && <div className="small muted">Removed: {p.removedReason}</div>}
                {x.issues.length > 0 && !removed && <div className="small warn-text">{x.issues.join('; ')}</div>}</span>,
              <span className="small mono">{p.pan}<div>{x.mobile}</div></span>,
              <span className="small">{p.consentAt ? <><span className="pill ok">given</span><div className="muted">{channel(p.consentChannel)} · {when(p.consentAt)}</div></>
                : <span className="pill muted">{x.inviteActive ? 'invited' : 'not yet'}</span>}</span>,
              <span className="small"><span className={'pill ' + (p.kycState === 'VERIFIED' ? 'ok' : p.kycState === 'FAILED' ? 'bad' : p.kycState === 'REVIEW' ? 'warn' : 'muted')}>{label(p.kycState)}</span>
                {p.kycNote && <div className="muted">{p.kycNote}</div>}
                {x.vkyc ? <div className="muted">Video KYC: {label(x.vkyc).toLowerCase()}</div> : d.vkycParties === 'ALL' ? <div className="muted">Video KYC needed (Video KYC tab)</div> : null}</span>,
              <span className="small">{p.bureauState === 'FETCHED' ? <b>{p.bureauScore}</b> : label(p.bureauState)}</span>,
              <span className="small">{p.monthlyIncome ? inr(p.monthlyIncome) + '/mo' : '—'}{p.incomeConsidered && <div className={x.incomeCounted ? '' : 'muted'}>{x.incomeCounted ? 'counted' : 'not countable'}</div>}
                {p.verifiedIncome != null && <div data-party-income={p.partyNo}>{inr(p.verifiedIncome)}/mo per {p.incomeSource === 'AA' ? 'Account Aggregator' : 'bank statement'}</div>}
                {p.incomeConsidered && p.verifiedIncome == null && d.rules.incomeClubbing.verify && <div className="muted">{p.consentAa ? 'to be checked' : 'needs bank data consent'}</div>}</span>,
              <span className="small">{p.signedAt ? <><span className="pill ok">{label(p.signedDocument ?? 'signed')}</span><div className="muted">{channel(p.signedChannel)} · {when(p.signedAt)}</div></> : '—'}</span>,
              removed ? '' : <span className="row">
                {front && !p.consentAt && <button className="btn sm" onClick={() => setDlg({ kind: 'invite', p })}>Invite link</button>}
                {front && (!p.consentAt || p.incomeConsidered && !p.consentAa && d.rules.incomeClubbing.verify) && <button className="btn sm" onClick={() => setDlg({ kind: 'consent', p })}>Consent by OTP</button>}
                {front && <button className="btn sm" onClick={() => setDlg({ kind: 'docs', p })}>Documents ({x.documents.length})</button>}
                {['OPERATIONS', 'ADMIN'].includes(role) && p.kycState === 'REVIEW' && <button className="btn sm" onClick={() => setDlg({ kind: 'kyc', p })}>Resolve KYC</button>}
                {['OPERATIONS', 'ADMIN', 'CREDIT_OFFICER', 'CREDIT_MANAGER', 'CRO'].includes(role) && d.canChange && <button className="btn sm" onClick={() => setDlg({ kind: 'income', p })}>Income</button>}
                {front && d.sanctioned && !p.signedAt && <button className="btn sm primary" onClick={() => setDlg({ kind: 'sign', p })}>Sign by OTP</button>}
                {canAdd && d.canChange && <button className="btn sm danger" onClick={() => setDlg({ kind: 'remove', p })}>Remove</button>}
              </span>,
            ]
          })} />
      </div>

      {dlg?.kind === 'add' && <AddParty appId={id} relationships={d.relationships} entityTypes={d.entityTypes ?? []} rules={d.rules} openRequests={open} onClose={() => setDlg(null)} onDone={done} />}
      {dlg?.kind === 'invite' && <InviteDialog p={dlg.p} onClose={done} />}
      {dlg?.kind === 'consent' && <OtpDialog p={dlg.p} needsAa={dlg.p.incomeConsidered && !!d.rules.incomeClubbing.verify} kind="consent" onClose={() => setDlg(null)} onDone={done} />}
      {dlg?.kind === 'sign' && <OtpDialog p={dlg.p} kind="sign" onClose={() => setDlg(null)} onDone={done} />}
      {dlg?.kind === 'docs' && <DocsDialog p={dlg.p} docs={d.parties.find(x => x.party.id === dlg.p.id)?.documents ?? []} checks={d.parties.find(x => x.party.id === dlg.p.id)?.kycChecks ?? []}
        types={d.documentTypes} read={d.documentsRead} onClose={() => setDlg(null)} onDone={() => setR2(x => x + 1)} />}
      {dlg?.kind === 'remove' && <NoteDialog title={`Remove ${dlg.p.name}`} hint="Why is this person leaving the loan? (at least 10 characters)" url={`/api/parties/${dlg.p.id}/remove`} onClose={() => setDlg(null)} onDone={done} />}
      {dlg?.kind === 'kyc' && <KycDialog p={dlg.p} onClose={() => setDlg(null)} onDone={done} />}
      {dlg?.kind === 'income' && <IncomeDialog p={dlg.p} onClose={() => setDlg(null)} onDone={done} />}
      {dlg?.kind === 'request' && <RequestDialog appId={id} rules={d.rules} onClose={() => setDlg(null)} onDone={done} />}
      {dlg?.kind === 'waive' && <NoteDialog title={`Waive: ${roleLabel(dlg.q.role).toLowerCase()} asked for`} hint={`Asked: ${dlg.q.reason}. Why is it not needed? (at least 15 characters)`} url={`/api/party-requests/${dlg.q.id}/waive`} onClose={() => setDlg(null)} onDone={done} />}
    </div>
  )
}

export interface PartyDraft {
  role: string; relationship: string; name: string; pan: string; mobile: string; email: string; dob: string; gender: string; city: string; pincode: string
  occupation: string; monthlyIncome: string; existingEmi: string; incomeConsidered: boolean; coOwner: boolean; requirementId?: number
  partyType?: string; signatoryName?: string; signatoryDesignation?: string; registrationNo?: string; gstin?: string
}
export const EMPTY_PARTY: PartyDraft = { role: 'CO_APPLICANT', relationship: 'SPOUSE', name: '', pan: '', mobile: '', email: '', dob: '', gender: '', city: '', pincode: '',
  occupation: '', monthlyIncome: '', existingEmi: '', incomeConsidered: false, coOwner: false }

export function partyBody(p: PartyDraft) {
  const entity = !!p.partyType && p.partyType !== 'INDIVIDUAL'
  return { ...p, partyType: p.partyType || 'INDIVIDUAL', signatoryName: entity ? p.signatoryName || null : null, signatoryDesignation: entity ? p.signatoryDesignation || null : null,
    registrationNo: entity ? p.registrationNo || null : null, gstin: entity ? (p.gstin || '').toUpperCase() || null : null, gender: entity ? null : p.gender || null,
    incomeConsidered: entity ? false : p.incomeConsidered, coOwner: entity ? false : p.coOwner, pan: p.pan.toUpperCase(), email: p.email || null, city: p.city || null, pincode: p.pincode || null, occupation: p.occupation || null,
    monthlyIncome: p.monthlyIncome === '' ? null : Number(p.monthlyIncome), existingEmi: p.existingEmi === '' ? null : Number(p.existingEmi), dob: p.dob || null }
}

/** The person's details; used in the Add dialog and on the new-application form. */
export function PartyFields({ p, rules, relationships, entityTypes = [], onChange }: { p: PartyDraft; rules: PartyRules; relationships: string[]; entityTypes?: string[]; onChange: (p: PartyDraft) => void }) {
  const entity = !!p.partyType && p.partyType !== 'INDIVIDUAL'
  const set = (k: keyof PartyDraft) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    onChange({ ...p, [k]: e.target.type === 'checkbox' ? (e.target as HTMLInputElement).checked : e.target.value })
  const clubs = p.role === 'CO_APPLICANT' && rules.incomeClubbing.allowed && (rules.incomeClubbing.relationships.length === 0 || rules.incomeClubbing.relationships.includes(p.relationship))
  const roles: [string, string][] = ([['CO_APPLICANT', 'Co-applicant'], ['GUARANTOR', 'Guarantor']] as [string, string][])
    .filter(([r]) => (r === 'GUARANTOR' ? rules.guarantor : rules.coApplicant).mode !== 'NOT_ALLOWED')
  const txt = (k: keyof PartyDraft, l: string, props: React.InputHTMLAttributes<HTMLInputElement> = {}) =>
    <label className="fld">{l}<input name={'party_' + k} value={String(p[k] ?? '')} onChange={set(k)} {...props} /></label>
  return (
    <div className="form">
      <label className="fld">Role<select name="party_role" value={p.role} onChange={e => onChange({ ...p, role: e.target.value, partyType: e.target.value === 'GUARANTOR' ? p.partyType : 'INDIVIDUAL', relationship: e.target.value === 'GUARANTOR' ? p.relationship : relationships[0] ?? 'SPOUSE' })}>{roles.map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select></label>
      {p.role === 'GUARANTOR' && entityTypes.length > 0 && <label className="fld">Guarantor is<select name="party_partyType" value={p.partyType || 'INDIVIDUAL'}
        onChange={e => onChange({ ...p, partyType: e.target.value, relationship: e.target.value === 'INDIVIDUAL' ? relationships[0] ?? 'SPOUSE' : ENTITY_RELATIONSHIPS[0] })}>
        <option value="INDIVIDUAL">A person</option>{entityTypes.map(t => <option key={t} value={t}>{label(t)}</option>)}</select></label>}
      <label className="fld">Relationship to the applicant<select name="party_relationship" value={p.relationship} onChange={set('relationship')}>
        {(entity ? ENTITY_RELATIONSHIPS : relationships).map(r => <option key={r} value={r}>{relLabel(r)}</option>)}</select></label>
      {txt('name', entity ? 'Registered name (as on PAN)' : 'Full name (as on PAN)')}
      {txt('pan', entity ? 'PAN of the ' + label(p.partyType ?? '').toLowerCase() : 'PAN', { placeholder: entity ? 'ABCCK1234L' : 'ABCPK1234L', maxLength: 10 })}
      {txt('mobile', entity ? "Signatory's mobile (OTPs go here)" : 'Their own mobile (OTPs go here)', { inputMode: 'numeric', maxLength: 10 })}
      {txt('dob', entity ? 'Date of incorporation or registration' : 'Date of birth', { type: 'date' })}
      {entity && <>
        {txt('signatoryName', 'Authorised signatory (named in the board resolution or deed)')}
        {txt('signatoryDesignation', 'Signatory designation (e.g. Director, Partner)')}
        {txt('registrationNo', 'CIN, LLPIN or registration number')}
        {txt('gstin', 'GSTIN (optional)', { maxLength: 15 })}
      </>}
      {!entity && <label className="fld">Gender<select name="party_gender" value={p.gender} onChange={set('gender')}><option value="">Prefer not to say</option><option value="FEMALE">Female</option><option value="MALE">Male</option><option value="OTHER">Other</option></select></label>}
      {txt('email', 'Email', { type: 'email' })}
      {txt('city', 'City')}
      {txt('pincode', 'PIN code', { inputMode: 'numeric', maxLength: 6 })}
      {!entity && txt('occupation', 'Occupation or business')}
      {txt('monthlyIncome', entity ? 'Monthly turnover or profit (₹, for the record)' : 'Monthly income (₹)', { type: 'number', min: 0 })}
      {txt('existingEmi', 'Their existing EMIs (₹/month)', { type: 'number', min: 0 })}
      {!entity && <label className="check fld"><span><input type="checkbox" name="party_incomeConsidered" disabled={!clubs} checked={p.incomeConsidered && clubs} onChange={set('incomeConsidered')} /> Count this income for affordability
        {!clubs && <span className="muted"> (not allowed for this {p.role === 'GUARANTOR' ? 'role' : 'relationship'})</span>}
        {clubs && rules.incomeClubbing.verify && <span className="muted"> (checked against their own bank data before it counts)</span>}</span></label>}
      {p.role === 'CO_APPLICANT' && <label className="check fld"><span><input type="checkbox" name="party_coOwner" checked={p.coOwner} onChange={set('coOwner')} /> Co-owns the collateral</span></label>}
    </div>
  )
}

function AddParty({ appId, relationships, entityTypes, rules, openRequests, onClose, onDone }: { appId: string; relationships: string[]; entityTypes: string[]; rules: PartyRules; openRequests: Requirement[]; onClose: () => void; onDone: () => void }) {
  const firstRole = rules.coApplicant.mode !== 'NOT_ALLOWED' ? 'CO_APPLICANT' : 'GUARANTOR'
  const [p, setP] = useState<PartyDraft>({ ...EMPTY_PARTY, role: openRequests[0]?.role ?? firstRole, relationship: relationships[0] ?? 'SPOUSE', requirementId: openRequests[0]?.id })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>()
  async function save() {
    setBusy(true); setError(undefined)
    try { await api.post(`/api/applications/${appId}/parties`, partyBody(p)); onDone() } catch (e) { setError(e) } finally { setBusy(false) }
  }
  return (
    <Modal title="Add co-applicant or guarantor" onClose={onClose}>
      <ErrorBox error={error} />
      {openRequests.length > 0 && <label className="fld" style={{ marginBottom: 12 }}>Meets the request from credit
        <select value={p.requirementId ?? ''} onChange={e => { const q = openRequests.find(x => String(x.id) === e.target.value); setP({ ...p, requirementId: q?.id, role: q?.role ?? p.role }) }}>
          <option value="">—</option>{openRequests.map(q => <option key={q.id} value={q.id}>{roleLabel(q.role)}: {q.reason}</option>)}</select></label>}
      <PartyFields p={p} rules={rules} relationships={relationships} entityTypes={entityTypes} onChange={setP} />
      <p className="small muted">They give their own consent by OTP on this mobile (in the branch or through an invite link), are KYC- and bureau-checked on their own, and sign before disbursement.</p>
      <button className="btn primary" disabled={busy} onClick={save}>{busy ? 'Saving…' : 'Add'}</button>
    </Modal>
  )
}

function InviteDialog({ p, onClose }: { p: PartyRow; onClose: () => void }) {
  const [res, setRes] = useState<{ path: string; expiresAt: string; mobile: string; sms: string }>()
  const [error, setError] = useState<unknown>()
  const [copied, setCopied] = useState(false)
  const link = res ? window.location.origin + res.path : ''
  return (
    <Modal title={`Invite ${p.name}`} onClose={onClose}>
      <ErrorBox error={error} />
      {!res ? <>
        <p className="small">Creates a link {p.name} opens on their own phone ({p.role === 'GUARANTOR' ? 'guarantor' : 'co-applicant'}): they verify with an OTP, read what they are agreeing to, give consent and upload their PAN and Aadhaar. A new link replaces any earlier one. Valid 7 days.</p>
        <button className="btn primary" onClick={() => api.post<typeof res>(`/api/parties/${p.id}/invite`).then(setRes).catch(setError)}>Create link</button>
      </> : <>
        <label className="fld">Link<input readOnly value={link} data-testid="invite-link" onFocus={e => e.target.select()} /></label>
        <div className="row" style={{ marginTop: 8 }}>
          <button className="btn" onClick={() => { navigator.clipboard?.writeText(link).then(() => setCopied(true)).catch(() => undefined) }}>{copied ? 'Copied' : 'Copy link'}</button>
          <span className="small muted">Expires {when(res.expiresAt)}</span>
        </div>
        <p className="small">SMS to {res.mobile} (sent automatically once SMS is integrated; until then share it yourself):<br /><i>{res.sms.replace('<link>', link)}</i></p>
      </>}
    </Modal>
  )
}

function OtpDialog({ p, kind, needsAa, onClose, onDone }: { p: PartyRow; kind: 'consent' | 'sign'; needsAa?: boolean; onClose: () => void; onDone: () => void }) {
  const [aa, setAa] = useState(false)
  const [sent, setSent] = useState<{ mobile: string; demo: boolean }>()
  const [otp, setOtp] = useState('')
  const [kyc, setKyc] = useState(false)
  const [bureau, setBureau] = useState(false)
  const [lang, setLang] = useState('en')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>()
  async function send() { setError(undefined); try { setSent(await api.post(`/api/parties/${p.id}/otp`)) } catch (e) { setError(e) } }
  async function confirm() {
    setBusy(true); setError(undefined)
    try {
      if (kind === 'consent') await api.post(`/api/parties/${p.id}/consent`, { otp, consentKyc: kyc, consentBureau: bureau, consentAa: aa, language: lang })
      else await api.post(`/api/parties/${p.id}/sign`, { otp })
      onDone()
    } catch (e) { setError(e) } finally { setBusy(false) }
  }
  return (
    <Modal title={kind === 'consent' ? `${p.name}: consent in the branch` : `${p.name}: sign the ${p.role === 'GUARANTOR' ? 'guarantee' : 'loan agreement and KFS'}`} onClose={onClose}>
      <ErrorBox error={error} />
      {kind === 'consent' ? <>
        <p className="small">Read this to {p.name} in their language. As {p.role === 'GUARANTOR' ? 'guarantor they agree to repay if the borrower does not' : 'co-applicant they are jointly and severally liable for the whole loan'}.</p>
        <label className="check"><input type="checkbox" checked={kyc} onChange={e => setKyc(e.target.checked)} /> Verify my identity (PAN and the identity documents I give)</label>
        <label className="check"><input type="checkbox" checked={bureau} onChange={e => setBureau(e.target.checked)} /> Check my credit history with a credit bureau (one report)</label>
        {(needsAa || p.incomeConsidered) && <label className="check"><input type="checkbox" name="party_consentAa" checked={aa} onChange={e => setAa(e.target.checked)} /> Check my income from 12 months of my bank account (Account Aggregator, or the statement I upload){needsAa ? ' (needed: my income is counted)' : ''}</label>}
        <label className="fld" style={{ maxWidth: 240, marginTop: 8 }}>Language the consent was given in<select value={lang} onChange={e => setLang(e.target.value)}>
          {[['en', 'English'], ['hi', 'Hindi'], ['mr', 'Marathi'], ['ta', 'Tamil'], ['ml', 'Malayalam'], ['kn', 'Kannada'], ['te', 'Telugu'], ['bn', 'Bengali'], ['gu', 'Gujarati']].map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select></label>
      </> : <p className="small">{p.name} confirms with the OTP sent to their own phone. Show them the {p.role === 'GUARANTOR' ? 'guarantee deed' : 'KFS and loan agreement'} first.</p>}
      <div className="row" style={{ marginTop: 10 }}>
        <button className="btn" onClick={send}>{sent ? 'Send again' : 'Send OTP to their mobile'}</button>
        {sent && <span className="small muted">Sent to {sent.mobile}{sent.demo ? ' (demo OTP 123456 until SMS is integrated)' : ''}</span>}
      </div>
      <label className="fld" style={{ maxWidth: 200, marginTop: 8 }}>OTP they read out<input name="party_otp" inputMode="numeric" maxLength={6} value={otp} onChange={e => setOtp(e.target.value)} /></label>
      <button className="btn primary" style={{ marginTop: 10 }} disabled={busy || !sent || otp.length < 4 || (kind === 'consent' && (!kyc || !bureau || (!!needsAa && !aa)))} onClick={confirm}>
        {kind === 'consent' ? 'Record consent' : 'Record signature'}</button>
    </Modal>
  )
}

function DocsDialog({ p, docs, checks, types, read, onClose, onDone }: { p: PartyRow; docs: PartyDoc[]; checks: { check: string; score: number; expected?: string; found?: string }[]; types: string[]; read: boolean; onClose: () => void; onDone: () => void }) {
  const [type, setType] = useState('PAN')
  const [file, setFile] = useState<File>()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>()
  async function send() {
    if (!file) return
    setBusy(true); setError(undefined)
    try { const f = new FormData(); f.append('file', file); f.append('docType', type); await api.upload(`/api/parties/${p.id}/documents`, f); setFile(undefined); onDone() }
    catch (e) { setError(e) } finally { setBusy(false) }
  }
  return (
    <Modal title={`${p.name}: documents`} onClose={onClose}>
      <ErrorBox error={error} />
      <Table head={['Document', 'Status', 'Uploaded', '']} empty="No documents yet." rows={docs.map(x => [label(x.docType) + (x.masked ? ' (masked)' : ''),
        <span className="small"><span className={'pill ' + (x.status === 'VERIFIED' ? 'ok' : x.status === 'REJECTED' ? 'bad' : 'warn')}>{label(x.status)}</span>{x.remarks && <div className="muted">{x.remarks}</div>}</span>,
        <span className="small">{when(x.uploadedAt)} · {x.uploadedBy}</span>, <button className="btn sm" onClick={() => openFile(`/api/party-documents/${x.id}/content`)}>View</button>])} />
      {checks.length > 0 && <><h4>KYC checks</h4><Table head={['Check', 'Match', 'Expected', 'Found']} rows={checks.map(c => [c.check, Math.round(c.score * 100) + '%', c.expected ?? '—', c.found ?? '—'])} /></>}
      <div className="grid2" style={{ marginTop: 12 }}>
        <label className="fld">Type<select value={type} onChange={e => setType(e.target.value)}>{types.map(t => <option key={t} value={t}>{label(t)}</option>)}</select></label>
        <label className="fld">File (PDF, JPG or PNG)<input type="file" accept=".pdf,.jpg,.jpeg,.png" onChange={e => setFile(e.target.files?.[0])} /></label>
      </div>
      <button className="btn primary" style={{ marginTop: 8 }} disabled={!file || busy} onClick={send}>{busy ? 'Reading…' : 'Upload and read'}</button>
      <p className="small muted">{read ? 'KYC compares the PAN and Aadhaar (or voter ID, driving licence, passport) read here with the details given. Aadhaar is kept only masked.' : 'KYC runs through the identity provider; documents are kept for the file.'}</p>
    </Modal>
  )
}

function NoteDialog({ title, hint, url, onClose, onDone }: { title: string; hint: string; url: string; onClose: () => void; onDone: () => void }) {
  const [note, setNote] = useState('')
  const [error, setError] = useState<unknown>()
  return (
    <Modal title={title} onClose={onClose}>
      <ErrorBox error={error} />
      <label className="fld">{hint}<textarea rows={3} value={note} onChange={e => setNote(e.target.value)} /></label>
      <button className="btn primary" style={{ marginTop: 8 }} onClick={() => api.post(url, { note }).then(onDone).catch(setError)}>Confirm</button>
    </Modal>
  )
}

function KycDialog({ p, onClose, onDone }: { p: PartyRow; onClose: () => void; onDone: () => void }) {
  const [verified, setVerified] = useState('true')
  const [note, setNote] = useState('')
  const [error, setError] = useState<unknown>()
  return (
    <Modal title={`${p.name}: resolve KYC review`} onClose={onClose}>
      <ErrorBox error={error} />
      <p className="small">{p.kycNote}</p>
      <label className="fld">Outcome<select value={verified} onChange={e => setVerified(e.target.value)}><option value="true">Verified (originals seen or video KYC done)</option><option value="false">Failed</option></select></label>
      <label className="fld" style={{ marginTop: 8 }}>What you checked (at least 10 characters)<textarea rows={3} value={note} onChange={e => setNote(e.target.value)} /></label>
      <button className="btn primary" style={{ marginTop: 8 }} onClick={() => api.post(`/api/parties/${p.id}/kyc/resolve`, { verified: verified === 'true', note }).then(onDone).catch(setError)}>Save</button>
    </Modal>
  )
}

function IncomeDialog({ p, onClose, onDone }: { p: PartyRow; onClose: () => void; onDone: () => void }) {
  const [income, setIncome] = useState(p.monthlyIncome == null ? '' : String(p.monthlyIncome))
  const [emi, setEmi] = useState(p.existingEmi == null ? '' : String(p.existingEmi))
  const [counted, setCounted] = useState(p.incomeConsidered)
  const [note, setNote] = useState('')
  const [error, setError] = useState<unknown>()
  return (
    <Modal title={`${p.name}: income`} onClose={onClose}>
      <ErrorBox error={error} />
      <div className="grid2">
        <label className="fld">Monthly income (₹)<input type="number" value={income} onChange={e => setIncome(e.target.value)} /></label>
        <label className="fld">Existing EMIs (₹/month)<input type="number" value={emi} onChange={e => setEmi(e.target.value)} /></label>
      </div>
      <label className="check" style={{ marginTop: 8 }}><input type="checkbox" checked={counted} onChange={e => setCounted(e.target.checked)} /> Count this income for affordability</label>
      <label className="fld" style={{ marginTop: 8 }}>Checked against (salary slips, ITR, bank statement… at least 10 characters)<textarea rows={2} value={note} onChange={e => setNote(e.target.value)} /></label>
      <button className="btn primary" style={{ marginTop: 8 }} onClick={() => api.post(`/api/parties/${p.id}/income`, {
        monthlyIncome: income === '' ? null : Number(income), existingEmi: emi === '' ? null : Number(emi), incomeConsidered: counted, note }).then(onDone).catch(setError)}>Save</button>
      <p className="small muted">The decision engine uses the new figures when the decision is run again.</p>
    </Modal>
  )
}

function RequestDialog({ appId, rules, onClose, onDone }: { appId: string; rules: PartyRules; onClose: () => void; onDone: () => void }) {
  const roles = (['CO_APPLICANT', 'GUARANTOR'] as const).filter(r => (r === 'GUARANTOR' ? rules.guarantor : rules.coApplicant).mode !== 'NOT_ALLOWED')
  const [role, setRole] = useState<string>(roles[0] ?? 'GUARANTOR')
  const [reason, setReason] = useState('')
  const [error, setError] = useState<unknown>()
  return (
    <Modal title="Ask for a co-applicant or guarantor" onClose={onClose}>
      <ErrorBox error={error} />
      <label className="fld">Ask for<select value={role} onChange={e => setRole(e.target.value)}>{roles.map(r => <option key={r} value={r}>{roleLabel(r)}</option>)}</select></label>
      <label className="fld" style={{ marginTop: 8 }}>Why (sales passes this on to the borrower; at least 15 characters)<textarea rows={3} value={reason} onChange={e => setReason(e.target.value)} /></label>
      <button className="btn primary" style={{ marginTop: 8 }} onClick={() => api.post(`/api/applications/${appId}/party-requests`, { role, reason }).then(onDone).catch(setError)}>Send to sales</button>
      <p className="small muted">Sanction waits until a party is added and verified, or a credit manager waives the request with a reason.</p>
    </Modal>
  )
}

// ---------------------------------------------------------------- on the new-application form

export function PartiesOnForm({ productCode, list, onChange }: { productCode: string; list: PartyDraft[]; onChange: (l: PartyDraft[]) => void }) {
  const rules = useLoad(() => api.get<PartyRules>(`/api/products/${productCode}/party-rules`), [productCode])
  const r = rules.data ? parseRules(rules.data) : null
  if (!r) return null
  const none = r.coApplicant.mode === 'NOT_ALLOWED' && r.guarantor.mode === 'NOT_ALLOWED'
  const rels = r.relationships
  return (
    <div className="panel" data-testid="form-parties">
      <div className="row" style={{ justifyContent: 'space-between' }}>
        <h3 style={{ margin: 0 }}>Co-applicants and guarantors</h3>
        {!none && <button type="button" className="btn sm" data-party="form-add" onClick={() => onChange([...list, { ...EMPTY_PARTY, role: r.coApplicant.mode !== 'NOT_ALLOWED' ? 'CO_APPLICANT' : 'GUARANTOR', relationship: rels[0] ?? 'SPOUSE' }])}>Add a person</button>}
      </div>
      <ul className="small muted" style={{ paddingLeft: 18 }}>{describeRules(r).map(x => <li key={x}>{x}</li>)}</ul>
      {list.map((p, i) => <Section key={i} title={`${roleLabel(p.role)} ${i + 1}`} onRemove={() => onChange(list.filter((_, j) => j !== i))}>
        <PartyFields p={p} rules={r} relationships={rels} onChange={x => onChange(list.map((y, j) => j === i ? x : y))} />
      </Section>)}
      {list.length > 0 && <p className="small muted">Each person gets their own consent request after saving (an OTP on their phone, in the branch or by invite link).</p>}
    </div>
  )
}

function Section({ title, children, onRemove }: { title: string; children: ReactNode; onRemove: () => void }) {
  return (
    <div style={{ borderTop: '1px solid var(--line)', paddingTop: 10, marginTop: 10 }}>
      <div className="row" style={{ justifyContent: 'space-between' }}><b>{title}</b><button type="button" className="btn sm" onClick={onRemove}>Remove</button></div>
      {children}
    </div>
  )
}
