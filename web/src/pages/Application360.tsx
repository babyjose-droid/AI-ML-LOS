import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api } from '../api'
import { ErrorBox, Head, Modal, Pill, StateBoard, Table, useLoad } from '../components/ui'
import { inr, label, pct, when } from '../format'
import type { AppView, Contribution, Decision, Disbursement, Doc, FieldValue, History, IntegrationLog, Kfs, KycCheck, Rule, SanctionRecord, Snapshot, Step } from '../types'

const TABS = ['Overview', 'Documents', 'Data', 'Decision', 'Credit memo', 'Sanction & KFS', 'Disbursement', 'History', 'Integrations'] as const
type Tab = typeof TABS[number]

type Dialog = { kind: string; title: string; fields: ('note' | 'amount' | 'otp' | 'choice')[]; choices?: [string, string][]; hint?: string }

const DIALOGS: Record<string, Dialog> = {
  withdraw: { kind: 'withdraw', title: 'Withdraw application', fields: ['note'] },
  resolveKyc: { kind: 'resolveKyc', title: 'Resolve KYC review', fields: ['choice', 'note'], choices: [['true', 'Verified (video KYC done)'], ['false', 'Failed']] },
  fieldVisit: { kind: 'fieldVisit', title: 'Record field visit', fields: ['note'], hint: 'What you saw: business running, stock, premises, neighbour check.' },
  fraudDisposition: { kind: 'fraudDisposition', title: 'Fraud review outcome', fields: ['choice', 'note'], choices: [['true', 'Clear: no fraud'], ['false', 'Confirm fraud and reject']] },
  sanction: { kind: 'sanction', title: 'Sanction', fields: ['amount', 'note'], hint: 'Leave the amount empty to sanction the recommended amount. A reason is required for a referred case or a higher amount.' },
  decline: { kind: 'decline', title: 'Decline', fields: ['note'] },
  escalate: { kind: 'escalate', title: 'Escalate to next level', fields: ['note'] },
  acceptKfs: { kind: 'acceptKfs', title: 'Borrower accepts the Key Fact Statement', fields: ['otp'], hint: 'In this build the OTP is 123456. Phase 4 sends it to the borrower by SMS.' },
}

const ACTION_LABEL: Record<string, string> = {
  edit: 'Edit draft', submit: 'Submit', withdraw: 'Withdraw', process: 'Run automated checks', runKyc: 'Run KYC', fetchData: 'Fetch bank, bureau and GST data',
  verifyDocuments: 'Verify documents', runDecision: 'Run decision engine', resolveKyc: 'Resolve KYC review', fieldVisit: 'Record field visit',
  fraudDisposition: 'Fraud review outcome', sanction: 'Sanction', decline: 'Decline', escalate: 'Escalate', acceptKfs: 'Borrower accepts KFS',
  disburse: 'Disburse', retryDisbursement: 'Retry disbursement', uploadDocument: 'Upload document', reviewDocuments: 'Review documents',
}

export default function Application360() {
  const { id } = useParams()
  const [tab, setTab] = useState<Tab>('Overview')
  const [dialog, setDialog] = useState<Dialog | null>(null)
  const [error, setError] = useState<unknown>()
  const [msg, setMsg] = useState<string>()
  const [busy, setBusy] = useState(false)
  const [rev, setRev] = useState(0)
  const view = useLoad(() => api.get<AppView>('/api/applications/' + id), [id, rev])
  const refresh = () => setRev(r => r + 1)

  async function run(action: string, body?: Record<string, unknown>) {
    setBusy(true)
    setError(undefined)
    setMsg(undefined)
    const base = '/api/applications/' + id
    try {
      switch (action) {
        case 'submit': await api.post(base + '/submit'); break
        case 'withdraw': await api.post(base + '/withdraw', body); break
        case 'process': {
          const r = await api.post<{ steps: { step: string; outcome: string }[] }>(base + '/process')
          setMsg(r.steps.map(s => `${s.step}: ${label(s.outcome)}`).join(' · '))
          break
        }
        case 'runKyc': await api.post(base + '/kyc/run'); break
        case 'fetchData': await api.post(base + '/data/fetch'); break
        case 'verifyDocuments': await api.post(base + '/documents/verify'); break
        case 'runDecision': await api.post(base + '/decision/run'); break
        case 'resolveKyc': await api.post(base + '/kyc/resolve', { verified: body?.choice === 'true', note: body?.note }); break
        case 'fieldVisit': await api.post(base + '/field-visit', body); break
        case 'fraudDisposition': await api.post(base + '/fraud/disposition', { clear: body?.choice === 'true', note: body?.note }); break
        case 'sanction': await api.post(base + '/sanction', { amount: body?.amount ? Number(body.amount) : null, note: body?.note || null }); break
        case 'decline': await api.post(base + '/sanction/decline', body); break
        case 'escalate': await api.post(base + '/sanction/escalate', body); break
        case 'acceptKfs': await api.post(base + '/kfs/accept', body); break
        case 'disburse': {
          const d = await api.post<Disbursement>(base + '/disburse')
          setMsg(d.status === 'SUCCESS' ? `Disbursed. UTR ${d.utr}` : `Disbursement failed: ${d.failureReason}`)
          break
        }
        case 'retryDisbursement': await api.post(base + '/disburse/retry'); break
        case 'reviewDocuments': setTab('Documents'); break
      }
      setDialog(null)
      refresh()
    } catch (e) {
      setError(e)
    } finally {
      setBusy(false)
    }
  }

  if (view.error) return <ErrorBox error={view.error} />
  const v = view.data
  if (!v) return <div className="empty">Loading…</div>
  const a = v.application
  const buttons = v.actions.filter(x => x !== 'uploadDocument')

  return (
    <>
      <Head title={`${a.applicantName} · ${a.appNo}`} sub={`${v.product.name} · ${inr(a.loanAmount)} for ${a.tenureMonths} months · ${a.city ?? ''} · created by ${a.createdBy} ${when(a.createdAt)}`} />
      <ErrorBox error={error} />
      {msg && <div className="alert ok" role="status">{msg}</div>}
      {a.rejectionReason && <div className="alert bad">{a.rejectionReason}</div>}
      <div className="layout360">
        <div style={{ minWidth: 0 }}>
          <div className="panel"><StateBoard states={v.states} /></div>
          <div className="tabs" role="tablist">
            {TABS.map(t => <button key={t} role="tab" aria-selected={tab === t} className={tab === t ? 'on' : ''} onClick={() => setTab(t)}>{t}</button>)}
          </div>
          {tab === 'Overview' && <Overview v={v} />}
          {tab === 'Documents' && <Documents id={id!} canUpload={v.actions.includes('uploadDocument')} canReview={v.actions.includes('reviewDocuments')} missing={v.missingDocuments} rev={rev} onChange={refresh} />}
          {tab === 'Data' && <DataTab id={id!} rev={rev} />}
          {tab === 'Decision' && <DecisionTab id={id!} rev={rev} />}
          {tab === 'Credit memo' && <MemoTab id={id!} rev={rev} />}
          {tab === 'Sanction & KFS' && <SanctionTab id={id!} rev={rev} sanctioned={['SANCTIONED', 'KFS_ACCEPTED'].includes(v.states.SANCTION)} />}
          {tab === 'Disbursement' && <DisbTab id={id!} rev={rev} />}
          {tab === 'History' && <HistoryTab id={id!} rev={rev} />}
          {tab === 'Integrations' && <IntegrationsTab id={id!} rev={rev} />}
        </div>
        <div className="panel actions">
          <h3>Actions</h3>
          {buttons.length === 0 && <p className="small muted">No actions for your role at this stage.</p>}
          {buttons.map(x => x === 'edit'
            ? <Link key={x} className="btn" to={`/applications/${id}/edit`} style={{ display: 'block', marginBottom: 8 }}>{ACTION_LABEL[x]}</Link>
            : <button key={x} data-action={x} className={'btn' + (['process', 'sanction', 'disburse', 'submit', 'acceptKfs'].includes(x) ? ' primary' : '') + (x === 'decline' ? ' danger' : '')}
                disabled={busy} onClick={() => DIALOGS[x] ? setDialog(DIALOGS[x]) : run(x)}>{ACTION_LABEL[x] ?? x}</button>)}
          {v.blockers.length > 0 && v.states.APP !== 'DRAFT' && (
            <>
              <h3 style={{ marginTop: 14 }}>Waiting for</h3>
              <ul className="small" style={{ margin: 0, paddingLeft: 18 }}>{v.blockers.map(b => <li key={b}>{b}</li>)}</ul>
            </>
          )}
        </div>
      </div>
      {dialog && <ActionDialog d={dialog} busy={busy} error={error} onClose={() => setDialog(null)} onSubmit={b => run(dialog.kind, b)} />}
    </>
  )
}

function ActionDialog({ d, busy, error, onClose, onSubmit }: { d: Dialog; busy: boolean; error: unknown; onClose: () => void; onSubmit: (b: Record<string, string>) => void }) {
  const [b, setB] = useState<Record<string, string>>({ choice: d.choices?.[0][0] ?? '' })
  return (
    <Modal title={d.title} onClose={onClose}>
      <ErrorBox error={error} />
      {d.hint && <p className="small muted">{d.hint}</p>}
      <div className="form" style={{ gridTemplateColumns: '1fr' }}>
        {d.fields.includes('choice') && (
          <label className="fld">Outcome
            <select name="choice" value={b.choice} onChange={e => setB({ ...b, choice: e.target.value })}>
              {d.choices!.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
            </select>
          </label>
        )}
        {d.fields.includes('amount') && <label className="fld">Amount (₹)<input name="amount" type="number" value={b.amount ?? ''} onChange={e => setB({ ...b, amount: e.target.value })} /></label>}
        {d.fields.includes('otp') && <label className="fld">OTP<input name="otp" inputMode="numeric" maxLength={6} value={b.otp ?? ''} onChange={e => setB({ ...b, otp: e.target.value })} /></label>}
        {d.fields.includes('note') && <label className="fld">Reason / notes<textarea name="note" rows={3} value={b.note ?? ''} onChange={e => setB({ ...b, note: e.target.value })} /></label>}
        <button className="btn primary" disabled={busy} onClick={() => onSubmit(b)}>Confirm</button>
      </div>
    </Modal>
  )
}

function Overview({ v }: { v: AppView }) {
  const a = v.application
  return (
    <div className="grid2">
      <div className="panel">
        <h3>Applicant</h3>
        <dl className="dl">
          <dt>Name</dt><dd>{a.applicantName}</dd><dt>PAN</dt><dd className="mono">{a.pan}</dd><dt>Mobile</dt><dd>{a.mobile}</dd>
          <dt>Date of birth</dt><dd>{a.dob}</dd><dt>Address</dt><dd>{[a.address, a.city, a.pincode].filter(Boolean).join(', ')}</dd>
          <dt>Business</dt><dd>{a.businessName ?? '—'} · {a.businessVintageYears} years</dd>
        </dl>
      </div>
      <div className="panel">
        <h3>Loan request</h3>
        <dl className="dl">
          <dt>Product</dt><dd>{v.product.name}</dd><dt>Amount</dt><dd>{inr(a.loanAmount)}</dd><dt>Tenure</dt><dd>{a.tenureMonths} months</dd>
          <dt>Purpose</dt><dd>{a.purpose ?? '—'}</dd><dt>Declared income</dt><dd>{inr(a.declaredMonthlyIncome)} / month</dd>
          <dt>Essential expenses</dt><dd>{inr(a.essentialExpenses)} / month</dd>
          <dt>Disbursement to</dt><dd>{a.bankAccountNo ? `${a.bankAccountNo} · ${a.bankIfsc}` : '—'}</dd>
          <dt>Consents</dt><dd>{[a.consentKyc && 'KYC', a.consentAa && 'AA', a.consentBureau && 'Bureau'].filter(Boolean).join(', ') || 'None yet'}</dd>
        </dl>
      </div>
    </div>
  )
}

const DOC_TYPES = ['PAN', 'AADHAAR', 'VOTER_ID', 'DRIVING_LICENCE', 'PASSPORT', 'UDYAM', 'SALARY_SLIP', 'BANK_STATEMENT', 'GST_CERT', 'RENT_AGREEMENT', 'PHOTO']
const FIELD_LABEL: Record<string, string> = {
  pan: 'PAN', name: 'Name', fatherName: "Father's name", dob: 'Date of birth', holderType: 'Holder type', aadhaarMasked: 'Aadhaar (masked)',
  aadhaarLast4: 'Aadhaar last 4', aadhaarChecksum: 'Aadhaar checksum', gender: 'Gender', address: 'Address', pincode: 'PIN code', epicNumber: 'EPIC number',
  relationName: 'Relation name', dlNumber: 'Licence number', validTill: 'Valid till', passportNumber: 'Passport number', surname: 'Surname',
  givenNames: 'Given names', expiry: 'Expiry', sex: 'Sex', nationality: 'Nationality', mrzChecks: 'MRZ checks', udyamNumber: 'Udyam number',
  enterpriseName: 'Enterprise', ownerName: 'Owner', enterpriseType: 'Enterprise type', employeeName: 'Employee', employer: 'Employer',
  netPay: 'Net pay', grossPay: 'Gross pay', month: 'Month',
}
const parse = <T,>(s: string | undefined, d: T): T => { try { return s ? JSON.parse(s) as T : d } catch { return d } }

function Documents({ id, canUpload, canReview, missing, rev, onChange }: { id: string; canUpload: boolean; canReview: boolean; missing: string[]; rev: number; onChange: () => void }) {
  const docs = useLoad(() => api.get<Doc[]>(`/api/applications/${id}/documents`), [id, rev])
  const [type, setType] = useState(missing[0]?.split(' or ')[0] ?? 'PAN')
  const [file, setFile] = useState<File | null>(null)
  const [error, setError] = useState<unknown>()
  const [reviewing, setReviewing] = useState<Doc | null>(null)
  async function upload() {
    if (!file) return
    const fd = new FormData()
    fd.append('type', type)
    fd.append('file', file)
    try {
      await api.upload(`/api/applications/${id}/documents`, fd)
      setFile(null)
      setError(undefined)
      onChange()
    } catch (e) { setError(e) }
  }
  return (
    <>
      {missing.length > 0 && <div className="alert warn">Still needed: {missing.join(', ')}</div>}
      {canUpload && (
        <div className="panel">
          <h3>Upload</h3>
          <ErrorBox error={error} />
          <div className="row">
            <select style={{ maxWidth: 200 }} value={type} onChange={e => setType(e.target.value)} aria-label="Document type">
              {DOC_TYPES.map(t => <option key={t}>{t}</option>)}
            </select>
            <input style={{ maxWidth: 320 }} type="file" accept=".pdf,.jpg,.jpeg,.png" onChange={e => setFile(e.target.files?.[0] ?? null)} aria-label="File" />
            <button className="btn primary" disabled={!file} onClick={upload}>Upload</button>
          </div>
          <p className="small muted" style={{ marginBottom: 0 }}>PDF, JPG or PNG up to 10 MB. The AI reads each file when automated checks run. Aadhaar images are stored masked (last 4 digits only).</p>
        </div>
      )}
      {(docs.data ?? []).length === 0 && <div className="panel empty">No documents uploaded yet.</div>}
      {(docs.data ?? []).map(d => <DocCard key={d.id} appId={id} d={d} canReview={canReview} onReview={() => setReviewing(d)} />)}
      {reviewing && <ReviewDialog appId={id} d={reviewing} onClose={() => setReviewing(null)} onDone={() => { setReviewing(null); onChange() }} />}
    </>
  )
}

function DocCard({ appId, d, canReview, onReview }: { appId: string; d: Doc; canReview: boolean; onReview: () => void }) {
  const fields = parse<Record<string, FieldValue>>(d.extractedJson, {})
  const checks = parse<{ check: string; pass: boolean }[]>(d.validationsJson, [])
  const quality = parse<{ issues?: string[]; sharpness?: number }>(d.qualityJson, {})
  const tamper = parse<{ score?: number; signals?: string[] }>(d.tamperJson, {})
  const reasons = parse<string[]>(d.reviewReasons, [])
  return (
    <div className="panel" data-doc={d.docType}>
      <div className="head" style={{ marginBottom: 10 }}>
        <div>
          <h3 style={{ margin: 0 }}>{d.docType.replace(/_/g, ' ')} <Pill s={d.status} /> {d.masked && <span className="pill ok">Aadhaar masked</span>}</h3>
          <p className="small muted" style={{ margin: '4px 0 0' }}>
            <a href="#" onClick={e => { e.preventDefault(); openDoc(appId, d) }}>{d.fileName}</a> · {d.uploadedBy} · {when(d.uploadedAt)}
            {d.engine && <> · read by <b>{d.engine}</b></>}
            {d.detectedType && <> · looks like <b>{d.detectedType.replace(/_/g, ' ')}</b> ({pct(d.typeConfidence ?? 0, 0)})</>}
            {d.readConfidence !== undefined && d.readConfidence !== null && <> · confidence <b>{pct(d.readConfidence, 0)}</b></>}
          </p>
        </div>
        {canReview && d.status === 'NEEDS_REVIEW' && <button className="btn primary sm" data-review={d.docType} onClick={onReview}>Review</button>}
      </div>
      {d.remarks && <div className={'alert ' + (d.status === 'REJECTED' ? 'bad' : d.status === 'VERIFIED' ? 'warn' : 'warn')}>{d.remarks}</div>}
      {Object.keys(fields).length > 0 && (
        <Table head={['Field', 'Value', 'Confidence', 'Source']} rows={Object.entries(fields).map(([k, f]) => [
          FIELD_LABEL[k] ?? k, <b className="mono" data-field={k}>{f.value}</b>,
          <div className="row" style={{ flexWrap: 'nowrap' }}><div className="bar" style={{ width: 80 }}><span style={{ width: `${f.confidence * 100}%`, background: f.confidence >= 0.85 ? 'var(--ok)' : f.confidence >= 0.6 ? 'var(--warn)' : 'var(--bad)' }} /></div><span className="small num">{pct(f.confidence, 0)}</span></div>,
          <span className="small muted">{f.source}</span>,
        ])} />
      )}
      <div className="row small" style={{ marginTop: 10, gap: 14 }}>
        {checks.map(c => <span key={c.check}><Pill s={c.pass ? 'PASS' : 'FAIL'} /> {c.check}</span>)}
        {(quality.issues ?? []).map(q => <span key={q}><Pill s="WARN" /> {q}</span>)}
        {(tamper.signals ?? []).map(t => <span key={t}><Pill s="FAIL" /> {t}</span>)}
      </div>
      {d.status === 'NEEDS_REVIEW' && reasons.length > 0 && <p className="small muted">Why it needs a person: {reasons.join('; ')}</p>}
      {d.reviewedBy && <p className="small muted">Reviewed by {d.reviewedBy} {when(d.reviewedAt)}: {d.reviewNote}</p>}
    </div>
  )
}

function ReviewDialog({ appId, d, onClose, onDone }: { appId: string; d: Doc; onClose: () => void; onDone: () => void }) {
  const fields = parse<Record<string, FieldValue>>(d.extractedJson, {})
  const editable = Object.keys(fields).filter(k => !['aadhaarMasked', 'aadhaarChecksum', 'mrzChecks', 'holderType'].includes(k))
  const [vals, setVals] = useState<Record<string, string>>(Object.fromEntries(editable.map(k => [k, fields[k].value])))
  const [note, setNote] = useState('')
  const [error, setError] = useState<unknown>()
  async function send(action: 'APPROVE' | 'REJECT') {
    const changed = Object.fromEntries(Object.entries(vals).filter(([k, v]) => v !== fields[k]?.value))
    try {
      await api.post(`/api/applications/${appId}/documents/${d.id}/review`, { action, fields: changed, note })
      onDone()
    } catch (e) { setError(e) }
  }
  return (
    <Modal title={`Review ${d.docType.replace(/_/g, ' ')}`} onClose={onClose}>
      <ErrorBox error={error} />
      <p className="small muted">Open the file, compare it with the values below, correct anything wrong, then approve or reject. {d.remarks}</p>
      <p className="small"><a href="#" onClick={e => { e.preventDefault(); openDoc(appId, d) }}>Open {d.fileName}</a></p>
      <div className="form" style={{ gridTemplateColumns: '1fr' }}>
        {editable.map(k => (
          <label key={k} className="fld">{FIELD_LABEL[k] ?? k} {fields[k] && <span className="muted">({pct(fields[k].confidence, 0)})</span>}
            <input name={k} value={vals[k] ?? ''} onChange={e => setVals({ ...vals, [k]: e.target.value })} />
          </label>
        ))}
        <label className="fld">Reviewer note (required)<textarea name="note" rows={3} value={note} onChange={e => setNote(e.target.value)} /></label>
        <div className="row">
          <button className="btn primary" onClick={() => send('APPROVE')}>Approve</button>
          <button className="btn danger" onClick={() => send('REJECT')}>Reject</button>
        </div>
      </div>
    </Modal>
  )
}

async function openDoc(id: string, d: Doc) {
  let token: string | null = null
  try { token = localStorage.getItem('rhythm_token') } catch { /* ignore */ }
  const r = await fetch(`/api/applications/${id}/documents/${d.id}/content`, { headers: token ? { Authorization: 'Bearer ' + token } : {} })
  const url = URL.createObjectURL(await r.blob())
  window.open(url, '_blank')
}

function DataTab({ id, rev }: { id: string; rev: number }) {
  const kc = useLoad(() => api.get<KycCheck[]>(`/api/applications/${id}/kyc-checks`), [id, rev])
  return (
    <>
      {(kc.data ?? []).length > 0 && (
        <div className="panel">
          <h3>KYC checks from documents</h3>
          <Table head={['Check', 'Result', 'Score', 'Application', 'Document']} rows={(kc.data ?? []).map(c => [
            c.checkName, <Pill s={c.result} />, pct(c.score, 0), <span className="small">{c.expected ?? '—'}</span>, <span className="small">{c.found ?? '—'}</span>,
          ])} />
          <p className="small muted" style={{ marginBottom: 0 }}>These checks compare what the AI read on the documents with the application. Online validation with the issuers is added in the integrations phase.</p>
        </div>
      )}
      <VendorData id={id} rev={rev} />
    </>
  )
}

function VendorData({ id, rev }: { id: string; rev: number }) {
  const snaps = useLoad(() => api.get<Snapshot[]>(`/api/applications/${id}/snapshots`), [id, rev])
  const latest: Record<string, Snapshot> = {}
  for (const s of snaps.data ?? []) if (!latest[s.kind]) latest[s.kind] = s
  if (!Object.keys(latest).length) return <div className="panel empty">No external data yet. Run KYC and the data fetch.</div>
  const kyc = latest.KYC && JSON.parse(latest.KYC.payloadJson)
  const aa = latest.AA && JSON.parse(latest.AA.payloadJson)
  const bu = latest.BUREAU && JSON.parse(latest.BUREAU.payloadJson)
  const gst = latest.GST && JSON.parse(latest.GST.payloadJson)
  const max = aa ? Math.max(...aa.inflows, ...aa.outflows) : 1
  return (
    <div className="grid2">
      {kyc && <div className="panel"><h3>KYC</h3><dl className="dl">
        <dt>PAN status</dt><dd>{kyc.pan.status} · {kyc.pan.registeredName}</dd><dt>Aadhaar</dt><dd>{kyc.aadhaar.maskedAadhaar}</dd>
        <dt>CKYC</dt><dd>{kyc.ckyc.found ? kyc.ckyc.ckycNo : 'Not found'}</dd>
        <dt>Name match</dt><dd>{pct(kyc.nameMatch, 0)}</dd><dt>Address match</dt><dd>{pct(kyc.addressMatch, 0)}</dd><dt>Contact match</dt><dd>{pct(kyc.contactMatch, 0)}</dd>
      </dl><p className="small muted">Source {latest.KYC.vendor} · {when(latest.KYC.fetchedAt)}</p></div>}
      {bu && <div className="panel"><h3>Credit bureau</h3>{bu.hit ? <dl className="dl">
        <dt>Bureau</dt><dd>{bu.bureau}</dd><dt>Score</dt><dd>{bu.score}</dd><dt>Active lines</dt><dd>{bu.activeLines}</dd>
        <dt>Max DPD (12m)</dt><dd>{bu.maxDpd12}</dd><dt>Enquiries (6m)</dt><dd>{bu.enquiries6m}</dd><dt>History</dt><dd>{bu.historyMonths} months</dd>
        <dt>Existing EMIs</dt><dd>{inr(bu.totalEmi)}</dd></dl> : <p>No bureau record (thin file). The decision relies on bank cash flow.</p>}
        <p className="small muted">Source {latest.BUREAU.vendor} · {when(latest.BUREAU.fetchedAt)}</p></div>}
      {aa && <div className="panel"><h3>Bank cash flow ({aa.months} months, Account Aggregator)</h3>
        {aa.inflows.map((x: number, i: number) => (
          <div key={i} className="contrib" style={{ gridTemplateColumns: '40px 1fr 90px' }}>
            <span className="small muted">M{i + 1}</span>
            <div><div className="bar"><span style={{ width: `${x / max * 100}%`, background: 'var(--ok)' }} /></div>
              <div className="bar" style={{ marginTop: 3 }}><span style={{ width: `${aa.outflows[i] / max * 100}%`, background: 'var(--warn)' }} /></div></div>
            <span className="small num">{inr(x - aa.outflows[i])}</span>
          </div>
        ))}
        <p className="small muted">Green inflow, amber outflow, net on the right. Average balance {inr(aa.avgBalance)} · bounces {aa.bounces} · EMIs seen {inr(aa.detectedEmi)}</p></div>}
      {gst && <div className="panel"><h3>GST</h3><dl className="dl">
        <dt>GSTIN</dt><dd className="mono">{gst.gstin}</dd><dt>Filing regularity</dt><dd>{pct(gst.filingRegularity, 0)}</dd>
        <dt>Turnover growth</dt><dd>{pct(gst.turnoverGrowth, 0)}</dd><dt>Bank vs GST gap</dt><dd>{pct(gst.bankGstGap, 0)}</dd></dl></div>}
    </div>
  )
}

function DecisionTab({ id, rev }: { id: string; rev: number }) {
  const d = useLoad(() => api.get<Decision | undefined>(`/api/applications/${id}/decision`), [id, rev])
  if (!d.data) return <div className="panel empty">{d.loading ? 'Loading…' : 'The decision engine has not run yet.'}</div>
  const x = d.data
  const rules: Rule[] = JSON.parse(x.rulesJson)
  const contrib: Contribution[] = JSON.parse(x.contributionsJson)
  const steps: Step[] = JSON.parse(x.stepsJson)
  const conditions: string[] = JSON.parse(x.conditionsJson)
  const maxAbs = Math.max(...contrib.map(c => Math.abs(c.value)), 0.01)
  return (
    <>
      <div className="kpis">
        <div className="kpi"><span>Decision</span><b style={{ fontSize: 18 }}><Pill s={x.decision} /></b></div>
        <div className="kpi"><span>Probability of default</span><b>{pct(x.pd)}</b></div>
        <div className="kpi"><span>Score · band</span><b>{x.score} · {x.riskBand}</b></div>
        <div className="kpi"><span>Recommended</span><b>{inr(x.recommendedAmount)}</b></div>
        <div className="kpi"><span>Rate</span><b>{x.rate}%</b></div>
        <div className="kpi"><span>Sanction level</span><b>{x.delegationLevel ?? '—'}</b></div>
      </div>
      <div className="grid2">
        <div className="panel">
          <h3>Steps</h3>
          <Table head={['Check', 'Result', '']} rows={steps.map(s => [s.name, s.value, <span className={'pill ' + (s.status === 'ok' ? 'ok' : s.status === 'bad' ? 'bad' : s.status === 'warn' ? 'warn' : '')}>{s.status}</span>])} />
          <p className="small">{x.narrative}</p>
          {conditions.length > 0 && <><h3>Conditions</h3><ul className="small">{conditions.map(c => <li key={c}>{c}</li>)}</ul></>}
        </div>
        <div className="panel">
          <h3>What drove the risk (log-odds)</h3>
          {contrib.map(c => (
            <div key={c.label} className="contrib">
              <span>{c.label}</span>
              <div className="bar"><span style={{ width: `${Math.abs(c.value) / maxAbs * 100}%`, background: c.value > 0 ? 'var(--bad)' : 'var(--ok)' }} /></div>
              <span className="num small">{c.value > 0 ? '+' : ''}{c.value.toFixed(2)}</span>
            </div>
          ))}
          <p className="small muted">Red raises risk, green lowers it. Reason codes: {x.reasonCodes || 'none'}</p>
        </div>
      </div>
      <div className="panel flush">
        <Table head={['Rule', 'Check', 'Result']} rows={rules.map(r => [r.id, r.description, r.pass ? <Pill s="SUCCESS" /> : <span className={'pill ' + (r.action === 'REJECT' ? 'bad' : 'warn')}>{r.action}</span>])} />
      </div>
      <p className="small muted">Model {x.modelVersion} · policy {x.policyVersion} · input hash <span className="mono">{x.inputHash.slice(0, 16)}</span> · run by {x.createdBy} {when(x.createdAt)}</p>
    </>
  )
}

function MemoTab({ id, rev }: { id: string; rev: number }) {
  const d = useLoad(() => api.get<Decision | undefined>(`/api/applications/${id}/decision`), [id, rev])
  if (!d.data) return <div className="panel empty">The credit memo is written when the decision engine runs.</div>
  return <div className="panel"><pre className="memo">{d.data.creditMemo}</pre></div>
}

function SanctionTab({ id, rev, sanctioned }: { id: string; rev: number; sanctioned: boolean }) {
  const recs = useLoad(() => api.get<SanctionRecord[]>(`/api/applications/${id}/sanctions`), [id, rev])
  const kfs = useLoad(() => sanctioned ? api.get<Kfs>(`/api/applications/${id}/kfs`) : Promise.resolve(undefined), [id, rev, sanctioned])
  const k = kfs.data
  return (
    <>
      <div className="panel flush">
        <Table head={['When', 'Action', 'Level', 'Amount', 'Override', 'By', 'Note']} rows={(recs.data ?? []).map(r => [
          when(r.createdAt), <Pill s={r.action === 'APPROVED' ? 'SANCTIONED' : r.action} />, r.level, r.amount ? inr(r.amount) : '—',
          r.overrideFlag ? <span className="pill warn">Override</span> : '—', r.actor, <span className="small">{r.note ?? '—'}</span>,
        ])} empty="No sanction action yet." />
      </div>
      {k && (
        <div className="panel">
          <h3>Key Fact Statement</h3>
          <div className="grid2">
            <dl className="dl">
              <dt>Sanctioned amount</dt><dd>{inr(k.sanctionedAmount)}</dd><dt>Processing fee</dt><dd>{inr(k.processingFee)} + GST {inr(k.gstOnFee)}</dd>
              <dt>Net disbursed</dt><dd>{inr(k.netDisbursed)}</dd><dt>Interest rate</dt><dd>{k.ratePa}% a year (reducing)</dd>
              <dt>APR (all-in)</dt><dd><b>{k.aprPa}%</b></dd>
            </dl>
            <dl className="dl">
              <dt>EMI</dt><dd>{inr(k.emi)} × {k.tenureMonths}</dd><dt>Total interest</dt><dd>{inr(k.totalInterest)}</dd>
              <dt>Total payable</dt><dd>{inr(k.totalPayable)}</dd><dt>Cooling-off</dt><dd>{k.coolingOffDays} days, no penalty</dd>
              <dt>Repayment</dt><dd>{k.repaymentMode}</dd><dt>Grievance</dt><dd className="small">{k.grievanceOfficer}</dd>
            </dl>
          </div>
          <details style={{ marginTop: 10 }}>
            <summary className="small">Repayment schedule ({k.schedule.length} months)</summary>
            <Table head={['Month', 'EMI', 'Interest', 'Principal', 'Balance']} rows={k.schedule.map(r => [r.month, inr(r.emi), inr(r.interest), inr(r.principal), inr(r.balance)])} />
          </details>
        </div>
      )}
    </>
  )
}

function DisbTab({ id, rev }: { id: string; rev: number }) {
  const d = useLoad(() => api.get<Disbursement[]>(`/api/applications/${id}/disbursements`), [id, rev])
  const list = d.data ?? []
  return (
    <>
      <div className="panel flush">
        <Table head={['When', 'Status', 'Gross', 'Deductions', 'Net', 'Account', 'Name match', 'UTR / reason']} rows={list.map(x => [
          when(x.createdAt), <Pill s={x.status} />, inr(x.grossAmount), inr(x.deductions), inr(x.netAmount), `${x.beneficiaryAccount} · ${x.ifsc}`,
          x.nameMatchScore !== undefined && x.nameMatchScore !== null ? pct(x.nameMatchScore, 0) : '—', x.utr ?? x.failureReason ?? '—',
        ])} empty="Not disbursed yet." />
      </div>
      {list.filter(x => x.lmsPayload).map(x => (
        <div className="panel" key={x.id}><h3>Loan booking file sent to the LMS</h3><pre className="memo mono">{JSON.stringify(JSON.parse(x.lmsPayload!), null, 2)}</pre></div>
      ))}
    </>
  )
}

function HistoryTab({ id, rev }: { id: string; rev: number }) {
  const h = useLoad(() => api.get<History[]>(`/api/applications/${id}/history`), [id, rev])
  return (
    <div className="panel">
      <ul className="timeline">
        {(h.data ?? []).slice().reverse().map(x => (
          <li key={x.id}>
            <span className="small muted">{when(x.createdAt)}<br />{x.actor}</span>
            <div><b>{label(x.domain)}: {x.fromState} → {x.toState}</b> <span className="mono muted">{x.event}</span>{x.note && <div className="small">{x.note}</div>}</div>
          </li>
        ))}
      </ul>
    </div>
  )
}

function IntegrationsTab({ id, rev }: { id: string; rev: number }) {
  const l = useLoad(() => api.get<IntegrationLog[]>(`/api/applications/${id}/integrations`), [id, rev])
  return (
    <div className="panel flush">
      <Table head={['When', 'Vendor', 'Operation', 'Attempt', 'Status', 'Latency', 'Result']} rows={(l.data ?? []).map(x => [
        when(x.createdAt), x.vendor, <span className="mono">{x.operation}</span>, x.attempt, <Pill s={x.status} />, `${x.latencyMs} ms`,
        <span className="small">{x.responseSummary ?? x.errorMessage}</span>,
      ])} empty="No vendor calls yet." />
    </div>
  )
}
