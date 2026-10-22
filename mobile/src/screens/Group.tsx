// Group loans (JLG) and the household: the field officer forms and trains the group (CGT), credit tests it (GRT); the
// household's members, earners, expenses and repayments feed the microfinance affordability rule (RBI 2022).
import React, { useCallback, useEffect, useState } from 'react'
import { Text, View } from 'react-native'
import { api, getCached, message } from '../lib/api'
import { useSession } from '../session'
import { Btn, C, Card, Check, Choice, Feedback, Input, Muted, Note, Pill, Row, Screen, Spinner, inr, label, useRun, when } from '../ui'
import { STAGE_FIELD, StageSheet, useApp, type Stage } from './AppDetail'
import { OfflineBanner } from './Lists'

interface Earner { name: string; relation: string; occupation?: string; monthlyIncome: number; included: boolean }
interface Household { household?: { members: number; monthlyExpenses: number; existingEmi: number; updatedBy?: string }; earners: Earner[]; monthlyIncome?: number; annualIncome?: number
  surplus?: number; mfiQualifying?: boolean; mfiRepaymentRoom?: number }
const RELATIONS = ['SELF', 'SPOUSE', 'SON', 'DAUGHTER', 'FATHER', 'MOTHER', 'BROTHER', 'SISTER', 'OTHER']

export default function GroupScreen({ id, household: only }: { id: number; household?: boolean }) {
  const { v, stages, load } = useApp(id)
  const [stage, setStage] = useState<Stage | null>(null)
  const [ok, setOk] = useState<string>()
  const group = stages.filter(s => s.key === 'cgt' || s.key === 'grt')
  return (
    <Screen title={only ? 'Household' : 'Group & household'} sub={v ? `${v.application.applicantName} · ${v.application.appNo}` : undefined} testID="group">
      {ok ? <Note kind="ok" testID="ok">{ok}</Note> : null}
      {!only && group.map(s => (
        <Card key={s.key} title={s.name} testID={'group-' + s.key}>
          <Row k="Status" v={<Pill s={s.status} text={label(s.outcome ?? s.status)} />} testID={'group-status-' + s.key} />
          {s.data && Object.keys(s.data).length > 0 ? Object.entries(s.data).map(([k, x]) => <Row key={k} k={STAGE_FIELD[k] ?? label(k)} v={String(x)} />) : null}
          {s.completedBy ? <Muted>{s.completedBy} · {when(s.completedAt)}{s.note ? ' · ' + s.note : ''}</Muted> : <Muted>{s.help}</Muted>}
          {(s.canComplete || s.canReview) && <Btn title={s.key === 'cgt' ? 'Record group formation and training' : 'Record the recognition test'} kind="primary" testID={'record-' + s.key} onPress={() => setStage(s)} />}
          {!s.canComplete && !s.canReview && s.status !== 'DONE' ? <Muted>{s.key === 'cgt' ? 'Done by the field officer.' : 'Done by the credit officer after the CGT.'}</Muted> : null}
        </Card>
      ))}
      <HouseholdCard id={id} />
      {stage && <StageSheet id={id} s={stage} onClose={() => setStage(null)} onDone={() => { setOk(stage.name + ': recorded'); setStage(null); load() }} />}
    </Screen>
  )
}

function HouseholdCard({ id }: { id: number }) {
  const { user } = useSession()
  const [h, setH] = useState<Household | null>(null)
  const [savedAt, setSavedAt] = useState<number>()
  const [edit, setEdit] = useState<{ members: string; monthlyExpenses: string; existingEmi: string; earners: (Omit<Earner, 'monthlyIncome'> & { monthlyIncome: string })[] } | null>(null)
  const r = useRun()
  const can = ['SALES', 'OPERATIONS', 'FIELD_OFFICER', 'CREDIT_OFFICER', 'ADMIN'].includes(user.role)
  const load = useCallback(() => { getCached<Household>(`/api/applications/${id}/household`).then(x => { setH(x.data); setSavedAt(x.savedAt) }).catch(e => r.setError(message(e))) }, [id]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { load() }, [load])
  if (!h) return <Card title="Household"><Feedback error={r.error} />{r.error ? null : <Spinner />}</Card>
  function start() {
    setEdit({ members: String(h!.household?.members ?? 1), monthlyExpenses: String(h!.household?.monthlyExpenses ?? ''), existingEmi: String(h!.household?.existingEmi ?? 0),
      earners: h!.earners.length ? h!.earners.map(e => ({ ...e, monthlyIncome: String(e.monthlyIncome) })) : [{ name: '', relation: 'SELF', occupation: '', monthlyIncome: '', included: true }] })
  }
  async function save() {
    const e = edit!
    const done = await r.run(() => api.put(`/api/applications/${id}/household`, { members: Number(e.members), monthlyExpenses: Number(e.monthlyExpenses || 0), existingEmi: Number(e.existingEmi || 0),
      earners: e.earners.filter(x => x.name.trim()).map(x => ({ ...x, monthlyIncome: Number(x.monthlyIncome || 0) })) }), 'Household saved')
    if (done) { setEdit(null); load() }
  }
  const setE = (i: number, k: string, val: string | boolean) => setEdit({ ...edit!, earners: edit!.earners.map((x, j) => j === i ? { ...x, [k]: val } : x) })
  return (
    <Card title="Household cash flow" testID="household">
      <OfflineBanner savedAt={savedAt} />
      <Feedback error={r.error} ok={r.ok} />
      {!edit && (h.household ? <>
        <Row k="Members" v={String(h.household.members)} />
        <Row k="Income" v={`${inr(h.monthlyIncome)}/month · ${inr(h.annualIncome)} a year`} />
        <Row k="Expenses" v={inr(h.household.monthlyExpenses)} />
        <Row k="Existing repayments" v={inr(h.household.existingEmi)} />
        <Row k="Surplus" v={inr(h.surplus)} />
        <Row k="Microfinance" v={<Pill s={h.mfiQualifying ? 'PASS' : 'REFER'} text={h.mfiQualifying ? 'qualifies' : 'above ₹3 lakh a year'} />} testID="mfi" />
        <Row k="Room within the 50% cap" v={`${inr(h.mfiRepaymentRoom)}/month`} />
        {h.earners.map((e, i) => <Muted key={i}>{e.name} · {label(e.relation)} · {e.occupation ?? '—'} · {inr(e.monthlyIncome)}{e.included ? '' : ' (not counted)'}</Muted>)}
      </> : <Muted>Not captured. Needed for microfinance (the RBI cap uses household income); useful for any family business.</Muted>)}
      {!edit && can && <Btn title={h.household ? 'Edit household' : 'Add household'} testID="edit-household" onPress={start} />}
      {edit && <>
        <Input label="Members in the household" keyboardType="numeric" value={edit.members} onChangeText={t => setEdit({ ...edit, members: t })} testID="hh-members" />
        <Input label="Household expenses / month (₹)" keyboardType="numeric" value={edit.monthlyExpenses} onChangeText={t => setEdit({ ...edit, monthlyExpenses: t })} testID="hh-expenses" />
        <Input label="Existing loan repayments / month (₹)" keyboardType="numeric" value={edit.existingEmi} onChangeText={t => setEdit({ ...edit, existingEmi: t })} testID="hh-emi" />
        {edit.earners.map((e, i) => (
          <View key={i} style={{ borderTopWidth: 1, borderTopColor: C.line, paddingTop: 8, marginTop: 4 }}>
            <Text style={{ fontWeight: '700', color: C.ink, marginBottom: 6 }}>Earning member {i + 1}</Text>
            <Input label={`Member ${i + 1}: name`} value={e.name} onChangeText={t => setE(i, 'name', t)} />
            <Choice label="Relation" value={e.relation} options={RELATIONS.map(x => [x, label(x)])} onChange={t => setE(i, 'relation', t)} testID={`hh-relation-${i + 1}`} />
            <Input label={`Member ${i + 1}: occupation`} value={e.occupation ?? ''} onChangeText={t => setE(i, 'occupation', t)} />
            <Input label={`Member ${i + 1}: income / month (₹)`} keyboardType="numeric" value={e.monthlyIncome} onChangeText={t => setE(i, 'monthlyIncome', t)} />
            <Check label="Count this income" value={e.included} onChange={t => setE(i, 'included', t)} />
            {edit.earners.length > 1 && <Btn small title="Remove" onPress={() => setEdit({ ...edit, earners: edit.earners.filter((_, j) => j !== i) })} />}
          </View>
        ))}
        <Btn small title="Add earning member" testID="add-earner" onPress={() => setEdit({ ...edit, earners: [...edit.earners, { name: '', relation: 'SPOUSE', occupation: '', monthlyIncome: '', included: true }] })} />
        <Btn title="Save household" kind="primary" busy={r.busy} testID="save-household" onPress={save} />
        <Btn title="Cancel" onPress={() => setEdit(null)} />
      </>}
    </Card>
  )
}
