import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, getToken } from '../api'
import { ErrorBox, Head, KpiStrip, Table, useLoad, useTab } from '../components/ui'
import { inr, label, pct, when } from '../format'

// Loans against securities and fixed deposits: the pledge (CAS or deposit receipt read by AI), drawing power, lien, and the
// margin monitor (daily price file, margin calls cured by pledging more or a part-payment, or invoked).

interface Check { id: string; label: string; result: string; reason: string; ruling?: string }
interface Security { isin: string; name: string; category: string; approved: boolean; maxLtv: number; price: number; priceDate: string; updatedBy?: string }
interface Holding { isin: string; name?: string; quantity: number; price?: number; priceDate?: string; category?: string; approved: boolean; maxLtv: number; value: number; drawingPower: number }
interface Call { id: number; pledgeId: number; applicationId: number; outstanding: number; drawingPower: number; shortfall: number; dueBy: string; status: string
  cureNote?: string; closedBy?: string; closedAt?: string; createdAt: string }
interface PledgeRec { id: number; kind: string; holderName?: string; depository?: string; dematId?: string; issuer?: string; depositNo?: string; principal?: number
  ratePa?: number; startDate?: string; maturityDate?: string; lienState: string; lienRef?: string; lienMarkedBy?: string; lienMarkedAt?: string; value?: number
  drawingPower?: number; status: string }
interface View { kind: 'SECURITIES' | 'DEPOSIT'; applicant: string; loan: number; disbursed: boolean; outstanding?: number; pledge?: PledgeRec; checks: Check[]
  readyForSanction: boolean; readyForDisbursement: boolean; holdings: Holding[]; documents: { id: number; docType: string; fileName: string; readConfidence?: number
  engine?: string; uploadedBy: string; uploadedAt: string }[]; marginCalls: Call[]; securities: Security[]; canEdit: boolean; canLien: boolean; canCure: boolean
  canInvoke: boolean; canRule: boolean }

const ST: Record<string, string> = { PASS: 'ok', REFER: 'warn', FAIL: 'bad', PENDING: 'muted', MARKED: 'ok', NONE: 'muted', OPEN: 'bad', CURED: 'ok', INVOKED: 'warn' }
const St = ({ s }: { s?: string }) => <span className={'pill ' + (ST[s ?? ''] ?? 'muted')}>{(s ?? '—').replace(/_/g, ' ').toLowerCase()}</span>
const DOC: Record<string, string> = { CAS_STATEMENT: 'CAS statement (holdings)', FD_RECEIPT: 'Fixed deposit receipt', LIEN_CONFIRMATION: 'Lien / pledge confirmation' }

async function upload(url: string, form: FormData) {
  const t = getToken()
  const r = await fetch(url, { method: 'POST', body: form, headers: t ? { Authorization: 'Bearer ' + t } : {} })
  const b = await r.json().catch(() => ({}))
  if (!r.ok) throw new Error(b.message ?? 'Upload failed')
  return b
}
async function openFile(url: string) {
  const t = getToken()
  const r = await fetch(url, { headers: t ? { Authorization: 'Bearer ' + t } : {} })
  if (r.ok) window.open(URL.createObjectURL(await r.blob()), '_blank')
}

export function PledgeTab({ id, rev, onChange }: { id: string; rev: number; onChange: () => void }) {
  const [r, setR] = useState(0)
  const v = useLoad(() => api.get<View>(`/api/applications/${id}/pledge`), [id, rev, r])
  const [docType, setDocType] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [f, setF] = useState<Record<string, string>>({})
  const [lienRef, setLienRef] = useState('')
  const [top, setTop] = useState({ isin: '', quantity: '', ref: '' })
  const [pay, setPay] = useState({ amount: '', ref: '' })
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState('')
  const [error, setError] = useState<unknown>()
  const [rule, setRule] = useState<{ check: string; note: string } | null>(null)
  useEffect(() => {
    const d = v.data
    if (!d) return
    const p = d.pledge
    setF({ holderName: p?.holderName ?? '', depository: p?.depository ?? '', dematId: p?.dematId ?? '', issuer: p?.issuer ?? '', depositNo: p?.depositNo ?? '',
      principal: p?.principal != null ? String(p.principal) : '', ratePa: p?.ratePa != null ? String(p.ratePa) : '', startDate: p?.startDate ?? '', maturityDate: p?.maturityDate ?? '' })
    setDocType(x => x || (d.kind === 'SECURITIES' ? 'CAS_STATEMENT' : 'FD_RECEIPT'))
  }, [v.data])
  const d = v.data
  if (!d) return v.error ? <ErrorBox error={v.error} /> : <div className="empty">Loading…</div>
  const p = d.pledge
  const run = async (fn: () => Promise<unknown>, m: string) => {
    setBusy(true)
    try { await fn(); setMsg(m); setError(undefined); setR(x => x + 1); onChange() } catch (x) { setError(x) } finally { setBusy(false) }
  }
  const sec = d.kind === 'SECURITIES'
  const open = d.marginCalls.find(c => c.status === 'OPEN')
  const ltv = d.outstanding != null && p?.value ? d.outstanding / p.value : undefined
  const docTypes = sec ? ['CAS_STATEMENT', 'LIEN_CONFIRMATION'] : ['FD_RECEIPT', 'LIEN_CONFIRMATION']
  const txt = (k: string, l: string, type = 'text') => <label className="fld">{l}<input name={k} type={type} value={f[k] ?? ''} disabled={!d.canEdit} onChange={e => setF({ ...f, [k]: e.target.value })} /></label>

  return (
    <div data-pledge>
      <ErrorBox error={error} />
      {msg && <div className="alert ok" role="status">{msg}</div>}
      <KpiStrip items={[
        ['Pledge', sec ? 'Securities' : 'Fixed deposit', <span data-pledge-status={p?.status ?? 'PENDING'}><St s={p?.status ?? 'PENDING'} /></span>],
        ['Value', inr(p?.value ?? 0), sec ? `${d.holdings.length} holding(s)` : p?.issuer],
        ['Drawing power', <span data-dp={p?.drawingPower ?? 0}>{inr(p?.drawingPower ?? 0)}</span>, sec ? 'approved securities at their LTV' : '90% of deposit with interest'],
        [d.disbursed ? 'Outstanding' : 'Loan asked', inr(d.outstanding ?? d.loan), ltv != null ? 'LTV ' + pct(ltv) : undefined],
        ['Lien', <span data-lien={p?.lienState ?? 'NONE'}><St s={p?.lienState ?? 'NONE'} /></span>, p?.lienRef],
        ['Margin call', open ? <span className="bad">{inr(open.shortfall)}</span> : 'none', open ? 'due ' + open.dueBy : undefined, !!open],
      ]} />

      <div className="panel">
        <h3>{sec ? 'Securities pledged' : 'Deposit pledged'}</h3>
        <p className="small muted">{sec ? 'Upload the consolidated account statement (CAS): Document AI reads the holder, depository, BO ID and every holding. Only approved securities count, at their LTV and today’s price.'
          : 'Upload the deposit receipt: Document AI reads the issuer, deposit number, holder, amount, rate and maturity.'}</p>
        <div className="row">
          <select aria-label="Pledge document type" value={docType} onChange={e => setDocType(e.target.value)}>{docTypes.map(t => <option key={t} value={t}>{DOC[t]}</option>)}</select>
          <input type="file" aria-label="Pledge document" accept=".pdf,.jpg,.jpeg,.png" onChange={e => setFile(e.target.files?.[0] ?? null)} />
          <button className="btn" disabled={!file || busy} onClick={() => { const fd = new FormData(); fd.append('file', file!); fd.append('docType', docType); run(() => upload(`/api/applications/${id}/pledge/documents`, fd).then(() => setFile(null)), DOC[docType] + ' uploaded and read') }}>Upload</button>
        </div>
        <div className="grid3" style={{ marginTop: 10 }}>
          {txt('holderName', 'Holder')}
          {sec ? <>{txt('depository', 'Depository (CDSL / NSDL)')}{txt('dematId', 'BO / client ID')}</>
            : <>{txt('issuer', 'Issuer (bank)')}{txt('depositNo', 'Deposit no.')}{txt('principal', 'Amount (₹)')}{txt('ratePa', 'Rate (% a year)')}{txt('startDate', 'Deposit date', 'date')}{txt('maturityDate', 'Maturity', 'date')}</>}
        </div>
        {d.canEdit && <div className="row" style={{ marginTop: 8 }}><button className="btn primary" disabled={busy} onClick={() => run(() => api.put(`/api/applications/${id}/pledge`, {
          ...f, principal: f.principal ? Number(f.principal) : undefined, ratePa: f.ratePa ? Number(f.ratePa) : undefined }), 'Pledge saved and checked')}>Save and check</button></div>}
        {sec && <Table head={['Security', 'Category', 'Quantity', 'Price', 'Value', 'LTV', 'Drawing power']} empty="No holdings yet" rows={d.holdings.map(h => [
          <span data-holding={h.isin}>{h.name ?? h.isin}<br /><span className="small muted">{h.isin}</span>{!h.approved && <span className="pill bad">not approved</span>}</span>,
          label(h.category ?? '—'), String(h.quantity), h.price != null ? `₹${h.price.toLocaleString('en-IN')}` + (h.priceDate ? ` (${h.priceDate})` : '') : '—', inr(h.value), pct(h.maxLtv, 0), inr(h.drawingPower)])} />}
        <Table head={['Document', 'File', 'Read', 'Uploaded']} empty="No documents yet" rows={d.documents.map(x => [DOC[x.docType] ?? x.docType,
          <a href="#" onClick={e => { e.preventDefault(); openFile(`/api/pledge-documents/${x.id}/content`) }}>{x.fileName}</a>,
          x.readConfidence != null ? pct(x.readConfidence, 0) + (x.engine ? ' · ' + x.engine : '') : '—', `${x.uploadedBy} · ${when(x.uploadedAt)}`])} />
      </div>

      {d.checks.length > 0 && <div className="panel">
        <h3>Pledge checks</h3>
        <Table head={['Check', 'Result', 'Why', '']} rows={d.checks.map(c => [<span><b>{c.id}</b> {c.label}</span>,
          <span data-pledge-check={c.id} data-result={c.result}><St s={c.result} />{c.ruling && <span className="small muted"> · cleared</span>}</span>, <span className="small">{c.reason}{c.ruling && <><br /><em>{c.ruling}</em></>}</span>,
          d.canRule && c.result === 'REFER' && !c.ruling ? <button className="btn sm" onClick={() => setRule({ check: c.id, note: '' })}>Approve exception</button> : ''])} />
        {rule && <div className="row" style={{ marginTop: 8 }}>
          <input aria-label="Exception reason" style={{ flex: 1 }} placeholder={`Why ${rule.check} can be accepted (15+ characters)`} value={rule.note} onChange={e => setRule({ ...rule, note: e.target.value })} />
          <button className="btn primary" disabled={rule.note.trim().length < 15 || busy} onClick={() => run(() => api.post(`/api/applications/${id}/pledge/exceptions`, rule).then(() => setRule(null)), 'Exception recorded')}>Record</button>
        </div>}
        {d.canLien && p && p.lienState !== 'MARKED' && d.readyForSanction && <div className="row" style={{ marginTop: 10 }}>
          <input aria-label="Lien reference" placeholder={sec ? 'Pledge sequence no. (PSN) from the depository' : 'Lien reference from the bank'} value={lienRef} onChange={e => setLienRef(e.target.value)} />
          <button className="btn primary" disabled={lienRef.trim().length < 4 || busy} onClick={() => run(() => api.post(`/api/applications/${id}/pledge/lien`, { ref: lienRef }), 'Lien marked')}>Mark lien</button>
          <span className="small muted">or upload the lien confirmation above</span>
        </div>}
      </div>}

      {d.disbursed && sec && <div className="panel" data-margin>
        <h3>Margin</h3>
        <p className="small muted">Every price file revalues the pledge. If the loan is above the drawing power, a margin call gives the borrower 7 days to pledge more or pay down; after that the pledge can be invoked.</p>
        <Table head={['Raised', 'Outstanding', 'Drawing power', 'Shortfall', 'Due', 'Status', 'How it closed']} empty="No margin calls" rows={d.marginCalls.map(c => [
          when(c.createdAt), inr(c.outstanding), inr(c.drawingPower), inr(c.shortfall), c.dueBy, <span data-call-status={c.status}><St s={c.status} /></span>, <span className="small">{c.cureNote ?? '—'}</span>])} />
        {open && d.canCure && <div className="grid2" style={{ marginTop: 10 }}>
          <div className="panel"><h4>Pledge more securities</h4>
            <select aria-label="Security to pledge" value={top.isin} onChange={e => setTop({ ...top, isin: e.target.value })}><option value="">Choose…</option>{d.securities.map(s => <option key={s.isin} value={s.isin}>{s.name} · ₹{s.price} · LTV {pct(s.maxLtv, 0)}</option>)}</select>
            <input aria-label="Quantity to pledge" placeholder="Quantity" value={top.quantity} onChange={e => setTop({ ...top, quantity: e.target.value.replace(/[^0-9.]/g, '') })} />
            <input aria-label="Top-up pledge reference" placeholder="PSN of the new pledge" value={top.ref} onChange={e => setTop({ ...top, ref: e.target.value })} />
            <button className="btn primary" disabled={!top.isin || !Number(top.quantity) || top.ref.trim().length < 4 || busy} onClick={() => run(() => api.post(`/api/applications/${id}/pledge/top-up`, { isin: top.isin, quantity: Number(top.quantity), ref: top.ref }), 'Pledge topped up')}>Add to pledge</button>
          </div>
          <div className="panel"><h4>Part-payment</h4>
            <input aria-label="Part-payment amount" placeholder={`At least ${inr(open.shortfall)}`} value={pay.amount} onChange={e => setPay({ ...pay, amount: e.target.value.replace(/[^0-9.]/g, '') })} />
            <input aria-label="Part-payment reference" placeholder="UTR" value={pay.ref} onChange={e => setPay({ ...pay, ref: e.target.value })} />
            <button className="btn" disabled={!Number(pay.amount) || busy} onClick={() => run(() => api.post(`/api/applications/${id}/pledge/part-payment`, { amount: Number(pay.amount), ref: pay.ref }), 'Part-payment recorded')}>Record payment</button>
          </div>
        </div>}
      </div>}
    </div>
  )
}

// ---------------------------------------------------------------- Pledges & margin (Portfolio)

interface Monitor { pledges: { applicationId: number; appNo: string; borrower: string; product: string; kind: string; value?: number; drawingPower?: number; outstanding?: number
  ltv?: number; lien: string; stage: string; openCall?: Call; maturity?: string }[]; value: number; drawingPower: number; outstanding: number
  calls: { call: Call; appNo?: string; borrower?: string }[]; securities: Security[] }

export function PledgesPage() {
  const [tab, setTab] = useTab(['monitor', 'calls', 'securities'] as const, 'monitor')
  const [r, setR] = useState(0)
  const m = useLoad(() => api.get<Monitor>('/api/pledges'), [r])
  const [csv, setCsv] = useState('')
  const [res, setRes] = useState<{ updated: number; errors: string[]; revaluation: { raised: number; cured: number } } | null>(null)
  const [inv, setInv] = useState<{ id: number; note: string } | null>(null)
  const [sec, setSec] = useState<Record<string, string> | null>(null)
  const [error, setError] = useState<unknown>()
  const d = m.data
  const openCalls = d?.calls.filter(c => c.call.status === 'OPEN') ?? []
  return (
    <>
      <Head title="Pledges & margin" sub="Loans against securities and fixed deposits: value, drawing power and LTV after each price file, margin calls, and the approved securities." />
      <ErrorBox error={error ?? m.error} />
      {d && <KpiStrip items={[['Pledged value', inr(d.value)], ['Drawing power', inr(d.drawingPower)], ['Outstanding', inr(d.outstanding), d.value ? 'LTV ' + pct(d.outstanding / d.value) : undefined],
        ['Open margin calls', String(openCalls.length), openCalls.length ? inr(openCalls.reduce((s, c) => s + c.call.shortfall, 0)) + ' short' : undefined, openCalls.length > 0]]} />}
      <div className="tabs" role="tablist">
        {([['monitor', 'Monitor'], ['calls', 'Margin calls'], ['securities', 'Securities & prices']] as const).map(([k, l]) =>
          <button key={k} role="tab" aria-selected={tab === k} className={tab === k ? 'on' : ''} onClick={() => setTab(k)}>{l}</button>)}
      </div>
      {d && tab === 'monitor' && <Table head={['Borrower', 'Pledge', 'Value', 'Drawing power', 'Outstanding', 'LTV', 'Lien', 'Margin']} empty="No pledges yet" rows={d.pledges.map(x => [
        <Link to={`/applications/${x.applicationId}?tab=Pledge`}>{x.borrower}<br /><span className="small muted">{x.appNo} · {x.product}</span></Link>, x.kind === 'SECURITIES' ? 'Securities' : 'Fixed deposit' + (x.maturity ? ` (to ${x.maturity})` : ''),
        inr(x.value ?? 0), inr(x.drawingPower ?? 0), x.outstanding != null ? inr(x.outstanding) : '—', x.ltv != null ? pct(x.ltv) : '—', <St s={x.lien} />,
        x.openCall ? <span className="pill bad">short {inr(x.openCall.shortfall)}</span> : '—'])} />}
      {d && tab === 'calls' && <>
        <Table head={['Borrower', 'Raised', 'Shortfall', 'Due', 'Status', 'Note', '']} empty="No margin calls" rows={d.calls.map(({ call: c, appNo, borrower }) => [
          <Link to={`/applications/${c.applicationId}?tab=Pledge`}>{borrower}<br /><span className="small muted">{appNo}</span></Link>, when(c.createdAt), inr(c.shortfall), c.dueBy, <St s={c.status} />,
          <span className="small">{c.cureNote ?? '—'}</span>, c.status === 'OPEN' ? <button className="btn sm danger" onClick={() => setInv({ id: c.id, note: '' })}>Invoke</button> : ''])} />
        {inv && <div className="row" style={{ marginTop: 8 }}>
          <input aria-label="Invocation note" style={{ flex: 1 }} placeholder="Notice given and sale details (15+ characters)" value={inv.note} onChange={e => setInv({ ...inv, note: e.target.value })} />
          <button className="btn danger" disabled={inv.note.trim().length < 15} onClick={async () => { try { await api.post(`/api/margin-calls/${inv.id}/invoke`, { note: inv.note }); setInv(null); setR(x => x + 1) } catch (x) { setError(x) } }}>Invoke the pledge</button>
        </div>}
      </>}
      {d && tab === 'securities' && <>
        <div className="panel">
          <h3>Daily price file</h3>
          <p className="small muted">One line per security: ISIN,price. Every pledge is revalued; margin calls are raised or cured.</p>
          <textarea aria-label="Price file" name="prices" rows={5} style={{ width: '100%' }} value={csv} onChange={e => setCsv(e.target.value)} placeholder={'INE0DM101015,1180.50\nINE0DM201013,842.00'} />
          <button className="btn primary" disabled={!csv.trim()} onClick={async () => { try { setRes(await api.post('/api/securities/prices', { csv })); setError(undefined); setR(x => x + 1) } catch (x) { setError(x) } }}>Load prices and revalue</button>
          {res && <p className="small" data-revaluation>{res.updated} price(s) updated · {res.revaluation.raised} margin call(s) raised · {res.revaluation.cured} cured{res.errors.length ? ' · ' + res.errors.join('; ') : ''}</p>}
        </div>
        <div className="row" style={{ margin: '10px 0' }}><button className="btn" onClick={() => setSec({ isin: '', name: '', category: 'EQUITY', approved: 'true', maxLtv: '0.50', price: '' })}>Add security</button></div>
        {sec && <div className="panel">
          <div className="grid3">
            {(['isin', 'name', 'maxLtv', 'price'] as const).map(k => <label key={k} className="fld">{({ isin: 'ISIN', name: 'Name', maxLtv: 'LTV (0–1)', price: 'Price (₹)' })[k]}<input name={k} value={sec[k]} onChange={e => setSec({ ...sec, [k]: e.target.value })} /></label>)}
            <label className="fld">Category<select value={sec.category} onChange={e => setSec({ ...sec, category: e.target.value })}>{['EQUITY', 'EQUITY_MF', 'DEBT_MF', 'BOND'].map(c => <option key={c}>{c}</option>)}</select></label>
            <label className="fld">Approved<select value={sec.approved} onChange={e => setSec({ ...sec, approved: e.target.value })}><option>true</option><option>false</option></select></label>
          </div>
          <div className="row"><button className="btn primary" onClick={async () => { try { await api.put('/api/securities', { ...sec, approved: sec.approved === 'true', maxLtv: Number(sec.maxLtv), price: Number(sec.price) }); setSec(null); setR(x => x + 1) } catch (x) { setError(x) } }}>Save</button>
            <button className="btn" onClick={() => setSec(null)}>Cancel</button></div>
        </div>}
        <Table head={['Security', 'Category', 'LTV', 'Price', 'As of', 'Approved', '']} rows={d.securities.map(s => [<span>{s.name}<br /><span className="small muted">{s.isin}</span></span>, label(s.category), pct(s.maxLtv, 0),
          `₹${Number(s.price).toLocaleString('en-IN')}`, s.priceDate, s.approved ? 'yes' : <span className="pill bad">no</span>,
          <button className="btn sm" onClick={() => setSec({ isin: s.isin, name: s.name, category: s.category, approved: String(s.approved), maxLtv: String(s.maxLtv), price: String(s.price) })}>Edit</button>])} />
      </>}
    </>
  )
}
