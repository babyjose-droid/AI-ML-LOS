// Documents on an application: capture with the camera or pick a file, upload through the outbox (works offline),
// see what the document AI read, and approve or reject the ones it was unsure about.
import React, { useCallback, useEffect, useState } from 'react'
import { Image, Text, View } from 'react-native'
import { api, getCached, message } from '../lib/api'
import { pickFile, takePhoto } from '../lib/capture'
import type { LocalFile } from '../lib/files'
import { add, newId } from '../lib/outbox'
import { useSession } from '../session'
import { Btn, C, Card, Choice, Feedback, Input, Muted, Note, Pill, Row, Screen, Sheet, Spinner, label, useRun, when } from '../ui'
import { useApp } from './AppDetail'
import { OfflineBanner } from './Lists'

interface FieldValue { value: string; confidence: number }
interface Doc { id: number; docType: string; fileName: string; status: string; readConfidence?: number; uploadedBy: string; uploadedAt: string; extractedJson?: string; reviewReasons?: string; masked: boolean; reviewedBy?: string; reviewNote?: string }

export const BASE_TYPES = ['PAN', 'AADHAAR', 'VOTER_ID', 'DRIVING_LICENCE', 'PASSPORT', 'UDYAM', 'SALARY_SLIP', 'BANK_STATEMENT', 'GST_CERT', 'RENT_AGREEMENT', 'PHOTO']
export const typesFor = (requiredDocs: string) => Array.from(new Set([...requiredDocs.split(/[,|]/).map(x => x.trim()).filter(Boolean), ...BASE_TYPES]))

/** Pick a source and keep the file on the phone. */
export function Capture({ onFile, name, testID }: { onFile: (f: LocalFile) => void; name: string; testID?: string }) {
  const r = useRun()
  return (
    <View testID={testID}>
      <View style={{ flexDirection: 'row', gap: 8 }}>
        <Btn small style={{ flex: 1 }} title="Take photo" testID={(testID ?? 'cap') + '-camera'} busy={r.busy} onPress={() => r.run(async () => { const s = await takePhoto(name); if (s) onFile(s.file) })} />
        <Btn small style={{ flex: 1 }} title="Choose file" testID={(testID ?? 'cap') + '-file'} busy={r.busy} onPress={() => r.run(async () => { const f = await pickFile(); if (f) onFile(f) })} />
      </View>
      <Feedback error={r.error} />
    </View>
  )
}

export function Picked({ f }: { f: LocalFile }) {
  return (
    <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10, marginVertical: 8 }}>
      {f.type.startsWith('image/') ? <Image source={{ uri: f.uri }} style={{ width: 56, height: 56, borderRadius: 6, backgroundColor: '#eee' }} /> : <Text style={{ fontSize: 28 }}>📄</Text>}
      <Text style={{ flex: 1, color: C.ink }} numberOfLines={2}>{f.name}{f.size ? ` · ${Math.round(f.size / 1024)} KB` : ''}</Text>
    </View>
  )
}

export default function Documents({ id }: { id: number }) {
  const { v } = useApp(id)
  const { syncNow, online, user } = useSession()
  const [docs, setDocs] = useState<Doc[] | null>(null)
  const [savedAt, setSavedAt] = useState<number>()
  const [type, setType] = useState('')
  const [file, setFile] = useState<LocalFile | null>(null)
  const [password, setPassword] = useState('')
  const [review, setReview] = useState<Doc | null>(null)
  const r = useRun()

  const load = useCallback(async () => {
    try { const x = await getCached<Doc[]>(`/api/applications/${id}/documents`); setDocs(x.data); setSavedAt(x.savedAt) } catch (e) { r.setError(message(e)) }
  }, [id]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { load() }, [load])

  const types = v ? typesFor(v.product.requiredDocs) : BASE_TYPES
  const missing = v?.missingDocuments ?? []
  const chosen = type || missing[0]?.split('|')[0] || types[0]
  const canUpload = ['SALES', 'OPERATIONS', 'ADMIN'].includes(user.role)
  const canReview = ['OPERATIONS', 'ADMIN', 'CREDIT_OFFICER', 'CREDIT_MANAGER'].includes(user.role)

  async function upload() {
    if (!file || !v) return
    await r.run(async () => {
      await add({ id: newId(), kind: 'document', label: `${label(chosen)} for ${v.application.appNo}`, method: 'POST', path: `/api/applications/${id}/documents`,
        fields: [{ name: 'type', value: chosen }, { name: 'file', file }, ...(password ? [{ name: 'password', value: password }] : [])] })
      setFile(null); setPassword('')
      const s = await syncNow()
      await load()
      if (!s || s.offline) return `${label(chosen)} saved on this phone; it will upload when you are online`
      if (s.failed.length) throw new Error('The server refused the upload; see the outbox')
      return `${label(chosen)} uploaded and read`
    }, x => String(x))
  }

  return (
    <Screen title="Documents" sub={v ? `${v.application.applicantName} · ${v.application.appNo}` : undefined} testID="documents">
      <OfflineBanner savedAt={savedAt} />
      <Feedback error={r.error} ok={r.ok} />
      {missing.length > 0 && <Note kind="warn" testID="missing">Still needed: {missing.map(m => m.split('|').map(label).join(' or ')).join(', ')}</Note>}
      {canUpload && <Card title="Add a document" testID="add-document">
        <Choice label="Type" value={chosen} options={types.map(t => [t, label(t)])} onChange={setType} testID="doc-type" />
        <Capture name={chosen.toLowerCase()} onFile={setFile} testID="doc" />
        {file && <Picked f={file} />}
        {chosen === 'BANK_STATEMENT' && <Input label="PDF password (if any)" value={password} onChangeText={setPassword} autoCapitalize="none" secureTextEntry />}
        <Btn title={online ? 'Upload' : 'Save to send later'} kind="primary" disabled={!file} busy={r.busy} onPress={upload} testID="upload" />
        <Muted>Aadhaar is masked to the last four digits before it is stored.</Muted>
      </Card>}
      {docs === null ? <Spinner /> : docs.length === 0 ? <Card><Muted>No documents yet.</Muted></Card> : null}
      {(docs ?? []).map(d => {
        const fields: Record<string, FieldValue> = (() => { try { return JSON.parse(d.extractedJson ?? '{}') } catch { return {} } })()
        return (
          <Card key={d.id} testID={'doc-' + d.docType}>
            <View style={{ flexDirection: 'row', justifyContent: 'space-between', gap: 8 }}>
              <Text style={{ flex: 1, fontWeight: '700', color: C.ink }}>{label(d.docType)}</Text>
              <Pill s={d.status === 'NEEDS_REVIEW' ? 'REFER' : d.status === 'REJECTED' ? 'FAIL' : d.status === 'VERIFIED' ? 'PASS' : 'PENDING'} text={label(d.status)} />
            </View>
            <Muted>{d.fileName} · {d.uploadedBy} · {when(d.uploadedAt)}{d.readConfidence != null ? ` · read ${Math.round(d.readConfidence * 100)}%` : ''}</Muted>
            {Object.entries(fields).filter(([k]) => !['aadhaarChecksum', 'mrzChecks'].includes(k)).slice(0, 6).map(([k, f]) => <Row key={k} k={label(k)} v={String(f?.value ?? '')} />)}
            {d.status === 'NEEDS_REVIEW' && d.reviewReasons ? <Muted>Why it needs a person: {d.reviewReasons}</Muted> : null}
            {d.reviewedBy ? <Muted>Reviewed by {d.reviewedBy}: {d.reviewNote}</Muted> : null}
            {canReview && d.status === 'NEEDS_REVIEW' && <Btn small title="Review" testID={'review-' + d.docType} onPress={() => setReview(d)} />}
          </Card>
        )
      })}
      {review && <ReviewSheet appId={id} d={review} onClose={() => setReview(null)} onDone={() => { setReview(null); r.setOk('Review recorded'); load() }} />}
    </Screen>
  )
}

function ReviewSheet({ appId, d, onClose, onDone }: { appId: number; d: Doc; onClose: () => void; onDone: () => void }) {
  const [note, setNote] = useState('')
  const r = useRun()
  const go = async (action: 'APPROVE' | 'REJECT') => { if (await r.run(() => api.post(`/api/applications/${appId}/documents/${d.id}/review`, { action, fields: {}, note }))) onDone() }
  return (
    <Sheet visible title={'Review ' + label(d.docType)} onClose={onClose}>
      <Feedback error={r.error} />
      <Input label="Note" multiline value={note} onChangeText={setNote} />
      <Btn title="Approve" kind="primary" busy={r.busy} onPress={() => go('APPROVE')} testID="approve" />
      <Btn title="Reject" kind="danger" busy={r.busy} onPress={() => go('REJECT')} />
    </Sheet>
  )
}
