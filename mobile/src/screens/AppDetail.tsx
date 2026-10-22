// One application: where it is, what is blocking it, and the actions this officer can take, with the same rules as the web.
import React, { useCallback, useEffect, useState } from 'react'
import { RefreshControl, ScrollView, Text, View } from 'react-native'
import { api, getCached, message } from '../lib/api'
import { useSession } from '../session'
import { Btn, C, Card, Choice, Feedback, Input, ListItem, Muted, Note, Pill, Row, Screen, Sheet, Spinner, inr, label, useNav, useRun, when } from '../ui'
import { OfflineBanner } from './Lists'

export interface AppView {
  application: { id: number; appNo: string; productCode: string; applicantName: string; pan: string; mobile: string; city?: string; segment: string; loanAmount: number; tenureMonths: number
    purpose?: string; businessName?: string; declaredMonthlyIncome: number; rejectionReason?: string; createdBy: string; createdAt: string }
  states: Record<string, string>; actions: string[]; blockers: string[]; missingDocuments: string[]
  product: { code: string; name: string; requiredDocs: string; secured: boolean }
}
export interface Stage { key: string; name: string; role: string; kind: 'STATE' | 'RECORDED'; help: string; dataFields: string[]; status: string; outcome?: string; completedBy?: string; completedAt?: string; dueAt?: string; canComplete: boolean; canReview?: boolean; assignedToMe?: boolean; note?: string; onFailure?: string; data?: Record<string, unknown> }

export const STAGE_FIELD: Record<string, string> = {
  groupName: 'Group name', members: 'Members', sessionsHeld: 'Training sessions held', membersPresent: 'Members present',
  registrationNo: 'Registration no.', valuation: 'Valuation (₹)', netWeightGrams: 'Net weight (g)', purityKarat: 'Purity (karat)',
  value: 'Value (₹)', titleClear: 'Title clear (yes/no)', marketValue: 'Market value (₹)',
}
const NUMERIC = ['members', 'sessionsHeld', 'membersPresent', 'valuation', 'netWeightGrams', 'purityKarat', 'value', 'marketValue']

type Dialog = { kind: string; title: string; fields: ('note' | 'amount' | 'otp' | 'choice' | 'conditions')[]; choices?: [string, string][]; hint?: string; choiceLabel?: string }
const DIALOGS: Record<string, Dialog> = {
  withdraw: { kind: 'withdraw', title: 'Withdraw application', fields: ['note'] },
  resolveKyc: { kind: 'resolveKyc', title: 'Resolve KYC review', fields: ['choice', 'note'], choices: [['true', 'Verified (video KYC done)'], ['false', 'Failed']] },
  fieldVisit: { kind: 'fieldVisit', title: 'Record field visit', fields: ['note'], hint: 'What you saw: business running, stock, premises, neighbour check.' },
  fraudDisposition: { kind: 'fraudDisposition', title: 'Fraud review outcome', fields: ['choice', 'note'], choices: [['true', 'Clear: no fraud'], ['false', 'Confirm fraud and reject']] },
  sanction: { kind: 'sanction', title: 'Sanction', fields: ['amount', 'conditions', 'note'], hint: 'Leave the amount empty to sanction the recommended amount. A reason is needed for a referred case or a higher amount. Conditions: one per line.' },
  sendBack: { kind: 'sendBack', title: 'Send back to credit', fields: ['note'], hint: 'Say what needs another look (at least 10 characters).' },
  decline: { kind: 'decline', title: 'Decline', fields: ['note'] },
  escalate: { kind: 'escalate', title: 'Escalate to next level', fields: ['note'] },
  acceptKfs: { kind: 'acceptKfs', title: 'Borrower accepts the Key Fact Statement', fields: ['otp', 'choice'], choiceLabel: 'Language the KFS was shown in',
    choices: [['', "Borrower's language"], ['en', 'English'], ['hi', 'Hindi'], ['mr', 'Marathi'], ['ta', 'Tamil'], ['ml', 'Malayalam'], ['kn', 'Kannada'], ['te', 'Telugu'], ['bn', 'Bengali'], ['gu', 'Gujarati']],
    hint: 'Show the borrower the KFS first (Sanction & KFS). In this build the OTP is 123456.' },
}
const ACTION_LABEL: Record<string, string> = {
  edit: 'Edit draft', submit: 'Submit', withdraw: 'Withdraw', process: 'Run automated checks', runKyc: 'Run KYC', fetchData: 'Fetch bank, bureau and GST data',
  verifyDocuments: 'Verify documents', runDecision: 'Run decision engine', resolveKyc: 'Resolve KYC review', fieldVisit: 'Record field visit',
  fraudDisposition: 'Fraud review outcome', sanction: 'Sanction', decline: 'Decline', sendBack: 'Send back', escalate: 'Escalate', acceptKfs: 'Borrower accepts KFS',
  disburse: 'Disburse', retryDisbursement: 'Retry disbursement', uploadDocument: 'Upload documents', reviewDocuments: 'Review documents', videoKyc: 'Video KYC',
}
const PRIMARY = ['process', 'sanction', 'disburse', 'submit', 'acceptKfs']
const STATE_ORDER: [string, string][] = [['APP', 'Application'], ['KYC', 'KYC'], ['DATA', 'Data'], ['DOCS', 'Documents'], ['FIELD', 'Field visit'], ['DECISION', 'Decision'], ['FRAUD', 'Fraud'], ['SANCTION', 'Sanction'], ['DISB', 'Disbursement']]

export function useApp(id: number) {
  const [v, setV] = useState<AppView | null>(null)
  const [stages, setStages] = useState<Stage[]>([])
  const [savedAt, setSavedAt] = useState<number>()
  const [error, setError] = useState<string>()
  const [loading, setLoading] = useState(false)
  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [a, s] = await Promise.all([getCached<AppView>('/api/applications/' + id), getCached<{ stages: Stage[] }>(`/api/applications/${id}/stages`).catch(() => ({ data: { stages: [] }, savedAt: undefined }))])
      setV(a.data); setStages(s.data.stages ?? []); setSavedAt(a.savedAt); setError(undefined)
    } catch (e) { setError(message(e)) } finally { setLoading(false) }
  }, [id])
  useEffect(() => { load() }, [load])
  return { v, stages, savedAt, error, loading, load }
}

export function productStep(stages: Stage[]): { key: string; name: string } | null {
  for (const k of ['asset', 'limit', 'plan', 'pledge']) { const s = stages.find(x => x.key === k); if (s) return { key: k, name: s.name } }
  return null
}

export default function AppDetail({ id, action: hinted }: { id: number; action?: string }) {
  const nav = useNav()
  const { user, online } = useSession()
  const { v, stages, savedAt, error, loading, load } = useApp(id)
  const [dialog, setDialog] = useState<Dialog | null>(null)
  const [stage, setStage] = useState<Stage | null>(null)
  const r = useRun()

  if (!v) return <Screen title="Application">{error ? <Note kind="bad">{error}</Note> : <Spinner />}</Screen>
  const a = v.application
  const step = productStep(stages)
  const colTypes = String((v.product as unknown as { collateralTypes?: string }).collateralTypes ?? '').split(',').filter(Boolean)
  const hasCol = stages.some(x => x.key === 'col') || colTypes.length > 0
  const group = stages.some(x => x.key === 'cgt' || x.key === 'grt')
  const staff = !['VALUER', 'LEGAL_REVIEWER'].includes(user.role)
  const field = stages.some(x => x.key === 'field')
  // recorded workflow steps this officer completes (or confirms); the product steps and the gold appraisal have screens of their own
  const recorded = stages.filter(x => x.kind === 'RECORDED' && (x.canComplete || x.canReview) && !['asset', 'limit', 'plan', 'pledge', 'field', 'col', 'gold'].includes(x.key))

  async function act(x: string, body?: Record<string, string>) {
    const base = '/api/applications/' + id
    const done = await r.run(async () => {
      switch (x) {
        case 'submit': return api.post(base + '/submit')
        case 'withdraw': return api.post(base + '/withdraw', body)
        case 'process': {
          const p = await api.post<{ steps: { step: string; outcome: string }[] }>(base + '/process')
          return p.steps.map(s => `${s.step}: ${/^[A-Z_]+$/.test(s.outcome) ? label(s.outcome) : s.outcome}`).join(' · ')
        }
        case 'runKyc': return api.post(base + '/kyc/run')
        case 'fetchData': return api.post(base + '/data/fetch')
        case 'verifyDocuments': return api.post(base + '/documents/verify')
        case 'runDecision': return api.post(base + '/decision/run')
        case 'resolveKyc': return api.post(base + '/kyc/resolve', { verified: body?.choice === 'true', note: body?.note })
        case 'fieldVisit': return api.post(base + '/field-visit', { note: body?.note })
        case 'fraudDisposition': return api.post(base + '/fraud/disposition', { clear: body?.choice === 'true', note: body?.note })
        case 'sanction': return api.post(base + '/sanction', { amount: body?.amount ? Number(body.amount) : null, note: body?.note || null, conditions: String(body?.conditions ?? '').split('\n').map(c => c.trim()).filter(Boolean) })
        case 'sendBack': return api.post(base + '/sanction/send-back', { note: body?.note })
        case 'decline': return api.post(base + '/sanction/decline', { note: body?.note })
        case 'escalate': return api.post(base + '/sanction/escalate', { note: body?.note })
        case 'acceptKfs': return api.post(base + '/kfs/accept', { otp: body?.otp, language: body?.choice || null })
        case 'disburse': {
          const d = await api.post<{ status: string; utr?: string; failureReason?: string }>(base + '/disburse')
          return d.status !== 'SUCCESS' ? `Disbursement failed: ${d.failureReason}` : d.utr?.startsWith('LIMIT ') ? `Limit ${d.utr.slice(6)} activated` : `Disbursed. UTR ${d.utr}`
        }
        case 'retryDisbursement': return api.post(base + '/disburse/retry')
      }
    }, res => typeof res === 'string' ? res : (ACTION_LABEL[x] ?? label(x)) + ': done')
    if (done !== undefined) { setDialog(null); load() }
  }

  function press(x: string) {
    if (x === 'edit') return nav.push('new', { id })
    if (x === 'uploadDocument' || x === 'reviewDocuments') return nav.push('documents', { id })
    if (x === 'videoKyc') return nav.push('vkyc', { id })
    if (x === 'fieldVisit' && user.role === 'FIELD_OFFICER') return nav.push('visits')
    if (DIALOGS[x]) setDialog(DIALOGS[x]); else act(x)
  }

  return (
    <Screen title={a.applicantName} sub={`${a.appNo} · ${v.product.name}`} scroll={false} testID="app-detail">
      <ScrollView refreshControl={<RefreshControl refreshing={loading} onRefresh={load} />} keyboardShouldPersistTaps="handled">
        <OfflineBanner savedAt={savedAt} />
        <Feedback error={r.error ?? error} ok={r.ok} />
        <Card>
          <Row k="Loan" v={`${inr(a.loanAmount)} · ${a.tenureMonths} months`} />
          <Row k="Status" v={<Pill s={v.states.DISB === 'DONE' ? 'DISBURSED' : v.states.APP} text={v.states.DISB === 'DONE' ? 'Disbursed' : label(v.states.APP)} />} testID="app-state" />
          {a.businessName ? <Row k="Business" v={a.businessName} /> : null}
          <Row k="Mobile" v={a.mobile} />
          {a.city ? <Row k="City" v={a.city} /> : null}
          {a.rejectionReason ? <Note kind="bad">{a.rejectionReason}</Note> : null}
        </Card>

        <Card title="Actions" testID="actions">
          {hinted && !v.actions.length ? <Muted>Your task: {label(hinted)}</Muted> : null}
          {v.actions.length === 0 ? <Muted>No action for you at this stage.</Muted> : null}
          {v.actions.map(x => (
            <Btn key={x} title={x === 'disburse' && step?.key === 'limit' ? 'Activate limit' : ACTION_LABEL[x] ?? label(x)} testID={'act-' + x} busy={r.busy && !dialog}
              kind={PRIMARY.includes(x) ? 'primary' : x === 'decline' ? 'danger' : 'plain'} disabled={!online} onPress={() => press(x)} />
          ))}
          {!online && v.actions.length ? <Muted>Decisions need a connection; documents and photos can be captured offline.</Muted> : null}
          {v.blockers.length > 0 ? <><Text style={{ fontWeight: '700', marginTop: 6, color: C.ink }}>Waiting for</Text>{v.blockers.map((b, i) => <Text key={i} style={{ color: C.muted, marginTop: 3 }}>• {b}</Text>)}</> : null}
          {v.missingDocuments.length > 0 ? <Note kind="warn">Documents still needed: {v.missingDocuments.map(label).join(', ')}</Note> : null}
        </Card>

        {recorded.length > 0 && <Card title="Steps for you">
          {recorded.map(s => <Btn key={s.key} title={(s.canReview && !s.canComplete ? 'Confirm: ' : '') + s.name} testID={'stage-' + s.key}
            onPress={() => ['cgt', 'grt'].includes(s.key) ? nav.push('group', { id }) : setStage(s)} />)}
        </Card>}

        <ListItem title="Documents" sub="Capture, upload and review" testID="go-documents" onPress={() => nav.push('documents', { id })} />
        {step && <ListItem title={step.name} sub="Product-specific step" testID={'go-' + step.key} onPress={() => nav.push('step', { id, kind: step.key })} />}
        {hasCol && <ListItem title="Collateral" sub={colTypes.includes('GOLD') ? 'Declare the gold, weigh and test it, photos' : 'Declare, documents, valuation and geo-tagged photos'} testID="go-collateral" onPress={() => nav.push('collateral', { id })} />}
        {group && <ListItem title="Group: CGT and GRT" sub="Group formation, training and recognition test" testID="go-group" onPress={() => nav.push('group', { id })} />}
        {staff && <ListItem title="Household" sub="Members, earners, expenses and repayments" testID="go-household" onPress={() => nav.push('group', { id, household: true })} />}
        {staff && <ListItem title="Co-applicants & guarantors" sub="Add, consent and sign by OTP, documents, KYC" testID="go-parties" onPress={() => nav.push('parties', { id })} />}
        {['OPERATIONS', 'ADMIN', 'CREDIT_OFFICER', 'CREDIT_MANAGER'].includes(user.role) && <ListItem title="Video KYC" sub="Live call with the customer, AI checks, decision" testID="go-vkyc" onPress={() => nav.push('vkyc', { id })} />}
        {field && user.role === 'FIELD_OFFICER' && <ListItem title="Field visit" sub="Check in, checklist and photos" testID="go-visit" onPress={() => nav.push('visits')} />}
        <ListItem title="Decision & credit memo" sub="Score, recommended amount, reasons" testID="go-decision" onPress={() => nav.push('decision', { id })} />
        <ListItem title="Sanction & KFS" sub="Key Fact Statement and schedule" testID="go-kfs" onPress={() => nav.push('kfs', { id })} />

        <Card title="Progress" testID="progress">
          {STATE_ORDER.map(([k, l]) => <Row key={k} k={l} v={<Pill s={v.states[k]} />} />)}
          {stages.length > 0 && <>
            <Text style={{ fontWeight: '700', marginTop: 10, marginBottom: 4, color: C.ink }}>Workflow</Text>
            {stages.map(s => <Row key={s.key} k={s.name} v={<Pill s={s.status} text={label(s.outcome ?? s.status)} />} />)}
          </>}
        </Card>
        <Muted>Created by {a.createdBy} · {when(a.createdAt)}</Muted>
      </ScrollView>

      {dialog && <ActionSheet d={dialog} busy={r.busy} error={r.error} onClose={() => { setDialog(null); r.setError(undefined) }} onGo={b => act(dialog.kind, b)} />}
      {stage && <StageSheet id={id} s={stage} onClose={() => setStage(null)} onDone={() => { setStage(null); r.setOk(stage.name + ': recorded'); load() }} />}
    </Screen>
  )
}

function ActionSheet({ d, busy, error, onClose, onGo }: { d: Dialog; busy: boolean; error?: string; onClose: () => void; onGo: (b: Record<string, string>) => void }) {
  const [b, setB] = useState<Record<string, string>>({ choice: d.choices?.[0][0] ?? '' })
  const set = (k: string) => (t: string) => setB({ ...b, [k]: t })
  return (
    <Sheet visible title={d.title} onClose={onClose}>
      {d.hint ? <Muted>{d.hint}</Muted> : null}
      {error ? <Note kind="bad" testID="error">{error}</Note> : null}
      {d.fields.includes('amount') && <Input label="Amount (₹)" keyboardType="numeric" value={b.amount ?? ''} onChangeText={set('amount')} testID="f-amount" />}
      {d.fields.includes('otp') && <Input label="OTP from the borrower" keyboardType="number-pad" maxLength={6} value={b.otp ?? ''} onChangeText={set('otp')} testID="f-otp" />}
      {d.fields.includes('choice') && d.choices && <Choice label={d.choiceLabel ?? 'Outcome'} value={b.choice ?? ''} options={d.choices} onChange={set('choice')} />}
      {d.fields.includes('conditions') && <Input label="Conditions (one per line)" multiline value={b.conditions ?? ''} onChangeText={set('conditions')} testID="f-conditions" />}
      {d.fields.includes('note') && <Input label="Note" multiline value={b.note ?? ''} onChangeText={set('note')} testID="f-note" />}
      <Btn title="Confirm" kind={d.kind === 'decline' ? 'danger' : 'primary'} busy={busy} onPress={() => onGo(b)} testID="confirm" />
    </Sheet>
  )
}

export function StageSheet({ id, s, onClose, onDone }: { id: number; s: Stage; onClose: () => void; onDone: () => void }) {
  const review = !!s.canReview && !s.canComplete
  const [outcome, setOutcome] = useState('PASS')
  const [note, setNote] = useState('')
  const [data, setData] = useState<Record<string, string>>({})
  const r = useRun()
  async function go() {
    const d: Record<string, unknown> = {}
    for (const [k, x] of Object.entries(data)) if (x.trim()) d[k] = NUMERIC.includes(k) && !isNaN(Number(x)) ? Number(x) : x.trim()
    const ok = await r.run(() => review ? api.post(`/api/applications/${id}/stages/${s.key}/review`, { note })
      : api.post(`/api/applications/${id}/stages/${s.key}/complete`, { outcome, note, data: d }), 'done')
    if (ok !== undefined) onDone()
  }
  return (
    <Sheet visible title={(review ? 'Confirm: ' : '') + s.name} onClose={onClose}>
      {s.help ? <Muted>{s.help}</Muted> : null}
      <Feedback error={r.error} />
      {!review && <Choice label="Outcome" value={outcome} options={[['PASS', 'Pass'], ['FAIL', 'Fail' + (s.onFailure ? ` (${label(s.onFailure).toLowerCase()})` : '')]]} onChange={setOutcome} testID="stage-outcome" />}
      {!review && (s.dataFields ?? []).map(f => <Input key={f} label={STAGE_FIELD[f] ?? label(f)} keyboardType={NUMERIC.includes(f) ? 'numeric' : 'default'} value={data[f] ?? ''}
        onChangeText={t => setData({ ...data, [f]: t })} testID={'stage-' + f} />)}
      <Input label="Notes (at least 10 characters)" multiline value={note} onChangeText={setNote} testID="stage-note" />
      {!review && outcome === 'FAIL' && s.onFailure === 'REJECT' ? <Note kind="bad">Failing this step rejects the application.</Note> : null}
      <Btn title={review ? 'Confirm' : 'Save'} kind="primary" busy={r.busy} onPress={go} testID="confirm" />
    </Sheet>
  )
}
