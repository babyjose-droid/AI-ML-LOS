import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'
import { useAuth } from '../auth'
import { ErrorBox, Head, Pill, Table, useLoad } from '../components/ui'
import { inr, label, when } from '../format'
import type { Product, ProductVersionView } from '../types'

// Product builder (FSD 20.5): six tabs, saved as a draft of the next version, sent for approval and published by the
// CRO (not the maker). Applications keep the product version they were created on.

const TABS = ['Basics', 'Eligibility', 'Loan terms', 'Fees & charges', 'Documents', 'Credit'] as const
type Tab = typeof TABS[number]
type F = Record<string, string | number | boolean | null | undefined>

const NEW: F = {
  code: '', name: '', segment: 'MSME', secured: false, status: 'LIVE', minAmount: 50000, maxAmount: 500000, minTenure: 6, maxTenure: 36,
  minAge: 21, maxAge: 65, processingFeePct: 2, rateMin: 13, rateMax: 26, fieldVisitRule: 'NEVER', requiredDocs: 'PAN,AADHAAR',
  videoKycRule: 'ON_KYC_REVIEW', bankDataSource: 'AA_OR_STATEMENT', repaymentFrequency: 'MONTHLY', moratoriumMonths: 0, scoringModel: 'AUTO',
}

export default function Products() {
  const { user } = useAuth()
  const [rev, setRev] = useState(0)
  const list = useLoad(() => api.get<Product[]>('/api/products'), [rev])
  const [sel, setSel] = useState<string | 'new' | null>(null)
  const products = list.data ?? []
  const code = sel ?? products[0]?.code ?? null
  return (
    <>
      <Head title="Products" sub="A product ties together terms, fees, documents, the credit policy, the scoring model and a workflow. Changes are saved as a draft, approved by the CRO and apply to new applications only.">
        {user?.role === 'ADMIN' && <button className="btn primary" data-product="new" onClick={() => setSel('new')}>New product</button>}
      </Head>
      <ErrorBox error={list.error} />
      <div className="panel flush">
        <Table head={['Product', 'Category', 'Amount', 'Tenure', 'Rate band', 'Workflow', 'Status']} onRow={i => setSel(products[i].code)} rows={products.map(p => [
          <span><b className={p.code === code ? '' : 'muted'}>{p.name}</b><div className="small muted">{p.code} · v{p.version}</div></span>,
          label(p.segment), <span className="num">{inr(p.minAmount)} – {inr(p.maxAmount)}</span>, `${p.minTenure}–${p.maxTenure} m`,
          `${p.rateMin}–${p.rateMax}%`, <Link to={`/admin/workflows?product=${p.code}`} data-product-workflow={p.code} onClick={e => e.stopPropagation()}>stages</Link>,
          <Pill s={p.status === 'LIVE' ? 'SUCCESS' : p.status} />,
        ])} />
      </div>
      {code && <ProductEditor key={code + ':' + rev} code={code} product={code === 'new' ? null : products.find(p => p.code === code) ?? null}
        onChange={c => { setRev(r => r + 1); if (c) setSel(c) }} />}
    </>
  )
}

function ProductEditor({ code, product, onChange }: { code: string; product: Product | null; onChange: (c?: string) => void }) {
  const { user } = useAuth()
  const isNew = code === 'new'
  const versions = useLoad(() => isNew ? Promise.resolve([] as ProductVersionView[]) : api.get<ProductVersionView[]>(`/api/products/${code}/versions`), [code])
  const docTypes = useLoad(() => api.get<string[]>('/api/products/document-types'), [])
  const open = (versions.data ?? []).find(v => ['DRAFT', 'PENDING_APPROVAL'].includes(v.status))
  const [f, setF] = useState<F>({ ...NEW })
  const [tab, setTab] = useState<Tab>('Basics')
  const [notes, setNotes] = useState('')
  const [note, setNote] = useState('')
  const [error, setError] = useState<unknown>()
  const [msg, setMsg] = useState<string>()
  useEffect(() => {
    const base = open?.definition ?? product
    if (base) setF({ ...(base as unknown as F) })
  }, [open?.id, product?.code, versions.data])

  const isAdmin = user?.role === 'ADMIN'
  const pending = open?.status === 'PENDING_APPROVAL'
  const editable = isAdmin && !pending
  const canDecide = pending && user?.role === 'CRO' && user.username !== open?.createdBy
  const set = (k: string, v: string | number | boolean | null) => setF(x => ({ ...x, [k]: v }))
  const num = (k: string, t: string, step = 1) => (
    <label className="fld" key={k}>{t}<input name={'p_' + k} type="number" step={step} disabled={!editable} value={f[k] == null ? '' : String(f[k])}
      onChange={e => set(k, e.target.value === '' ? null : Number(e.target.value))} /></label>)
  const pick = (k: string, t: string, opts: [string, string][]) => (
    <label className="fld" key={k}>{t}<select name={'p_' + k} disabled={!editable} value={String(f[k] ?? '')} onChange={e => set(k, e.target.value)}>
      {opts.map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select></label>)

  async function save(): Promise<boolean> {
    setError(undefined); setMsg(undefined)
    try {
      if (isNew) { await api.post('/api/admin/products', f); setMsg('Draft v1 saved'); onChange(String(f.code)) }
      else { const v = await api.put<ProductVersionView>('/api/admin/products/' + code, f); setMsg(`Draft v${v.version} saved`); versions.reload() }
      return true
    } catch (e) { setError(e); return false }
  }
  async function submit() {
    if (!(await save())) return
    try { await api.post(`/api/admin/products/${isNew ? f.code : code}/submit`, { notes }); setMsg('Sent to the CRO for approval'); onChange(isNew ? String(f.code) : undefined); versions.reload() } catch (e) { setError(e) }
  }
  async function decide(approve: boolean) {
    try { await api.post(`/api/products/${code}/decision`, { approve, note }); onChange() } catch (e) { setError(e) }
  }
  async function discard() {
    try { await api.del(`/api/admin/products/${code}/draft`); onChange() } catch (e) { setError(e) }
  }

  const sections: Record<Tab, JSX.Element> = {
    'Basics': <div className="form-grid">
      <label className="fld">Product code<input name="p_code" value={String(f.code ?? '')} disabled={!isNew} onChange={e => set('code', e.target.value.toUpperCase())} /></label>
      <label className="fld">Product name<input name="p_name" value={String(f.name ?? '')} disabled={!editable} onChange={e => set('name', e.target.value)} /></label>
      {pick('segment', 'Category', [['MSME', 'MSME'], ['SALARIED', 'Salaried'], ['MICROFINANCE', 'Microfinance']])}
      {pick('secured', 'Secured', [['false', 'Unsecured'], ['true', 'Secured']])}
      {pick('status', 'Status', [['DRAFT', 'Draft'], ['LIVE', 'Live'], ['PAUSED', 'Paused'], ['RETIRED', 'Retired']])}
      <label className="fld">Version<input disabled value={`v${open?.version ?? product?.version ?? 1}${open ? ' (' + label(open.status) + ')' : ''}`} /></label>
    </div>,
    'Eligibility': <><div className="form-grid">
      {num('minAge', 'Minimum age')}{num('maxAge', 'Maximum age at maturity')}
      {num('minVintageYears', 'Minimum business / job vintage (years)', 0.5)}{num('minMonthlyIncome', 'Minimum monthly income (₹)', 1000)}
    </div><p className="small muted">Checked by the decision engine before scoring. Branch availability is set on each branch in Branch hierarchy; credit cut-offs live in the credit policy.</p></>,
    'Loan terms': <><div className="form-grid">
      {num('minAmount', 'Minimum amount (₹)', 5000)}{num('maxAmount', 'Maximum amount (₹)', 5000)}
      {pick('repaymentFrequency', 'Repayment frequency', [['MONTHLY', 'Monthly'], ['FORTNIGHTLY', 'Fortnightly'], ['WEEKLY', 'Weekly'], ['BULLET', 'Bullet']])}
      {num('minTenure', 'Minimum tenure (months)')}{num('maxTenure', 'Maximum tenure (months)')}{num('moratoriumMonths', 'Moratorium (months)')}
      {num('rateMin', 'Rate floor (% p.a.)', 0.25)}{num('rateMax', 'Rate ceiling (% p.a.)', 0.25)}
    </div><p className="small muted">The pricing engine picks a rate inside this band from the risk band; it can never go outside it.</p></>,
    'Fees & charges': <><div className="form-grid">
      {num('processingFeePct', 'Processing fee (%)', 0.25)}{num('minProcessingFee', 'Minimum processing fee (₹)', 250)}
      {num('foreclosurePct', 'Foreclosure charge (%)', 0.5)}{num('bounceCharge', 'Bounce charge (₹)', 50)}
      {num('penalRatePct', 'Penal charge (% p.a. on overdue)', 0.5)}
      <label className="fld">GST on fees<input disabled value="18% (from tax master)" /></label>
    </div><p className="small muted">All charges flow into the Key Fact Statement; the processing fee and GST into the APR.</p></>,
    'Documents': <DocGrid f={f} types={docTypes.data ?? []} editable={editable} onChange={(req, opt) => setF(x => ({ ...x, requiredDocs: req, optionalDocs: opt }))} />,
    'Credit': <div className="form-grid">
      {pick('scoringModel', 'Scoring model', [['AUTO', 'RHY-PD-THICK if bureau record, else RHY-PD-NTC']])}
      <label className="fld">Credit policy version<input disabled value="Live policy (Credit policy studio)" /></label>
      {pick('fieldVisitRule', 'Field visit rule', [['ALWAYS', 'Always'], ['ABOVE_AMOUNT', 'Above an amount'], ['NEVER', 'Never']])}
      {f.fieldVisitRule === 'ABOVE_AMOUNT' && num('fieldVisitThreshold', 'Field visit above (₹)', 50000)}
      {pick('videoKycRule', 'Video KYC', [['ON_KYC_REVIEW', 'When KYC needs review'], ['ALWAYS', 'Always'], ['ABOVE_AMOUNT', 'Above an amount']])}
      {f.videoKycRule === 'ABOVE_AMOUNT' && num('videoKycThreshold', 'Video KYC above (₹)', 50000)}
      {pick('bankDataSource', 'Bank data', [['AA_OR_STATEMENT', 'AA or bank statement'], ['STATEMENT_ONLY', 'Bank statement only'], ['AA_ONLY', 'AA only']])}
      {num('autoSanctionLimit', 'Auto-sanction up to (₹, empty = never)', 5000)}
      <label className="fld">Delegation matrix<input disabled value="DOP-2026 (board-approved)" /></label>
      <label className="fld">Workflow<input disabled value={isNew ? 'Default stages until published' : `See Workflow designer (${code})`} /></label>
    </div>,
  }

  return (
    <div className="panel" data-product-editor={code}>
      <div className="row" style={{ justifyContent: 'space-between' }}>
        <h3 style={{ margin: 0 }}>{isNew ? 'New product' : String(f.name ?? code)} {open && <Pill s={open.status} />}</h3>
        {!isNew && <span className="small muted">Live v{product?.version}{open ? ` · draft v${open.version} by ${open.createdBy} ${when(open.createdAt)}` : ''}</span>}
      </div>
      <ErrorBox error={error} />
      {msg && <div className="alert ok" role="status">{msg}</div>}
      <div className="tabs" role="tablist" style={{ marginTop: 12 }}>
        {TABS.map(t => <button key={t} role="tab" aria-selected={tab === t} className={tab === t ? 'on' : ''} onClick={() => setTab(t)}>{t}</button>)}
      </div>
      {sections[tab]}
      {open?.changes && open.changes.length > 0 && <div className="small" style={{ marginTop: 12 }} data-product-changes>
        <b>Changes from live v{product?.version}:</b><ul style={{ margin: '4px 0', paddingLeft: 18 }}>{open.changes.map(c => <li key={c}>{c}</li>)}</ul></div>}
      {editable && (
        <div style={{ marginTop: 12 }}>
          <label className="fld">What changed and why (sent to the CRO)<textarea name="productNotes" rows={2} value={notes} onChange={e => setNotes(e.target.value)} /></label>
          <div className="row" style={{ marginTop: 8 }}>
            <button className="btn" data-product="save" onClick={save}>Save draft</button>
            <button className="btn primary" data-product="submit" onClick={submit}>Submit for approval</button>
            {open?.status === 'DRAFT' && <button className="btn danger" onClick={discard}>Delete draft</button>}
            <span className="small muted">Live applications keep the version they started on.</span>
          </div>
        </div>
      )}
      {pending && !canDecide && <p className="small muted">Version {open?.version} is waiting for the CRO: {open?.notes}</p>}
      {canDecide && (
        <div style={{ marginTop: 12 }}>
          <p className="small">Change described as: {open?.notes}</p>
          <label className="fld">Note (required)<textarea name="productDecisionNote" rows={2} value={note} onChange={e => setNote(e.target.value)} /></label>
          <div className="row" style={{ marginTop: 8 }}>
            <button className="btn primary" data-product="approve" onClick={() => decide(true)}>Approve and publish</button>
            <button className="btn danger" data-product="reject" onClick={() => decide(false)}>Reject</button>
          </div>
        </div>
      )}
      {!isNew && (versions.data ?? []).length > 0 && (
        <div style={{ marginTop: 16 }}>
          <Table head={['Version', 'Status', 'Made by', 'Approved by', 'Change']} rows={(versions.data ?? []).map(v => [
            `v${v.version}`, <Pill s={v.status} />, <span className="small">{v.createdBy} {when(v.createdAt)}</span>,
            <span className="small">{v.approvedBy ? `${v.approvedBy} ${when(v.approvedAt)}` : '—'}</span>, <span className="small">{v.notes ?? ''}</span>,
          ])} />
        </div>
      )}
    </div>
  )
}

/** Mandatory / optional / not required per document type. "A|B" in the mandatory list means "any one of A or B". */
function DocGrid({ f, types, editable, onChange }: { f: F; types: string[]; editable: boolean; onChange: (req: string, opt: string) => void }) {
  const groups = String(f.requiredDocs ?? '').split(',').map(x => x.trim()).filter(Boolean)
  const optional = String(f.optionalDocs ?? '').split(',').map(x => x.trim()).filter(Boolean)
  const inGroup = new Set(groups.flatMap(g => g.split('|')))
  const rows: { key: string; text: string; value: string }[] = [
    ...groups.map(g => ({ key: g, text: g.split('|').map(label).join(' or '), value: 'M' })),
    ...types.filter(t => !inGroup.has(t)).map(t => ({ key: t, text: label(t), value: optional.includes(t) ? 'O' : 'N' })),
  ]
  function change(key: string, v: string) {
    let req = groups.filter(g => g !== key)
    let opt = optional.filter(o => o !== key)
    const parts = key.split('|')
    if (v === 'M') req = [...req, key]
    if (v === 'O') opt = [...opt, ...parts]
    onChange(req.join(','), opt.join(','))
  }
  return (
    <>
      <Table head={['Document', 'Requirement']} rows={rows.map(r => [r.text,
        <select name={'pd_' + r.key} disabled={!editable} value={r.value} onChange={e => change(r.key, e.target.value)}>
          <option value="M">Mandatory</option><option value="O">Optional</option><option value="N">Not required</option></select>])} />
      <p className="small muted">Mandatory documents block underwriting until verified; optional ones are asked for but never block.</p>
    </>
  )
}
