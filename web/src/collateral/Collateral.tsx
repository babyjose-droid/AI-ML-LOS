import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, getToken } from '../api'
import { useAuth } from '../auth'
import { ErrorBox, Head, Modal, Table, useLoad, useTab } from '../components/ui'
import { inr, label, pct, when } from '../format'

// ---------------------------------------------------------------- types

interface Row { id: number; colNo: string; type: string; description?: string; applicationId: number; appNo: string; applicant: string; productCode: string; value: number; requested: number; ltv?: number; maxExposure?: number; ltvCap: boolean; riskScore?: number; completeness: number; status: string; openReviews: number }
interface Hit { id: string; check: string; output: string; passed: boolean; reason: string; evidence: string[]; ruling?: string }
interface Dim { name: string; weight: number; score: number; evidence: string }
interface Review { id: number; collateralId: number; ruleId: string; type: string; role: string; reason: string; slaHours: number; dueAt: string; status: string; reviewer?: string; note?: string; createdAt: string; closedAt?: string }
interface Evaluation {
  id: number; status: string; riskScore: number; risk: Dim[]; rules: Hit[]; summary: string; policyVersion?: string; modelVersion: string; evaluatedAt: string; evaluatedBy: string
  ltv: { valueUsed: number; haircut: number; eligibleValue: number; maxLtv: number; maxExposure: number; requested: number; recommended: number; ltvRequested?: number; ltvRecommended?: number; ltvCap: boolean }
  ec: { present: boolean; docId?: number; periodFrom?: string; periodTo?: string; years?: number; requiredYears?: number; current?: boolean; transactions?: Tx[]; openCharges?: Tx[]; releasedCharges?: (Tx & { releasedBy: string; releasedOn: string })[]; latestOwner?: string }
  title: { date?: string; year?: number; instrument: string; fromOwner?: string; owner: string; evidence: string; flag?: string; note?: string }[]
  vision: { photos?: number; coverage?: number; maxDistanceM?: number; poorQuality?: number; reused?: number; missingLabels?: string[] }
  valuation: { reports: number; valueUsed: number; highest: number; variance: number; newestReport?: string; ageMonths: number; realisable: number; distress: number; aiValue?: number; aiConfidence?: number; aiDeviation?: number }
  conditions: string[]
}
interface Tx { date: string; type: string; docNo: string; from?: string; to?: string; linkedRef?: string }
interface Col { id: number; colNo: string; applicationId: number; type: string; description?: string; ownerName?: string; address?: string; pincode?: string; lat?: number; lng?: number; surveyNo?: string; municipalId?: string; areaSqft?: number; condition?: string; constructionStage?: string; occupancy?: string; insurer?: string; insuranceExpiry?: string; insuranceAssigned: boolean; status: string; riskScore?: number; maxExposure?: number; ltvCap: boolean; lienState?: string; purpose?: string; substituteFor?: number }
interface Doc { document: { id: number; docType: string; version: number; fileName: string; readConfidence?: number; engine?: string; confirmedBy?: string; uploadedBy: string; uploadedAt: string; manipulated: boolean }; extracted: { fields?: Record<string, unknown>; confidence?: Record<string, number>; transactions?: Tx[] }; legalFlag: boolean }
interface Media { id: number; label: string; lat?: number; lng?: number; distanceM?: number; capturedAt?: string; quality?: string; reuseMatch?: string; tamperScore?: number; manipulated: boolean; uploadedBy: string }
interface Valuation { id: number; source: string; valuer?: string; reportDate: string; marketValue: number; realisableValue?: number; distressValue?: number; method?: string; approved: boolean; detailsJson?: string }
interface Policy { ltvSlabs?: string; type: string; label: string; ecYears: number; haircut: number; maxLtv: number; valuationMaxAgeMonths: number; secondValuationAbove?: number; varianceLimit: number; minImages: number; gpsRadiusM: number; coverageMin: number; revalueMonths: number; requiredDocs: string; optionalDocs?: string; requiredPhotos?: string; updatedBy: string }
interface Unified { credit: string; collateral: string; decision: string; recommendedAmount?: number; conditions: string[]; note?: string; collaterals: { colNo: string; type: string; status: string; riskScore: number; maxExposure: number }[] }
interface Detail { collateral: Col; summary: Row; policy: Policy; documents: Doc[]; media: Media[]; valuations: Valuation[]; reviews: Review[]; evaluation?: Evaluation; unified: Unified; avm?: Avm }
export interface Avm { value: number; ratePerSqft: number; low: number; high: number; confidence: number; comparables: number; basis: string
  used: { id: number; locality?: string; distanceKm: number; monthsAgo: number; areaSqft: number; price: number; ratePerSqft: number; adjustedRate: number; weight: number }[] }

const ST: Record<string, string> = { PASS: 'ok', REFER: 'warn', FAIL: 'bad', PENDING: 'muted', OPEN: 'warn', CLEARED: 'ok', UPHELD: 'bad', SUPERSEDED: 'muted', APPROVE_WITH_CONDITIONS: 'ok', REJECT: 'bad' }
const TYPES = ['RESIDENTIAL', 'SHOP', 'PLOT', 'VEHICLE', 'GOLD']
const PROPERTY_DOCS = ['SALE_DEED', 'MOTHER_DEED', 'EC', 'KHATA', 'PROPERTY_TAX', 'APPROVED_PLAN', 'OC', 'LAND_RECORD', 'CONVERSION_ORDER', 'POA', 'PARTITION_DEED', 'RELEASE_DEED', 'VALUATION_REPORT', 'INSURANCE']
const VEHICLE_DOCS = ['RC', 'INVOICE', 'PERMIT', 'FITNESS', 'NOC', 'VALUATION_REPORT', 'INSURANCE']
/** Upload choices for a collateral type: property papers or vehicle papers, never both. */
const docsFor = (type: string) => type === 'VEHICLE' ? VEHICLE_DOCS : type === 'GOLD' ? ['VALUATION_REPORT', 'INSURANCE'] : PROPERTY_DOCS
const ACRONYM: Record<string, string> = { EC: 'Encumbrance certificate (EC)', OC: 'Occupancy certificate (OC)', POA: 'Power of attorney', RC: 'Registration certificate (RC)', NOC: 'NOC' }
const docLabel = (x: string) => ACRONYM[x] ?? label(x)
const St = ({ s }: { s?: string }) => <span className={'pill ' + (ST[s ?? ''] ?? 'muted')}>{(s ?? '—').replace(/_/g, ' ').toLowerCase()}</span>

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
  if (!r.ok) return
  const u = URL.createObjectURL(await r.blob())
  window.open(u, '_blank')
}

// ---------------------------------------------------------------- SCR-140 overview, SCR-147 reviews

/** Collateral AI overview (SCR-140), review queue (SCR-147), monitoring and policy. */
export default function CollateralOverview() {
  const { user } = useAuth()
  const reviewer = user && ['LEGAL_REVIEWER', 'VALUER', 'FIELD_OFFICER'].includes(user.role)
  const [tab, setTab] = useTab(['cases', 'reviews', 'monitoring', 'policy'] as const, reviewer ? 'reviews' : 'cases')
  return (
    <>
      <Head title="Collateral AI" sub="Property, vehicle and gold collateral: documents, EC and title, photos, valuation and LTV, risk and the collateral rules. AI flags evidence; legal reviewers, valuers and field officers clear it with a reason." />
      <div className="tabs" role="tablist">
        {([['cases', 'All cases'], ['reviews', 'Review queue'], ['monitoring', 'Monitoring'], ['policy', 'Policy and rates']] as const)
          .filter(([k]) => !(reviewer && (k === 'monitoring' || k === 'policy'))).map(([k, l]) => <button key={k} role="tab" aria-selected={tab === k} className={tab === k ? 'on' : ''} onClick={() => setTab(k)}>{l}</button>)}
      </div>
      {tab === 'cases' && <Cases />}
      {tab === 'reviews' && <ReviewQueue />}
      {tab === 'monitoring' && <Monitoring />}
      {tab === 'policy' && <PolicyView />}
    </>
  )
}

function Cases() {
  const [status, setStatus] = useState('')
  const [type, setType] = useState('')
  const o = useLoad(() => api.get<{ collaterals: Row[]; byStatus: Record<string, number>; totalValue: number; openReviews: number; aiConfigured: boolean }>(`/api/collaterals?status=${status}&type=${type}`), [status, type])
  const nav = useNavigate()
  return (
    <>
      <ErrorBox error={o.error} />
      {o.data && <div className="kpis" data-testid="collateral-kpis">
        <div className="kpi"><span>Cases</span><b>{o.data.collaterals.length}</b></div>
        <div className="kpi"><span>Pass / refer / fail</span><b>{o.data.byStatus.PASS ?? 0} / {o.data.byStatus.REFER ?? 0} / {o.data.byStatus.FAIL ?? 0}</b><span>{o.data.byStatus.PENDING ?? 0} pending</span></div>
        <div className="kpi"><span>Value (lower of valuations)</span><b>{inr(o.data.totalValue)}</b></div>
        <div className="kpi"><span>Open reviews</span><b>{o.data.openReviews}</b><span>{o.data.aiConfigured ? 'Document AI on' : 'Document AI off: fields typed and confirmed'}</span></div>
      </div>}
      <div className="row" style={{ margin: '12px 0' }}>
        <select value={status} onChange={e => setStatus(e.target.value)} aria-label="Status"><option value="">All statuses</option>{['PENDING', 'PASS', 'REFER', 'FAIL'].map(s => <option key={s}>{s}</option>)}</select>
        <select value={type} onChange={e => setType(e.target.value)} aria-label="Type"><option value="">All types</option>{TYPES.map(s => <option key={s}>{s}</option>)}</select>
      </div>
      <div className="panel flush">
        <Table head={['Case', 'Type', 'Applicant', 'Value', 'LTV', 'Max exposure', 'Risk', 'Documents', 'Reviews', 'Status']} onRow={i => nav(`/collateral/${o.data!.collaterals[i].id}`)}
          rows={(o.data?.collaterals ?? []).map(c => [<span className="mono">{c.colNo}</span>, label(c.type), <span>{c.applicant}<div className="small muted">{c.appNo} · {c.productCode}</div></span>,
            c.value ? inr(c.value) : '—', c.ltv == null ? '—' : pct(c.ltv, 0), c.maxExposure == null ? '—' : <span>{inr(c.maxExposure)}{c.ltvCap && <span className="pill warn" style={{ marginLeft: 4 }}>LTV cap</span>}</span>,
            c.riskScore ?? '—', pct(c.completeness, 0), c.openReviews || '—', <St s={c.status} />])} empty={o.loading ? 'Loading…' : 'No collateral yet. Declare it on a secured application (Collateral tab).'} />
      </div>
    </>
  )
}

export function ReviewQueue() {
  const [r, setR] = useState(0)
  const q = useLoad(() => api.get<{ review: Review; overdue: boolean; collateralId: number; colNo: string; type: string; appNo: string; applicationId: number; applicant: string }[]>('/api/collateral-reviews'), [r])
  const [open, setOpen] = useState<Review | null>(null)
  return (
    <>
      <p className="small muted">Open reviews for your role, oldest due first. Each needs a reason: <b>Cleared</b> accepts the flag (for example the release letter is seen); <b>Upheld</b> confirms the problem and fails the collateral.</p>
      <ErrorBox error={q.error} />
      <div className="panel flush" data-testid="collateral-reviews">
        <Table head={['Due', 'Case', 'Rule', 'Queue', 'What to check', '']} rows={(q.data ?? []).map(x => [
          <span className={x.overdue ? 'bad-text' : ''}>{when(x.review.dueAt)}{x.overdue && ' (overdue)'}</span>,
          <Link to={`/collateral/${x.collateralId}`}>{x.colNo}</Link>, x.review.ruleId, label(x.review.type), <span className="small">{x.review.reason}<div className="muted">{x.applicant} · {x.appNo}</div></span>,
          <button className="btn sm primary" onClick={() => setOpen(x.review)}>Resolve</button>])} empty={q.loading ? 'Loading…' : 'Nothing waiting for you.'} />
      </div>
      {open && <ResolveDialog review={open} onClose={() => setOpen(null)} onDone={() => { setOpen(null); setR(x => x + 1) }} />}
    </>
  )
}

function ResolveDialog({ review, onClose, onDone }: { review: Review; onClose: () => void; onDone: () => void }) {
  const [note, setNote] = useState('')
  const [error, setError] = useState<unknown>()
  async function go(outcome: string) {
    setError(undefined)
    try { await api.post(`/api/collateral-reviews/${review.id}/resolve`, { outcome, note }); onDone() } catch (e) { setError(e) }
  }
  return (
    <Modal title={`${review.ruleId} · ${label(review.type)} review`} onClose={onClose}>
      <p className="small">{review.reason}</p>
      <label>Reason (what you checked, at least 15 characters)<textarea name="reviewNote" rows={3} value={note} onChange={e => setNote(e.target.value)} /></label>
      <ErrorBox error={error} />
      <div className="row">
        <button className="btn primary" data-action="clearReview" onClick={() => go('CLEARED')}>Cleared</button>
        <button className="btn" onClick={() => go('UPHELD')}>Upheld (problem confirmed)</button>
      </div>
    </Modal>
  )
}

function Monitoring() {
  const a = useLoad(() => api.get<{ collateralId: number; colNo: string; appNo: string; type: string; alert: string; detail: string }[]>('/api/collateral-alerts'), [])
  return (
    <>
      <p className="small muted">For disbursed loans: revaluation due (yearly for property), insurance expiring within 30 days, and LTV breaches against the outstanding from the loan book.</p>
      <ErrorBox error={a.error} />
      <div className="panel flush">
        <Table head={['Case', 'Application', 'Type', 'Alert', 'Detail']} rows={(a.data ?? []).map(x => [<Link to={`/collateral/${x.collateralId}`}>{x.colNo}</Link>, x.appNo, label(x.type),
          <span className={'pill ' + (x.alert === 'LTV_BREACH' || x.alert === 'INSURANCE_EXPIRED' ? 'bad' : 'warn')}>{label(x.alert)}</span>, x.detail])} empty={a.loading ? 'Loading…' : 'No alerts.'} />
      </div>
    </>
  )
}

function PolicyView() {
  const { user } = useAuth()
  const [r, setR] = useState(0)
  const p = useLoad(() => api.get<Policy[]>('/api/collateral-policy'), [r])
  const rates = useLoad(() => api.get<{ gold: { rateDate: string; rate22k: number; source: string }[]; goldRateUsed: number; locality: { pinPrefix: string; locality: string; type: string; ratePerSqft: number; source: string }[] }>('/api/collateral-rates'), [r])
  const [edit, setEdit] = useState<Policy | null>(null)
  const [gold, setGold] = useState('')
  const [error, setError] = useState<unknown>()
  const canEdit = user && ['ADMIN', 'CRO'].includes(user.role)
  async function saveGold() {
    setError(undefined)
    try { await api.post('/api/collateral-rates/gold', { rate22k: Number(gold) }); setGold(''); setR(x => x + 1) } catch (e) { setError(e) }
  }
  return (
    <>
      <ErrorBox error={error ?? p.error} />
      <div className="panel flush">
        <Table head={['Type', 'Haircut', 'Max LTV', 'EC years', 'Valuation age', 'Second valuation above', 'Variance limit', 'Photos / GPS', 'Mandatory documents', '']} rows={(p.data ?? []).map(x => [
          x.label, pct(x.haircut, 0), x.type === 'GOLD' ? (x.ltvSlabs ?? '').split(',').map(sl => { const [u, v] = sl.split(':'); return `${u === '*' ? 'above' : '≤ ₹' + Number(u).toLocaleString('en-IN')}: ${Math.round(Number(v) * 100)}%` }).join(' · ') + ' (+ FOIR)' : pct(x.maxLtv, 0), x.ecYears || '—', `${x.valuationMaxAgeMonths} months`, x.secondValuationAbove ? inr(x.secondValuationAbove) : '—',
          pct(x.varianceLimit, 0), x.minImages ? `${x.minImages} within ${x.gpsRadiusM} m, ${pct(x.coverageMin, 0)} coverage` : '—', <span className="small">{x.requiredDocs.split(',').filter(Boolean).map(docLabel).join(', ')}</span>,
          canEdit ? <button className="btn sm" onClick={() => setEdit(x)}>Change</button> : ''])} />
      </div>
      <div className="grid2" style={{ marginTop: 12 }}>
        <div className="panel">
          <h3>Gold rate (22 carat, ₹ per gram)</h3>
          <p className="small">Used: <b>{inr(rates.data?.goldRateUsed ?? 0)}</b> (the lower of the 30-day average and the latest rate).</p>
          <Table head={['Date', 'Rate', 'Source']} rows={(rates.data?.gold ?? []).slice(0, 5).map(g => [g.rateDate, inr(g.rate22k), g.source.toLowerCase()])} />
          {user && ['ADMIN', 'OPERATIONS'].includes(user.role) && <div className="row" style={{ marginTop: 8 }}>
            <input inputMode="decimal" placeholder="Today's 22K rate" value={gold} onChange={e => setGold(e.target.value)} aria-label="Gold rate" />
            <button className="btn" onClick={saveGold}>Save</button></div>}
        </div>
        <div className="panel">
          <h3>Locality rates (AI value cross-check)</h3>
          <p className="small muted">Illustrative until the lender loads its own. The AI estimate is shown next to the valuer's value; it never sets the value.</p>
          <Table head={['PIN', 'Locality', 'Type', '₹ / sq ft']} rows={(rates.data?.locality ?? []).map(l => [l.pinPrefix + '…', l.locality, label(l.type), inr(l.ratePerSqft)])} />
        </div>
      </div>
      <Comparables />
      {edit && <PolicyEdit p={edit} onClose={() => setEdit(null)} onDone={() => { setEdit(null); setR(x => x + 1) }} />}
    </>
  )
}

interface Sale { id: number; type: string; pincode: string; locality?: string; areaSqft: number; price: number; saleDate: string; source: string; addedBy: string }

/** Registered sales behind the automated valuation; the valuation desk pastes or uploads a CSV. */
function Comparables() {
  const { user } = useAuth()
  const [r, setR] = useState(0)
  const list = useLoad(() => api.get<Sale[]>('/api/collateral/comparables'), [r])
  const [csv, setCsv] = useState('')
  const [source, setSource] = useState('REGISTRY')
  const [res, setRes] = useState<{ added: number; rows: number; errors: string[] }>()
  const [error, setError] = useState<unknown>()
  const canImport = user && ['ADMIN', 'VALUER', 'CREDIT_MANAGER'].includes(user.role)
  async function load() {
    setError(undefined); setRes(undefined)
    const lines = csv.trim().split(/\r?\n/).filter(Boolean)
    const head = lines[0]?.split(',').map(h => h.trim())
    if (!head?.includes('pincode')) { setError(new Error('The first line must name the columns: type,pincode,locality,lat,lng,areaSqft,price,saleDate,documentNo')); return }
    const rows = lines.slice(1).map(l => { const v = l.split(','); const o: Record<string, unknown> = {}; head.forEach((h, i) => { const x = (v[i] ?? '').trim(); o[h] = x === '' ? null : ['lat', 'lng', 'areaSqft', 'price'].includes(h) ? Number(x) : x }); return o })
    try { setRes(await api.post('/api/collateral/comparables', { source, rows })); setR(x => x + 1) } catch (e) { setError(e) }
  }
  return (
    <div className="panel" style={{ marginTop: 12 }} data-testid="comparables">
      <h3>Comparable sales (automated valuation)</h3>
      <p className="small muted">Registered sales within 3 km and 24 months, brought to today, give the automated value shown beside the valuer's report. {list.data?.length ?? 0} latest shown; seeded rows are illustrative.</p>
      <Table head={['Type', 'PIN', 'Locality', 'Area', 'Price', '₹/sq ft', 'Sold', 'Source']} rows={(list.data ?? []).slice(0, 12).map(x => [label(x.type), x.pincode, x.locality ?? '—', `${x.areaSqft} sq ft`, inr(x.price), inr(Math.round(x.price / x.areaSqft)), x.saleDate, x.source.toLowerCase()])} />
      {canImport && <>
        <label style={{ marginTop: 8 }}>Add sales (CSV with a header row: type,pincode,locality,lat,lng,areaSqft,price,saleDate,documentNo)
          <textarea name="comparablesCsv" rows={4} value={csv} onChange={e => setCsv(e.target.value)} placeholder={'type,pincode,locality,lat,lng,areaSqft,price,saleDate,documentNo\nRESIDENTIAL,422013,Gangapur Road,20.006,73.763,1200,6500000,2026-08-14,NSK/4411/2026'} /></label>
        <div className="row"><select value={source} onChange={e => setSource(e.target.value)} aria-label="Source"><option value="REGISTRY">Sub-registrar data</option><option value="VALUATION">Our past valuations</option><option value="VENDOR">Data vendor</option></select>
          <button className="btn" data-action="importComparables" onClick={load} disabled={!csv.trim()}>Add sales</button></div>
        <ErrorBox error={error} />
        {res && <p className="small">{res.added} of {res.rows} added.{res.errors.length > 0 && <> {res.errors.join('; ')}</>}</p>}
      </>}
    </div>
  )
}

function PolicyEdit({ p, onClose, onDone }: { p: Policy; onClose: () => void; onDone: () => void }) {
  const [f, setF] = useState({ haircut: String(p.haircut), maxLtv: String(p.maxLtv), ecYears: String(p.ecYears), valuationMaxAgeMonths: String(p.valuationMaxAgeMonths), ltvSlabs: p.ltvSlabs ?? '', note: '' })
  const [error, setError] = useState<unknown>()
  async function save() {
    setError(undefined)
    try {
      await api.put(`/api/collateral-policy/${p.type}`, { haircut: Number(f.haircut), maxLtv: Number(f.maxLtv), ecYears: Number(f.ecYears), valuationMaxAgeMonths: Number(f.valuationMaxAgeMonths), ...(p.type === 'GOLD' ? { ltvSlabs: f.ltvSlabs } : {}), note: f.note })
      onDone()
    } catch (e) { setError(e) }
  }
  return (
    <Modal title={`Collateral policy: ${p.label}`} onClose={onClose}>
      <div className="grid2">
        <label>Haircut (0–0.6)<input value={f.haircut} onChange={e => setF({ ...f, haircut: e.target.value })} /></label>
        {p.type !== 'GOLD' && <label>Maximum LTV<input value={f.maxLtv} onChange={e => setF({ ...f, maxLtv: e.target.value })} /></label>}
        <label>EC years<input value={f.ecYears} onChange={e => setF({ ...f, ecYears: e.target.value })} /></label>
        <label>Valuation age (months)<input value={f.valuationMaxAgeMonths} onChange={e => setF({ ...f, valuationMaxAgeMonths: e.target.value })} /></label>
      </div>
      {p.type === 'GOLD' && <label>LTV by loan size (up to ₹:LTV, … , *:LTV). RBI caps: 85% up to ₹2.5 lakh, 80% up to ₹5 lakh, 75% above
        <input name="ltvSlabs" value={f.ltvSlabs} onChange={e => setF({ ...f, ltvSlabs: e.target.value })} /></label>}
      {p.type === 'GOLD' && <p className="small muted">The loan is also capped by FOIR (Credit policy studio, override for the gold product; 50% now). The lower amount applies.</p>}
      <label>Board approval or reason<input value={f.note} onChange={e => setF({ ...f, note: e.target.value })} /></label>
      <ErrorBox error={error} />
      <button className="btn primary" onClick={save}>Save</button>
    </Modal>
  )
}

// ---------------------------------------------------------------- case workspace SCR-141..149

const CASE_TABS = ['360 summary', 'Documents', 'EC analysis', 'Title chain', 'Property vision', 'Valuation & LTV', 'Risk & decision', 'Audit'] as const
type CaseTab = typeof CASE_TABS[number]

/** One collateral case: the eight tabs of the Collateral AI workspace. */
export function CollateralCase() {
  const { id } = useParams()
  const [rev, setRev] = useState(0)
  const [tab, setTab] = useState<CaseTab>('360 summary')
  const d = useLoad(() => api.get<Detail>(`/api/collaterals/${id}`), [id, rev])
  const refresh = () => setRev(x => x + 1)
  const c = d.data?.collateral
  return (
    <>
      <Head title={c ? `${c.colNo} · ${label(c.type)}` : 'Collateral'} sub={d.data ? `${d.data.summary.applicant} · ${d.data.summary.appNo} · ${c?.address ?? ''}` : ''}>
        {c && <><St s={c.status} /> <Link className="btn" to={`/applications/${c.applicationId}`}>Application</Link></>}
      </Head>
      <ErrorBox error={d.error} />
      {d.data && <>
        <div className="tabs" role="tablist">
          {CASE_TABS.filter(t => !(c!.type === 'GOLD' && ['EC analysis', 'Title chain', 'Property vision'].includes(t)) && !(c!.type === 'VEHICLE' && ['EC analysis', 'Title chain'].includes(t)))
            .map(t => <button key={t} role="tab" aria-selected={tab === t} className={tab === t ? 'on' : ''} onClick={() => setTab(t)}>{t === 'Property vision' && c!.type === 'VEHICLE' ? 'Vehicle photos' : t}</button>)}
        </div>
        {tab === '360 summary' && <Summary d={d.data} />}
        {tab === 'Documents' && <DocumentsTab d={d.data} onChange={refresh} />}
        {tab === 'EC analysis' && <EcTab d={d.data} />}
        {tab === 'Title chain' && <TitleTab d={d.data} />}
        {tab === 'Property vision' && <VisionTab d={d.data} onChange={refresh} />}
        {tab === 'Valuation & LTV' && <ValuationTab d={d.data} onChange={refresh} />}
        {tab === 'Risk & decision' && <RiskTab d={d.data} onChange={refresh} />}
        {tab === 'Audit' && <AuditTab id={c!.id} />}
      </>}
    </>
  )
}

function Summary({ d }: { d: Detail }) {
  const e = d.evaluation
  const c = d.collateral
  return (
    <>
      <div className="kpis" data-testid="collateral-360">
        <div className="kpi"><span>Status</span><b><St s={c.status} /></b><span>{d.reviews.filter(r => r.status === 'OPEN').length} open reviews</span></div>
        <div className="kpi"><span>Value used</span><b>{e?.ltv.valueUsed ? inr(e.ltv.valueUsed) : '—'}</b><span>{e?.valuation.reports ?? 0} valuation(s)</span></div>
        <div className="kpi"><span>Maximum exposure</span><b>{e ? inr(e.ltv.maxExposure) : '—'}</b><span>{e ? `LTV ${pct(e.ltv.maxLtv, 0)} of eligible value` : ''}</span></div>
        <div className="kpi"><span>Risk score</span><b>{e?.riskScore ?? '—'}</b><span>0 is no concern, 100 severe</span></div>
      </div>
      {e && <div className="panel" style={{ marginTop: 12 }}>
        <h3>Case summary</h3>
        <p>{e.summary}</p>
        <p className="small muted">Evidence IDs (DOC-, IMG-, VAL-) link to the Documents, Property vision and Valuation tabs. Evaluated {when(e.evaluatedAt)} by {e.evaluatedBy}, model {e.modelVersion}, policy {e.policyVersion ?? '—'}.</p>
      </div>}
      <div className="grid2" style={{ marginTop: 12 }}>
        <div className="panel">
          <h3>Key facts</h3>
          <dl className="facts">
            <dt>Owner (declared)</dt><dd>{c.ownerName ?? '—'}</dd>
            {c.surveyNo && <><dt>Survey / CTS</dt><dd>{c.surveyNo}</dd></>}
            {c.municipalId && <><dt>Municipal ID</dt><dd>{c.municipalId}</dd></>}
            {c.areaSqft && <><dt>Area</dt><dd>{c.areaSqft} sq ft</dd></>}
            <dt>Condition</dt><dd>{[c.condition, c.constructionStage, c.occupancy].filter(Boolean).map(x => label(x!)).join(' · ') || '—'}</dd>
            <dt>Insurance</dt><dd>{c.insurer ? `${c.insurer}, to ${c.insuranceExpiry ?? '—'}${c.insuranceAssigned ? ', assigned to the lender' : ', not assigned'}` : '—'}</dd>
          </dl>
        </div>
        <UnifiedPanel u={d.unified} />
      </div>
    </>
  )
}

function UnifiedPanel({ u }: { u: Unified }) {
  return (
    <div className="panel" data-testid="unified-decision">
      <h3>Unified credit + collateral decision</h3>
      <p>Credit <b>{label(u.credit)}</b> · collateral <b>{label(u.collateral)}</b> → <St s={u.decision} /></p>
      {u.recommendedAmount != null && <p className="small">Recommended exposure {inr(u.recommendedAmount)}</p>}
      {u.note && <p className="small">{u.note}</p>}
      {u.conditions.length > 0 && <><h4>Conditions</h4><ul className="small">{u.conditions.map(c => <li key={c}>{c}</li>)}</ul></>}
    </div>
  )
}

function DocumentsTab({ d, onChange }: { d: Detail; onChange: () => void }) {
  const { user } = useAuth()
  const [type, setType] = useState(() => docsFor(d.collateral.type)[0])
  const [fields, setFields] = useState('')
  const [tx, setTx] = useState('')
  const [file, setFile] = useState<File>()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>()
  const [confirm, setConfirm] = useState<Doc | null>(null)
  const required = d.policy.requiredDocs.split(',')
  const have = new Set(d.documents.map(x => x.document.docType))
  const canUpload = user && ['ADMIN', 'SALES', 'OPERATIONS', 'CREDIT_OFFICER', 'CREDIT_MANAGER', 'FIELD_OFFICER', 'LEGAL_REVIEWER'].includes(user.role)
  async function send() {
    if (!file) return
    setBusy(true); setError(undefined)
    try {
      const f = new FormData()
      f.append('file', file); f.append('docType', type)
      if (fields.trim()) f.append('fields', fields)
      if (tx.trim()) f.append('transactions', tx)
      await upload(`/api/collaterals/${d.collateral.id}/documents`, f)
      setFile(undefined); setFields(''); setTx(''); onChange()
    } catch (e) { setError(e) } finally { setBusy(false) }
  }
  return (
    <>
      <div className="panel">
        <h3>Checklist</h3>
        <div className="row" style={{ flexWrap: 'wrap' }}>
          {required.filter(r => r !== 'APPRAISAL').map(r => <span key={r} className={'pill ' + (have.has(r) ? 'ok' : 'bad')}>{docLabel(r)}{have.has(r) ? '' : ' (missing)'}</span>)}
          {(d.policy.optionalDocs ?? '').split(',').filter(Boolean).map(r => <span key={r} className={'pill ' + (have.has(r) ? 'ok' : 'muted')}>{docLabel(r)} (optional)</span>)}
        </div>
      </div>
      <div className="panel flush" style={{ marginTop: 12 }}>
        <Table head={['ID', 'Document', 'Fields read', 'Confidence', 'Read by', 'Uploaded', '']} rows={d.documents.map(x => [
          <span className="mono">DOC-{x.document.id}</span>, <span>{docLabel(x.document.docType)} v{x.document.version}{x.legalFlag && <span className="pill warn" style={{ marginLeft: 4 }}>legal reading</span>}{x.document.manipulated && <span className="pill bad" style={{ marginLeft: 4 }}>manipulated</span>}</span>,
          <span className="small">{Object.entries(x.extracted.fields ?? {}).map(([k, v]) => <span key={k} className={(x.extracted.confidence?.[k] ?? 1) < 0.85 ? 'warn-text' : ''}>{label(k)}: {String(v)}; </span>)}
            {(x.extracted.transactions?.length ?? 0) > 0 && <b> {x.extracted.transactions!.length} EC entries</b>}</span>,
          x.document.readConfidence == null ? '—' : pct(x.document.readConfidence, 0), <span className="small">{x.document.engine}{x.document.confirmedBy && <div>confirmed by {x.document.confirmedBy}</div>}</span>,
          <span className="small">{when(x.document.uploadedAt)} · {x.document.uploadedBy}</span>,
          <span className="row"><button className="btn sm" onClick={() => openFile(`/api/collateral-documents/${x.document.id}/content`)}>View</button>
            {!x.document.confirmedBy && canUpload && <button className="btn sm" onClick={() => setConfirm(x)}>Confirm fields</button>}</span>])} empty="No documents yet." />
      </div>
      {canUpload && <div className="panel" style={{ marginTop: 12 }}>
        <h3>Upload a document</h3>
        <div className="grid2">
          <label>Type<select value={type} onChange={e => setType(e.target.value)}>{docsFor(d.collateral.type).map(x => <option key={x} value={x}>{docLabel(x)}</option>)}</select></label>
          <label>File (PDF, JPG or PNG)<input type="file" accept=".pdf,.jpg,.jpeg,.png" onChange={e => setFile(e.target.files?.[0])} /></label>
        </div>
        <label>Fields you have checked (optional JSON, e.g. {'{"owner": "…", "surveyNo": "…"}'}); typed fields count as confirmed<input value={fields} onChange={e => setFields(e.target.value)} /></label>
        {type === 'EC' && <label>EC entries (optional JSON list of {'{date, type, docNo, from, to, linkedRef}'})<textarea rows={3} value={tx} onChange={e => setTx(e.target.value)} /></label>}
        <ErrorBox error={error} />
        <button className="btn primary" disabled={!file || busy} onClick={send}>{busy ? 'Reading…' : 'Upload and read'}</button>
        <p className="small muted">Every page is hashed; a re-upload makes a new version and reads it again. Fields read under 85% confidence wait for a person to confirm them.</p>
      </div>}
      {confirm && <ConfirmDialog doc={confirm} onClose={() => setConfirm(null)} onDone={() => { setConfirm(null); onChange() }} />}
    </>
  )
}

function ConfirmDialog({ doc, onClose, onDone }: { doc: Doc; onClose: () => void; onDone: () => void }) {
  const [f, setF] = useState<Record<string, string>>(Object.fromEntries(Object.entries(doc.extracted.fields ?? {}).map(([k, v]) => [k, String(v ?? '')])))
  const [error, setError] = useState<unknown>()
  async function save() {
    setError(undefined)
    try { await api.post(`/api/collateral-documents/${doc.document.id}/confirm`, { fields: f }); onDone() } catch (e) { setError(e) }
  }
  return (
    <Modal title={`Confirm ${docLabel(doc.document.docType)} (DOC-${doc.document.id})`} onClose={onClose}>
      <p className="small muted">Check each field against the document and correct it. Confirmed fields count at 100%.</p>
      <div className="grid2">{Object.keys(f).map(k => <label key={k}>{label(k)}{(doc.extracted.confidence?.[k] ?? 1) < 0.85 && ' (low confidence)'}<input value={f[k]} onChange={e => setF({ ...f, [k]: e.target.value })} /></label>)}</div>
      <ErrorBox error={error} />
      <button className="btn primary" onClick={save}>Confirm</button>
    </Modal>
  )
}

function EcTab({ d }: { d: Detail }) {
  const ec = d.evaluation?.ec
  if (!ec?.present) return <div className="panel empty">No encumbrance certificate yet. Upload it under Documents; its entries are read into charges and releases.</div>
  return (
    <>
      <div className="kpis">
        <div className="kpi"><span>Period</span><b>{ec.years} years</b><span>{ec.periodFrom} to {ec.periodTo} (policy {ec.requiredYears})</span></div>
        <div className="kpi"><span>Latest owner</span><b className="small">{ec.latestOwner ?? '—'}</b></div>
        <div className="kpi"><span>Open charges</span><b className={ec.openCharges?.length ? 'bad-text' : ''}>{ec.openCharges?.length ?? 0}</b><span>{ec.releasedCharges?.length ?? 0} released</span></div>
        <div className="kpi"><span>Current</span><b>{ec.current ? 'Yes' : 'No'}</b><span>ends within 90 days of today</span></div>
      </div>
      <div className="panel flush" style={{ marginTop: 12 }}>
        <Table head={['Date', 'Entry', 'Document no.', 'From', 'To', 'Charge status']} rows={(ec.transactions ?? []).map(t => {
          const open = ec.openCharges?.some(o => o.docNo === t.docNo)
          const rel = ec.releasedCharges?.find(o => o.docNo === t.docNo)
          return [t.date, label(t.type), <span className="mono">{t.docNo}</span>, t.from ?? '—', t.to ?? '—',
            t.type === 'MORTGAGE' ? (open ? <span className="pill bad">open: no release</span> : <span className="pill ok">released by {rel?.releasedBy} on {rel?.releasedOn}</span>) : t.type === 'RELEASE' ? `release of ${t.linkedRef ?? '—'}` : '']
        })} />
      </div>
      <p className="small muted">Source: uploaded EC (DOC-{ec.docId}). State registry integrations come with the integrations phase; AI matches entries but does not give an EC opinion.</p>
    </>
  )
}

function TitleTab({ d }: { d: Detail }) {
  const t = d.evaluation?.title ?? []
  if (!t.length) return <div className="panel empty">No ownership links yet: upload the sale deed, mother deed and EC.</div>
  return (
    <div className="panel">
      <h3>Ownership timeline</h3>
      <ol className="timeline">
        {t.map((l, i) => <li key={i} className={l.flag ? 'flag' : ''}>
          <b>{l.year ?? '—'}</b> · {l.instrument}: {l.fromOwner ? `${l.fromOwner} → ` : ''}<b>{l.owner}</b> <span className="small muted">[{l.evidence}]</span>
          {l.flag && <div><span className={'pill ' + (l.flag === 'CONFLICT' ? 'bad' : 'warn')}>{l.flag.toLowerCase()}</span> <span className="small">{l.note}</span></div>}
        </li>)}
      </ol>
      <p className="small muted">A gap is a missing link (the seller is not the previous owner on record, or the chain is shorter than the policy's {d.policy.ecYears || 30} years); a conflict is the same owner transferring twice. A legal reviewer reads every flag.</p>
    </div>
  )
}

function VisionTab({ d, onChange }: { d: Detail; onChange: () => void }) {
  const { user } = useAuth()
  const v = d.evaluation?.vision
  const [labelSel, setLabel] = useState((d.policy.requiredPhotos ?? 'EXTERIOR_FRONT').split(',')[0])
  const [file, setFile] = useState<File>()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>()
  const canUpload = user && ['ADMIN', 'OPERATIONS', 'CREDIT_OFFICER', 'FIELD_OFFICER', 'VALUER'].includes(user.role)
  async function send() {
    if (!file) return
    setBusy(true); setError(undefined)
    try {
      const pos = await new Promise<GeolocationPosition | null>(res => navigator.geolocation ? navigator.geolocation.getCurrentPosition(res, () => res(null), { timeout: 8000 }) : res(null))
      const f = new FormData()
      f.append('file', file); f.append('label', labelSel)
      if (pos) { f.append('lat', String(pos.coords.latitude)); f.append('lng', String(pos.coords.longitude)) }
      f.append('capturedAt', new Date(file.lastModified || Date.now()).toISOString())
      await upload(`/api/collaterals/${d.collateral.id}/media`, f)
      setFile(undefined); onChange()
    } catch (e) { setError(e) } finally { setBusy(false) }
  }
  return (
    <>
      {v && <div className="kpis">
        <div className="kpi"><span>Photos</span><b>{v.photos}</b><span>at least {d.policy.minImages}</span></div>
        <div className="kpi"><span>Coverage</span><b>{pct(v.coverage ?? 0, 0)}</b><span>{v.missingLabels?.length ? 'missing ' + v.missingLabels.map(label).join(', ') : 'all views'}</span></div>
        <div className="kpi"><span>Farthest from the property</span><b>{v.maxDistanceM ?? 0} m</b><span>limit {d.policy.gpsRadiusM} m</span></div>
        <div className="kpi"><span>Reused / poor quality</span><b className={v.reused ? 'bad-text' : ''}>{v.reused ?? 0} / {v.poorQuality ?? 0}</b></div>
      </div>}
      <div className="panel flush" style={{ marginTop: 12 }}>
        <Table head={['ID', 'View', 'GPS', 'Distance', 'Taken', 'Quality', 'Reuse', '']} rows={d.media.map(m => [<span className="mono">IMG-{m.id}</span>, label(m.label),
          m.lat == null ? <span className="pill warn">no GPS</span> : `${m.lat.toFixed(5)}, ${m.lng?.toFixed(5)}`, m.distanceM == null ? '—' : <span className={m.distanceM > d.policy.gpsRadiusM ? 'bad-text' : ''}>{m.distanceM} m</span>,
          m.capturedAt ? when(m.capturedAt) : '—', <span className={'pill ' + (m.quality === 'GOOD' ? 'ok' : m.quality === 'UNKNOWN' ? 'muted' : 'warn')}>{(m.quality ?? 'unknown').toLowerCase()}</span>,
          m.reuseMatch ? <span className="pill bad">also on {m.reuseMatch}</span> : m.manipulated ? <span className="pill bad">manipulated</span> : '—',
          <button className="btn sm" onClick={() => openFile(`/api/collateral-media/${m.id}/content`)}>View</button>])} empty="No photos yet." />
      </div>
      {canUpload && <div className="panel" style={{ marginTop: 12 }}>
        <h3>Add a photo</h3>
        <div className="grid2">
          <label>View<select value={labelSel} onChange={e => setLabel(e.target.value)}>{(d.policy.requiredPhotos ?? '').split(',').concat(['OTHER']).filter(Boolean).map(x => <option key={x} value={x}>{label(x)}</option>)}</select></label>
          <label>Photo<input type="file" accept="image/*" capture="environment" onChange={e => setFile(e.target.files?.[0])} /></label>
        </div>
        <ErrorBox error={error} />
        <button className="btn primary" disabled={!file || busy} onClick={send}>{busy ? 'Checking…' : 'Upload'}</button>
        <p className="small muted">The phone's location is attached; the photo's own GPS and time are read too. Photos must be within {d.policy.gpsRadiusM} m of the property; a photo seen on another file fails the check.</p>
      </div>}
    </>
  )
}

function ValuationTab({ d, onChange }: { d: Detail; onChange: () => void }) {
  const { user } = useAuth()
  const e = d.evaluation
  const [f, setF] = useState({ valuer: '', reportDate: new Date().toISOString().slice(0, 10), marketValue: '', realisableValue: '', distressValue: '', method: 'Sales comparison' })
  const [items, setItems] = useState([{ description: '', grossWeight: '', stoneWeight: '0', purityKarat: '22' }])
  const [error, setError] = useState<unknown>()
  const gold = d.collateral.type === 'GOLD'
  async function save() {
    setError(undefined)
    try {
      if (gold) await api.post(`/api/collaterals/${d.collateral.id}/gold-appraisal`, { items: items.map(i => ({ description: i.description, grossWeight: Number(i.grossWeight), stoneWeight: Number(i.stoneWeight || 0), purityKarat: Number(i.purityKarat) })) })
      else await api.post(`/api/collaterals/${d.collateral.id}/valuations`, { ...f, marketValue: Number(f.marketValue), realisableValue: f.realisableValue ? Number(f.realisableValue) : undefined, distressValue: f.distressValue ? Number(f.distressValue) : undefined })
      onChange()
    } catch (x) { setError(x) }
  }
  const canRecord = user && (gold ? ['ADMIN', 'OPERATIONS', 'CREDIT_OFFICER', 'CREDIT_MANAGER'] : ['ADMIN', 'VALUER']).includes(user.role)
  return (
    <>
      {e && <div className="grid2">
        <div className="panel">
          <h3>LTV</h3>
          <dl className="facts">
            <dt>Value used (lower of approved)</dt><dd>{inr(e.ltv.valueUsed)}</dd>
            <dt>Haircut</dt><dd>{pct(e.ltv.haircut, 0)}</dd>
            <dt>Eligible value</dt><dd>{inr(e.ltv.eligibleValue)}</dd>
            <dt>Maximum LTV</dt><dd>{pct(e.ltv.maxLtv, 0)}</dd>
            <dt>Maximum exposure</dt><dd><b>{inr(e.ltv.maxExposure)}</b></dd>
            <dt>Requested</dt><dd>{inr(e.ltv.requested)}{e.ltv.ltvRequested != null && ` (LTV ${pct(e.ltv.ltvRequested, 0)})`}</dd>
            <dt>Recommended</dt><dd>{inr(e.ltv.recommended)}{e.ltv.ltvCap && <span className="pill warn" style={{ marginLeft: 4 }}>LTV CAP</span>}</dd>
          </dl>
        </div>
        <div className="panel">
          <h3>Valuations</h3>
          <dl className="facts">
            <dt>Reports</dt><dd>{e.valuation.reports}</dd>
            <dt>Variance</dt><dd>{pct(e.valuation.variance, 1)} (limit {pct(d.policy.varianceLimit, 0)})</dd>
            <dt>Newest</dt><dd>{e.valuation.newestReport ?? '—'} ({e.valuation.ageMonths} months)</dd>
            <dt>Realisable / distress</dt><dd>{inr(e.valuation.realisable)} / {inr(e.valuation.distress)}</dd>
            {e.valuation.aiValue != null && <><dt>AI cross-check</dt><dd>{inr(e.valuation.aiValue)} (confidence {pct(e.valuation.aiConfidence ?? 0, 0)}; value used is {pct(e.valuation.aiDeviation ?? 0, 0)} from it) · shown only</dd></>}
          </dl>
        </div>
      </div>}
      {d.avm && <div className="panel" style={{ marginTop: 12 }} data-testid="avm">
        <h3>Automated valuation from comparable sales</h3>
        <p className="small"><b>{inr(d.avm.value)}</b> (range {inr(d.avm.low)} to {inr(d.avm.high)}) at {inr(d.avm.ratePerSqft)}/sq ft · confidence {pct(d.avm.confidence, 0)} · {d.avm.basis}. A cross-check beside the empanelled valuer: it never sets the value.</p>
        <Table head={['Sale', 'Where', 'Distance', 'When', 'Area', 'Price', '₹/sq ft', 'Today', 'Weight']} rows={d.avm.used.map(u => [<span className="mono">SALE-{u.id}</span>, u.locality ?? '—', `${u.distanceKm} km`, `${u.monthsAgo} months ago`,
          `${u.areaSqft} sq ft`, inr(u.price), inr(u.ratePerSqft), inr(u.adjustedRate), u.weight])} />
      </div>}
      <div className="panel flush" style={{ marginTop: 12 }}>
        <Table head={['ID', 'Source', 'Valuer', 'Date', 'Market', 'Realisable', 'Distress', 'Method', 'Used']} rows={d.valuations.map(v => [<span className="mono">VAL-{v.id}</span>, label(v.source), v.valuer ?? '—', v.reportDate,
          inr(v.marketValue), v.realisableValue ? inr(v.realisableValue) : '—', v.distressValue ? inr(v.distressValue) : '—', <span className="small">{v.method}</span>, v.approved ? 'Yes' : 'Replaced'])} empty="No valuations yet." />
      </div>
      {canRecord && <div className="panel" style={{ marginTop: 12 }}>
        {gold ? <>
          <h3>Gold appraisal</h3>
          <div className="gold-items gold-head small muted"><span>Item</span><span>Gross weight (g)</span><span>Stones (g)</span><span>Purity</span></div>
          {items.map((it, i) => <div className="gold-items" key={i}>
            <input placeholder="Item" value={it.description} onChange={e2 => setItems(items.map((x, j) => j === i ? { ...x, description: e2.target.value } : x))} />
            <input placeholder="Gross g" inputMode="decimal" value={it.grossWeight} onChange={e2 => setItems(items.map((x, j) => j === i ? { ...x, grossWeight: e2.target.value } : x))} />
            <input placeholder="Stones g" inputMode="decimal" value={it.stoneWeight} onChange={e2 => setItems(items.map((x, j) => j === i ? { ...x, stoneWeight: e2.target.value } : x))} />
            <select value={it.purityKarat} onChange={e2 => setItems(items.map((x, j) => j === i ? { ...x, purityKarat: e2.target.value } : x))} aria-label="Purity">{['24', '22', '20', '18', '14'].map(k => <option key={k} value={k}>{k} carat</option>)}</select>
          </div>)}
          <button className="btn sm" onClick={() => setItems([...items, { description: '', grossWeight: '', stoneWeight: '0', purityKarat: '22' }])}>Add item</button>
          <p className="small muted">Valued on net gold weight only (no stones or making charges) at the 22 carat rate; LTV caps follow RBI: 85% up to ₹2.5 lakh, 80% up to ₹5 lakh, 75% above.</p>
        </> : <>
          <h3>Record an empanelled valuation</h3>
          <div className="grid2">
            <label>Valuer<input value={f.valuer} onChange={x => setF({ ...f, valuer: x.target.value })} /></label>
            <label>Report date<input type="date" value={f.reportDate} onChange={x => setF({ ...f, reportDate: x.target.value })} /></label>
            <label>Market value (₹)<input inputMode="numeric" value={f.marketValue} onChange={x => setF({ ...f, marketValue: x.target.value })} /></label>
            <label>Realisable value (₹)<input inputMode="numeric" value={f.realisableValue} onChange={x => setF({ ...f, realisableValue: x.target.value })} /></label>
            <label>Distress value (₹)<input inputMode="numeric" value={f.distressValue} onChange={x => setF({ ...f, distressValue: x.target.value })} /></label>
            <label>Method<input value={f.method} onChange={x => setF({ ...f, method: x.target.value })} /></label>
          </div>
        </>}
        <ErrorBox error={error} />
        <button className="btn primary" data-action="saveValuation" onClick={save}>Save</button>
      </div>}
    </>
  )
}

function RiskTab({ d, onChange }: { d: Detail; onChange: () => void }) {
  const e = d.evaluation
  const [error, setError] = useState<unknown>()
  const [open, setOpen] = useState<Review | null>(null)
  if (!e) return <div className="panel empty">Not evaluated yet.</div>
  async function rerun() { setError(undefined); try { await api.post(`/api/collaterals/${d.collateral.id}/evaluate`); onChange() } catch (x) { setError(x) } }
  const decisionJson = { collateralId: d.collateral.colNo, status: e.status, riskScore: e.riskScore, ltv: e.ltv, rules: e.rules.map(r => ({ id: r.id, passed: r.passed, output: r.passed ? 'OK' : r.output, ruling: r.ruling })), unified: d.unified }
  return (
    <>
      <ErrorBox error={error} />
      <div className="grid2">
        <div className="panel">
          <h3>Risk vector · {e.riskScore}/100</h3>
          {e.risk.map(x => <div key={x.name} className="bar-row" title={x.evidence}>
            <span style={{ width: 120, display: 'inline-block' }}>{x.name} <span className="small muted">{Math.round(x.weight * 100)}%</span></span>
            <span className="bar"><span style={{ width: `${x.score}%`, background: x.score >= 70 ? 'var(--bad, #c0392b)' : x.score >= 40 ? 'var(--warn, #d68910)' : 'var(--ok, #1e8449)' }} /></span>
            <span className="small" style={{ marginLeft: 6 }}>{x.score}</span>
            <div className="small muted">{x.evidence}</div>
          </div>)}
          <p className="small muted">Higher is riskier. For information and pricing: the rules decide the outcome.</p>
        </div>
        <UnifiedPanel u={d.unified} />
      </div>
      <div className="panel flush" style={{ marginTop: 12 }}>
        <Table head={['Rule', 'Check', 'Result', 'Evidence', 'Review']} rows={e.rules.map(r => {
          const rv = d.reviews.find(x => x.ruleId === r.id && x.status === 'OPEN')
          return [r.id, <span className="small">{r.check}</span>,
            <span>{r.passed ? <span className="pill ok">{r.ruling === 'CLEARED' ? 'cleared' : 'ok'}</span> : <span className={'pill ' + (r.output.includes('FAIL') ? 'bad' : 'warn')}>{r.output.toLowerCase()}</span>}<div className="small">{r.reason}</div></span>,
            <span className="small mono">{r.evidence.join(', ')}</span>, rv ? <button className="btn sm" onClick={() => setOpen(rv)}>{label(rv.type)} review</button> : '']
        })} />
      </div>
      <div className="row" style={{ marginTop: 12 }}><button className="btn" onClick={rerun}>Run Collateral AI again</button></div>
      <details style={{ marginTop: 12 }}><summary className="small">Decision JSON</summary><pre className="small">{JSON.stringify(decisionJson, null, 2)}</pre></details>
      {open && <ResolveDialog review={open} onClose={() => setOpen(null)} onDone={() => { setOpen(null); onChange() }} />}
    </>
  )
}

function AuditTab({ id }: { id: number }) {
  const t = useLoad(() => api.get<{ evidence: { id: string; kind: string; hash: string; engine: string; by: string; at: string; confirmedBy: string }[]; evaluations: Evaluation[]; reviews: Review[] }>(`/api/collaterals/${id}/audit`), [id])
  return (
    <>
      <p className="small muted">Evidence → feature → rule → decision → reviewer. Every evaluation is kept with its policy and model version.</p>
      <ErrorBox error={t.error} />
      {t.data && <>
        <h3>Evidence</h3>
        <div className="panel flush"><Table head={['ID', 'Kind', 'Hash', 'Read / method', 'By', 'When']} rows={t.data.evidence.map(e => [<span className="mono">{e.id}</span>, label(e.kind), <span className="mono small">{e.hash ? e.hash.slice(0, 12) + '…' : '—'}</span>,
          <span className="small">{e.engine}{e.confirmedBy && ` · ${e.confirmedBy}`}</span>, e.by, when(e.at)])} /></div>
        <h3 style={{ marginTop: 12 }}>Evaluations</h3>
        <div className="panel flush"><Table head={['When', 'By', 'Status', 'Risk', 'Rules not met', 'Policy / model']} rows={t.data.evaluations.map(e => [when(e.evaluatedAt), e.evaluatedBy, <St s={e.status} />, e.riskScore,
          <span className="small">{e.rules.filter(r => !r.passed).map(r => r.id).join(', ') || '—'}</span>, <span className="small">{e.policyVersion} · {e.modelVersion}</span>])} /></div>
        <h3 style={{ marginTop: 12 }}>Reviews</h3>
        <div className="panel flush"><Table head={['Rule', 'Queue', 'Reason', 'Outcome', 'Reviewer', 'Note', 'Closed']} rows={t.data.reviews.map(r => [r.ruleId, label(r.type), <span className="small">{r.reason}</span>, <St s={r.status} />, r.reviewer ?? '—',
          <span className="small">{r.note ?? ''}</span>, r.closedAt ? when(r.closedAt) : '—'])} empty="No reviews." /></div>
      </>}
    </>
  )
}

// ---------------------------------------------------------------- the application's Collateral tab

/** Collateral on one application: declare it and open each case. */
export function ApplicationCollateralTab({ id, productCode, rev, onChange }: { id: string; productCode: string; rev: number; onChange: () => void }) {
  const { user } = useAuth()
  const l = useLoad(() => api.get<Row[]>(`/api/applications/${id}/collaterals`), [id, rev])
  const u = useLoad(() => api.get<Unified>(`/api/applications/${id}/unified-decision`), [id, rev])
  const products = useLoad(() => api.get<{ code: string; collateralTypes?: string; collateralRequired?: boolean }[]>('/api/products'), [])
  const p = products.data?.find(x => x.code === productCode)
  const types = (p?.collateralTypes ?? '').split(',').filter(Boolean)
  const [show, setShow] = useState(false)
  const [f, setF] = useState<Record<string, string>>({ type: '' })
  const [error, setError] = useState<unknown>()
  const nav = useNavigate()
  const canDeclare = user && ['ADMIN', 'SALES', 'OPERATIONS', 'CREDIT_OFFICER', 'CREDIT_MANAGER'].includes(user.role) && types.length > 0
  async function declare() {
    setError(undefined)
    try {
      const body: Record<string, unknown> = {}
      for (const [k, v] of Object.entries(f)) if (v !== '') body[k] = ['lat', 'lng', 'areaSqft', 'newPrice', 'manufactureYear'].includes(k) ? Number(v) : v
      const c = await api.post<{ id: number }>(`/api/applications/${id}/collaterals`, body)
      setShow(false); onChange(); nav(`/collateral/${c.id}`)
    } catch (e) { setError(e) }
  }
  const t = f.type || types[0]
  return (
    <>
      {u.data && <UnifiedPanel u={u.data} />}
      {!types.length && <div className="panel empty" style={{ marginTop: 12 }}>This product takes no collateral.</div>}
      {types.length > 0 && <div className="panel flush" style={{ marginTop: 12 }}>
        <Table head={['Case', 'Type', 'Value', 'Max exposure', 'Risk', 'Documents', 'Reviews', 'Status']} onRow={i => nav(`/collateral/${l.data![i].id}`)} rows={(l.data ?? []).map(c => [
          <span className="mono">{c.colNo}</span>, label(c.type), c.value ? inr(c.value) : '—', c.maxExposure == null ? '—' : inr(c.maxExposure), c.riskScore ?? '—', pct(c.completeness, 0), c.openReviews || '—', <St s={c.status} />])}
          empty={p?.collateralRequired ? 'No collateral declared yet: sanction waits until it passes Collateral AI.' : 'No collateral.'} />
      </div>}
      {canDeclare && !show && <button className="btn" style={{ marginTop: 12 }} data-action="declareCollateral" onClick={() => { setF({ type: types[0] }); setShow(true) }}>Declare collateral</button>}
      {show && <div className="panel" style={{ marginTop: 12 }}>
        <h3>Declare collateral</h3>
        <div className="grid2">
          <label>Type<select value={t} onChange={e => setF({ ...f, type: e.target.value })}>{types.map(x => <option key={x} value={x}>{label(x)}</option>)}</select></label>
          <label>Description<input value={f.description ?? ''} onChange={e => setF({ ...f, description: e.target.value })} /></label>
          {t !== 'GOLD' && <>
            <label>Owner as declared<input value={f.ownerName ?? ''} onChange={e => setF({ ...f, ownerName: e.target.value })} /></label>
            <label>Address<input value={f.address ?? ''} onChange={e => setF({ ...f, address: e.target.value })} /></label>
            <label>PIN code<input value={f.pincode ?? ''} onChange={e => setF({ ...f, pincode: e.target.value })} /></label>
            <label>Latitude<input value={f.lat ?? ''} onChange={e => setF({ ...f, lat: e.target.value })} /></label>
            <label>Longitude<input value={f.lng ?? ''} onChange={e => setF({ ...f, lng: e.target.value })} /></label>
          </>}
          {['RESIDENTIAL', 'SHOP', 'PLOT'].includes(t) && <>
            <label>Survey / CTS no.<input value={f.surveyNo ?? ''} onChange={e => setF({ ...f, surveyNo: e.target.value })} /></label>
            <label>Municipal ID<input value={f.municipalId ?? ''} onChange={e => setF({ ...f, municipalId: e.target.value })} /></label>
            <label>Area (sq ft)<input value={f.areaSqft ?? ''} onChange={e => setF({ ...f, areaSqft: e.target.value })} /></label>
          </>}
          {t === 'VEHICLE' && <>
            <label>Registration no.<input value={f.registrationNo ?? ''} onChange={e => setF({ ...f, registrationNo: e.target.value })} /></label>
            <label>Make and model<input value={f.makeModel ?? ''} onChange={e => setF({ ...f, makeModel: e.target.value })} /></label>
            <label>Year of manufacture<input value={f.manufactureYear ?? ''} onChange={e => setF({ ...f, manufactureYear: e.target.value })} /></label>
            <label>New price (₹, for the AI cross-check)<input value={f.newPrice ?? ''} onChange={e => setF({ ...f, newPrice: e.target.value })} /></label>
          </>}
        </div>
        <ErrorBox error={error} />
        <div className="row"><button className="btn primary" onClick={declare}>Declare and open the case</button><button className="btn" onClick={() => setShow(false)}>Cancel</button></div>
      </div>}
    </>
  )
}
