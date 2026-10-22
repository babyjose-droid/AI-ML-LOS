// Decision engine result and credit memo; the sanction letter's Key Fact Statement with its repayment schedule.
import React, { useCallback, useEffect, useState } from 'react'
import { Text } from 'react-native'
import { api, getCached, message } from '../lib/api'
import { Body, Btn, C, Card, Feedback, Muted, Note, Pill, Row, Screen, Spinner, inr, label, useRun, when } from '../ui'
import { OfflineBanner } from './Lists'

interface Decision {
  decision: string; pd: number; score: number; riskBand: string; fraudScore: number; fraudStatus: string; kycStatus: string; sustainableIncome: number; maxEmi: number
  requestedEmi: number; recommendedAmount: number; recommendedEmi: number; rate: number; foirPost: number; delegationLevel?: string; reasonCodes: string
  conditionsJson: string; narrative: string; creditMemo: string; modelVersion: string; policyVersion: string; createdAt: string
}
interface Kfs {
  applicationNo: string; borrower: string; product: string; sanctionedAmount: number; processingFee: number; gstOnFee: number; insurance: number; netDisbursed: number
  ratePa: number; aprPa: number; tenureMonths: number; emi: number; totalInterest: number; totalPayable: number; coolingOffDays: number; repaymentMode: string; grievanceOfficer: string
  schedule: { month: number; emi: number; interest: number; principal: number; balance: number }[]
}

function useGet<T>(path: string) {
  const [d, setD] = useState<T | null | undefined>(undefined)
  const [savedAt, setSavedAt] = useState<number>()
  const [error, setError] = useState<string>()
  const load = useCallback(() => { getCached<T>(path).then(r => { setD(r.data ?? null); setSavedAt(r.savedAt) }).catch(e => { setD(null); setError(message(e)) }) }, [path])
  useEffect(() => { load() }, [load])
  return { d, savedAt, error, load }
}

interface Memo { memo?: { version: number; status: string; body: string; signedBy?: string; signedAt?: string }; deviations: { rule: string; description: string; justification: string }[]; canEdit: boolean; signRequired: boolean }

function MemoCard({ id }: { id: number }) {
  const { d, load } = useGet<Memo>(`/api/applications/${id}/memo`)
  const r = useRun()
  if (!d?.memo) return null
  const m = d.memo
  return (
    <Card title={`Credit memo v${m.version}`} testID="memo">
      <Row k="Status" v={<Pill s={m.status === 'SIGNED' ? 'PASS' : 'PENDING'} text={label(m.status)} />} testID="memo-status" />
      {m.signedBy ? <Muted>Signed by {m.signedBy} · {when(m.signedAt)}</Muted> : d.signRequired ? <Muted>Sanction waits for this memo to be signed.</Muted> : null}
      <Body>{m.body}</Body>
      {d.deviations.map(x => <Muted key={x.rule}>{x.rule} · {x.description}{x.justification ? ': ' + x.justification : ' (justify on the web console)'}</Muted>)}
      <Feedback error={r.error} ok={r.ok} />
      {d.canEdit && m.status !== 'SIGNED' && <Btn title="Sign and lock" kind="primary" busy={r.busy} testID="sign-memo" onPress={() => r.run(() => api.post(`/api/applications/${id}/memo/sign`, {}), 'Memo signed').then(x => { if (x) load() })} />}
    </Card>
  )
}

export function DecisionScreen({ id }: { id: number }) {
  const { d, savedAt, error } = useGet<Decision>(`/api/applications/${id}/decision`)
  const list = <T,>(s?: string): T[] => { try { return s ? JSON.parse(s) : [] } catch { return [] } }
  return (
    <Screen title="Decision" sub="Decision engine and credit memo" testID="decision">
      <OfflineBanner savedAt={savedAt} />
      {d === undefined ? <Spinner /> : !d ? <Note kind="warn">{error && !error.includes('404') ? error : 'No decision yet: run the decision engine once the data is in.'}</Note> : <>
        <Card>
          <Row k="Decision" v={<Pill s={d.decision} />} testID="decision-outcome" />
          <Row k="Score · risk band" v={`${d.score} · ${d.riskBand}`} />
          <Row k="Probability of default" v={(d.pd * 100).toFixed(2) + '%'} />
          <Row k="Recommended" v={`${inr(d.recommendedAmount)} · EMI ${inr(d.recommendedEmi)}`} />
          <Row k="Rate" v={d.rate + '% p.a.'} />
          <Row k="FOIR after the loan" v={Math.round(d.foirPost * 100) + '%'} />
          <Row k="Sustainable income" v={inr(d.sustainableIncome)} />
          <Row k="KYC · fraud" v={`${label(d.kycStatus)} · ${label(d.fraudStatus)}`} />
          {d.delegationLevel ? <Row k="Sanction level" v={d.delegationLevel} /> : null}
        </Card>
        {d.reasonCodes ? <Card title="Reasons"><Body>{d.reasonCodes.split(',').map(x => x.trim()).filter(Boolean).join('\n')}</Body></Card> : null}
        {list<string>(d.conditionsJson).length > 0 && <Card title="Conditions">{list<string>(d.conditionsJson).map((c, i) => <Text key={i} style={{ color: C.ink, marginBottom: 4 }}>• {typeof c === 'string' ? c : JSON.stringify(c)}</Text>)}</Card>}
        {d.narrative ? <Card title="Summary"><Body>{d.narrative}</Body></Card> : null}
        <MemoCard id={id} />
        <Muted>Model {d.modelVersion} · policy {d.policyVersion}</Muted>
      </>}
    </Screen>
  )
}

export function KfsScreen({ id }: { id: number }) {
  const { d, savedAt, error } = useGet<Kfs>(`/api/applications/${id}/kfs`)
  return (
    <Screen title="Key Fact Statement" sub="Show this to the borrower before they accept" testID="kfs">
      <OfflineBanner savedAt={savedAt} />
      {d === undefined ? <Spinner /> : !d ? <Note kind="warn">{error && !/40[049]/.test(error) ? error : 'The KFS is ready once the loan is sanctioned.'}</Note> : <>
        <Card>
          <Row k="Borrower" v={d.borrower} />
          <Row k="Product" v={d.product} />
          <Row k="Sanctioned" v={inr(d.sanctionedAmount)} testID="kfs-amount" />
          <Row k="Processing fee + GST" v={inr(d.processingFee + d.gstOnFee)} />
          {d.insurance ? <Row k="Insurance" v={inr(d.insurance)} /> : null}
          <Row k="Paid to the borrower" v={inr(d.netDisbursed)} />
          <Row k="Interest rate" v={d.ratePa + '% p.a.'} />
          <Row k="Annual percentage rate (APR)" v={d.aprPa + '%'} />
          <Row k="Tenure" v={d.tenureMonths + ' months'} />
          <Row k="Instalment" v={inr(d.emi)} testID="kfs-emi" />
          <Row k="Total interest" v={inr(d.totalInterest)} />
          <Row k="Total payable" v={inr(d.totalPayable)} />
          <Row k="Repayment" v={d.repaymentMode} />
          <Row k="Cooling-off period" v={d.coolingOffDays + ' days'} />
          <Row k="Grievance officer" v={d.grievanceOfficer} />
        </Card>
        <Card title="Repayment schedule (first 12)">
          {d.schedule.slice(0, 12).map(x => <Row key={x.month} k={'Month ' + x.month} v={`${inr(x.emi)} · balance ${inr(x.balance)}`} />)}
        </Card>
      </>}
    </Screen>
  )
}
