import { useEffect, useState } from 'react'
import { api } from '../api'
import { useAuth } from '../auth'
import { Link } from 'react-router-dom'
import { ErrorBox, Modal, Pill, Table, useLoad } from '../components/ui'
import { inr, when } from '../format'
import type { Consent, MemoDeviation, MemoView } from '../types'

const SOURCE: Record<string, string> = { KYC: 'Identity (KYC)', AA: 'Bank data (Account Aggregator)', BUREAU: 'Credit bureau', GST: 'GST returns', COMMUNICATION: 'Messages' }
const LANG: Record<string, string> = { en: 'English', hi: 'Hindi', mr: 'Marathi', ta: 'Tamil', ml: 'Malayalam', kn: 'Kannada', te: 'Telugu', bn: 'Bengali', gu: 'Gujarati' }

/** Consent ledger (BR-04): what the borrower agreed to, for how long, and withdrawal with its recorded effect. */
export function ConsentPanel({ id, rev, onChange }: { id: string; rev: number; onChange: () => void }) {
  const { user } = useAuth()
  const list = useLoad(() => api.get<Consent[]>(`/api/applications/${id}/consents`), [id, rev])
  const [w, setW] = useState<Consent | null>(null)
  const [note, setNote] = useState('')
  const [err, setErr] = useState<unknown>(null)
  const front = ['SALES', 'OPERATIONS', 'ADMIN'].includes(user?.role ?? '')
  async function withdraw() {
    try {
      setErr(null)
      await api.post(`/api/applications/${id}/consents/${w!.source}/withdraw`, { note })
      setW(null); setNote(''); onChange()
    } catch (e) { setErr(e) }
  }
  const rows = list.data ?? []
  return (
    <div className="panel" style={{ marginTop: 12 }} data-testid="consents">
      <h3>Consents</h3>
      <ErrorBox error={list.error} />
      <Table head={['Source', 'Purpose', 'Scope', 'Channel · language', 'Given', 'Valid until', 'Status', '']} rows={rows.map(c => [
        SOURCE[c.source] ?? c.source, <span className="small">{c.purpose}</span>, <span className="small">{c.scope}</span>,
        <span className="small">{c.channel} · {LANG[c.language] ?? c.language}</span>, when(c.grantedAt), c.validUntil ? when(c.validUntil) : 'Until withdrawn',
        <span title={c.effect ?? ''}><Pill s={(c as Consent & { state?: string }).state ?? c.status} />{c.withdrawnAt && <div className="small muted">by {c.withdrawnBy} · {when(c.withdrawnAt)}</div>}</span>,
        front && c.status === 'GRANTED' ? <button className="btn small" data-action={'withdraw-' + c.source} onClick={() => setW(c)}>Withdraw</button> : '',
      ])} empty="Consents are recorded when the application is submitted." />
      {w && (
        <Modal title={`Withdraw ${SOURCE[w.source] ?? w.source} consent`} onClose={() => setW(null)}>
          <ErrorBox error={err} />
          <p className="small muted">After withdrawal: no further pulls from this source. Data already held is used only for this application. Disbursement is blocked if KYC, bank or bureau consent is no longer valid.</p>
          <label className="fld">How did the borrower ask? (at least 10 characters)<textarea name="note" rows={3} value={note} onChange={e => setNote(e.target.value)} /></label>
          <button className="btn danger" onClick={withdraw}>Withdraw consent</button>
        </Modal>
      )}
    </div>
  )
}

/** Credit memo (FSD 12.2): edit, justify each deviation, sign and lock; versions and numbers not found in the file. */
export function CreditMemoPanel({ id, rev, onChange }: { id: string; rev: number; onChange: () => void }) {
  const [n, setN] = useState(0)
  const v = useLoad(() => api.get<MemoView>(`/api/applications/${id}/memo`), [id, rev, n])
  const [body, setBody] = useState('')
  const [devs, setDevs] = useState<MemoDeviation[]>([])
  const [editing, setEditing] = useState(false)
  const [err, setErr] = useState<unknown>(null)
  const [busy, setBusy] = useState(false)
  useEffect(() => { if (v.data?.memo) { setBody(v.data.memo.body); setDevs(v.data.deviations) } }, [v.data])
  if (v.error) return <ErrorBox error={v.error} />
  const d = v.data
  if (!d) return <div className="panel empty">Loading…</div>
  if (!d.memo) return <div className="panel empty">The credit memo is drafted when the decision engine reaches a decision.</div>
  const m = d.memo
  async function call(fn: () => Promise<unknown>) {
    setBusy(true); setErr(null)
    try { await fn(); setEditing(false); setN(x => x + 1); onChange() } catch (e) { setErr(e) } finally { setBusy(false) }
  }
  const save = () => call(() => api.put(`/api/applications/${id}/memo`, { body, deviations: devs }))
  const sign = () => call(async () => {
    if (editing) await api.put(`/api/applications/${id}/memo`, { body, deviations: devs })
    await api.post(`/api/applications/${id}/memo/sign`, {})
  })
  return (
    <>
      <div className="panel" data-testid="memo">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
          <h3 style={{ margin: 0 }}>Credit memo v{m.version} <Pill s={m.status} /></h3>
          <span className="small muted">
            {m.status === 'SIGNED' ? `Signed by ${m.signedBy} (${m.signerRole}) · ${when(m.signedAt!)} · locked` : `Last edited by ${m.editedBy}${m.editedAt ? ' · ' + when(m.editedAt) : ''}`}
            {d.signRequired && m.status !== 'SIGNED' && ' · Sanction waits for this memo to be signed'}
          </span>
        </div>
        <ErrorBox error={err} />
        {d.unsourcedNumbers.length > 0 && (
          <div className="alert warn small" style={{ marginTop: 8 }}>Numbers not found in the file: {d.unsourcedNumbers.join(', ')}. Check them against the data before signing.</div>
        )}
        {editing
          ? <textarea name="memoBody" rows={18} style={{ width: '100%', fontFamily: 'inherit', marginTop: 8 }} value={body} onChange={e => setBody(e.target.value)} />
          : <pre className="memo">{m.body}</pre>}
      </div>
      <div className="panel">
        <h3>Deviations</h3>
        {devs.length === 0 ? <p className="small muted">No policy deviations: every rule passed or was decided automatically.</p> : devs.map((x, i) => (
          <div key={x.rule} style={{ marginBottom: 10 }}>
            <div><b>{x.rule}</b> · {x.description}</div>
            {d.canEdit
              ? <textarea name={'justify-' + x.rule} rows={2} style={{ width: '100%' }} placeholder="Why this deviation is acceptable (at least 10 characters)" value={x.justification}
                  onChange={e => { const c = [...devs]; c[i] = { ...x, justification: e.target.value }; setDevs(c); setEditing(true) }} />
              : <div className="small">{x.justification || <span className="muted">No justification yet</span>}</div>}
          </div>
        ))}
        {d.canEdit && (
          <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
            {!editing && <button className="btn" onClick={() => setEditing(true)}>Edit memo</button>}
            {editing && <button className="btn" disabled={busy} onClick={save}>Save as new version</button>}
            <button className="btn primary" data-action="signMemo" disabled={busy} onClick={sign}>Sign and lock</button>
          </div>
        )}
      </div>
      <div className="panel flush">
        <Table head={['Version', 'Status', 'Edited by', 'When', 'Signed by']} rows={d.history.map(h => [h.version, <Pill s={h.status} />, h.editedBy, h.editedAt ? when(h.editedAt) : '—', h.signedBy || '—'])} />
      </div>
    </>
  )
}

interface Customer {
  name: string; pan: string; mobile: string; city?: string; segment: string; language?: string; disbursedTotal: number; exposure: number; otherPansOnMobile: string[]
  applications: { applicationId: number; appNo: string; product: string; amount: number; state: string; createdAt: string; decision?: string; band?: string; disbursed: number; samePan: boolean }[]
}

/** Customer 360 (SCR-031): the borrower across applications on the same PAN or mobile number. */
export function CustomerPanel({ id, rev }: { id: string; rev: number }) {
  const c = useLoad(() => api.get<Customer>(`/api/applications/${id}/customer`), [id, rev])
  if (!c.data) return <ErrorBox error={c.error} />
  const d = c.data
  return (
    <>
      <div className="kpis" data-testid="customer360">
        <div className="kpi"><span>Customer</span><b>{d.name}</b><span className="mono">{d.pan} · {d.mobile}</span></div>
        <div className="kpi"><span>Applications</span><b>{d.applications.length}</b></div>
        <div className="kpi"><span>Disbursed so far</span><b>{inr(d.disbursedTotal)}</b></div>
        <div className="kpi"><span>Exposure (open + disbursed)</span><b>{inr(d.exposure)}</b></div>
      </div>
      {d.otherPansOnMobile.length > 0 && <div className="alert warn small" style={{ marginTop: 10 }}>Other PANs use this mobile number: {d.otherPansOnMobile.join(', ')}. The fraud check counts these links.</div>}
      <div className="panel flush" style={{ marginTop: 10 }}>
        <Table head={['Application', 'Product', 'Amount', 'Stage', 'Decision', 'Disbursed', 'Created', 'Match']} rows={d.applications.map(x => [
          <Link to={`/applications/${x.applicationId}`}>{x.appNo}</Link>, x.product, inr(x.amount), <Pill s={x.state} />,
          x.decision ? <span><Pill s={x.decision} />{x.band && <span className="small"> band {x.band}</span>}</span> : '—',
          x.disbursed ? inr(x.disbursed) : '—', when(x.createdAt), x.samePan ? 'Same PAN' : 'Same mobile',
        ])} />
      </div>
    </>
  )
}
