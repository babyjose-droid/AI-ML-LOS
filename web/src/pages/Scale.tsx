import { useEffect, useState } from 'react'
import { api, getToken } from '../api'
import { useAuth } from '../auth'
import { ErrorBox, Head, Modal, Table, useLoad } from '../components/ui'
import { inr, lakh, pct, when } from '../format'

interface Partner { id: number; code: string; name: string; products: string; maxTicket: number; allowedBands: string; ntcAllowed: boolean; minVintageYears: number; pslOnly: boolean; partnerShare: number; partnerRate: number; status: string }
interface Eligible { applicationId: number; appNo: string; borrowerName: string; productCode: string; amount: number; partnerAmount: number; loanRate: number; nbfcYield: number; band: string; psl: boolean; status: string; assignment?: { id: number; status: string; batch: string; partnerRef?: string } }
interface PoolPartner { partner: Partner; eligible: Eligible[]; eligibleCount: number; eligibleAmount: number; partnerAmount: number; blendedRate?: number; nbfcYield?: number; notEligible: Record<string, number> }

async function download(url: string, name: string) {
  const t = getToken()
  const r = await fetch(url, { headers: t ? { Authorization: 'Bearer ' + t } : {} })
  if (!r.ok) throw new Error((await r.json().catch(() => ({})))?.message ?? 'Download failed')
  const u = URL.createObjectURL(await r.blob())
  const a = document.createElement('a')
  a.href = u; a.download = name; a.click()
  URL.revokeObjectURL(u)
}

/** Co-lending matcher (FSD 17.3): eligible pool per partner bank, reasons, partner file and approvals. */
export function CoLending() {
  const { user } = useAuth()
  const [rev, setRev] = useState(0)
  const pool = useLoad(() => api.get<{ candidates: number; partners: PoolPartner[] }>('/api/colending/pool'), [rev])
  const asg = useLoad(() => api.get<{ assignment: { id: number; status: string; batch: string; amount: number; partnerAmount: number; partnerRef?: string; note?: string; submittedAt: string }; partner: string; appNo: string; borrowerName: string }[]>('/api/colending/assignments'), [rev])
  const [sel, setSel] = useState<Record<number, number[]>>({})
  const [error, setError] = useState<unknown>()
  const [msg, setMsg] = useState<string>()
  const [edit, setEdit] = useState<Partner | null>(null)
  const canAct = user && ['ADMIN', 'CRO', 'OPERATIONS'].includes(user.role)
  async function submit(p: Partner) {
    setError(undefined)
    try {
      const r = await api.post<{ batch: string }>('/api/colending/submit', { partnerId: p.id, applicationIds: sel[p.id] ?? [] })
      setMsg(`Batch ${r.batch} sent to ${p.name}`); setSel({ ...sel, [p.id]: [] }); setRev(x => x + 1)
      await download(`/api/colending/batches/${r.batch}/file`, `${r.batch}.csv`)
    } catch (e) { setError(e) }
  }
  async function decide(id: number, approve: boolean) {
    const note = window.prompt(approve ? "Partner's reference (optional)" : "Partner's reason for rejecting") ?? undefined
    setError(undefined)
    try { await api.post(`/api/colending/assignments/${id}/decision`, approve ? { approve, partnerRef: note } : { approve, note }); setRev(x => x + 1) } catch (e) { setError(e) }
  }
  return (
    <>
      <Head title="Co-lending" sub="Every sanctioned or disbursed loan checked against each partner bank's rules. The borrower pays the blended rate; each lender keeps at least 10% of every loan (RBI co-lending directions, 2025)." />
      <ErrorBox error={error ?? pool.error} />
      {msg && <div className="alert ok">{msg}</div>}
      {pool.data && <p className="small muted">{pool.data.candidates} sanctioned or disbursed loans checked.</p>}
      {(pool.data?.partners ?? []).map(p => (
        <div key={p.partner.id} className="panel" style={{ marginBottom: 12 }} data-partner={p.partner.code}>
          <div className="row" style={{ justifyContent: 'space-between' }}>
            <h3 style={{ margin: 0 }}>{p.partner.name}</h3>
            {user && ['ADMIN', 'CRO'].includes(user.role) && <button className="btn sm" onClick={() => setEdit(p.partner)}>Rules</button>}
          </div>
          <p className="small muted">{p.partner.products} · up to {inr(p.partner.maxTicket)} · bands {p.partner.allowedBands}{p.partner.ntcAllowed ? '' : ' · no new-to-credit'}{p.partner.pslOnly ? ' · PSL only' : ''}{p.partner.minVintageYears > 0 ? ` · vintage ${p.partner.minVintageYears}+ years` : ''} · partner share {pct(p.partner.partnerShare, 0)} at {p.partner.partnerRate}%</p>
          <div className="kpis">
            <div className="kpi"><span>Eligible</span><b>{p.eligibleCount}</b><span>{lakh(p.eligibleAmount)}</span></div>
            <div className="kpi"><span>Partner's share</span><b>{lakh(p.partnerAmount)}</b></div>
            <div className="kpi"><span>Blended rate (borrower)</span><b>{p.blendedRate == null ? '—' : p.blendedRate + '%'}</b></div>
            <div className="kpi"><span>Our yield on our share</span><b>{p.nbfcYield == null ? '—' : p.nbfcYield + '%'}</b></div>
          </div>
          <Table head={['', 'Application', 'Borrower', 'Product', 'Amount', 'Partner share', 'Rate', 'Band', 'Status']} rows={p.eligible.map(e => [
            e.assignment ? '' : <input type="checkbox" aria-label={'Select ' + e.appNo} checked={(sel[p.partner.id] ?? []).includes(e.applicationId)}
              onChange={x => setSel({ ...sel, [p.partner.id]: x.target.checked ? [...(sel[p.partner.id] ?? []), e.applicationId] : (sel[p.partner.id] ?? []).filter(y => y !== e.applicationId) })} />,
            e.appNo, e.borrowerName, e.productCode, inr(e.amount), inr(e.partnerAmount), e.loanRate + '%', e.band, e.assignment ? <span className={'pill ' + (e.assignment.status === 'APPROVED' ? 'ok' : e.assignment.status === 'REJECTED' ? 'bad' : 'warn')}>{e.assignment.status.toLowerCase()}</span> : 'not sent'])}
            empty="No eligible loans." />
          {canAct && (sel[p.partner.id] ?? []).length > 0 && <button className="btn primary" style={{ marginTop: 8 }} onClick={() => submit(p.partner)}>Send {(sel[p.partner.id] ?? []).length} to the partner and download the file</button>}
          {Object.keys(p.notEligible).length > 0 && <details style={{ marginTop: 8 }}><summary className="small">Why other loans are not eligible</summary>
            <ul className="small">{Object.entries(p.notEligible).map(([k, v]) => <li key={k}>{k}: {v}</li>)}</ul></details>}
        </div>
      ))}
      <h3>Sent to partners</h3>
      <div className="panel flush">
        <Table head={['Batch', 'Partner', 'Application', 'Amount', 'Partner share', 'Status', 'Reference', '']} rows={(asg.data ?? []).map(x => [<span className="mono small">{x.assignment.batch}</span>, x.partner, `${x.appNo} · ${x.borrowerName}`,
          inr(x.assignment.amount), inr(x.assignment.partnerAmount), <span className={'pill ' + (x.assignment.status === 'APPROVED' ? 'ok' : x.assignment.status === 'REJECTED' ? 'bad' : 'warn')}>{x.assignment.status.toLowerCase()}</span>,
          x.assignment.partnerRef ?? x.assignment.note ?? '—',
          canAct && x.assignment.status === 'SUBMITTED' ? <span className="row"><button className="btn sm" onClick={() => decide(x.assignment.id, true)}>Partner approved</button><button className="btn sm" onClick={() => decide(x.assignment.id, false)}>Rejected</button></span>
            : <button className="btn sm" onClick={() => download(`/api/colending/batches/${x.assignment.batch}/file`, `${x.assignment.batch}.csv`).catch(setError)}>File</button>])} empty="Nothing sent yet." />
      </div>
      {edit && <PartnerEdit p={edit} onClose={() => setEdit(null)} onDone={() => { setEdit(null); setRev(x => x + 1) }} />}
    </>
  )
}

function PartnerEdit({ p, onClose, onDone }: { p: Partner; onClose: () => void; onDone: () => void }) {
  const [f, setF] = useState({ products: p.products, maxTicket: String(p.maxTicket), allowedBands: p.allowedBands, partnerShare: String(p.partnerShare), partnerRate: String(p.partnerRate), minVintageYears: String(p.minVintageYears), ntcAllowed: p.ntcAllowed, pslOnly: p.pslOnly, note: '' })
  const [error, setError] = useState<unknown>()
  async function save() {
    setError(undefined)
    try {
      await api.put(`/api/colending/partners/${p.id}`, { ...f, maxTicket: Number(f.maxTicket), partnerShare: Number(f.partnerShare), partnerRate: Number(f.partnerRate), minVintageYears: Number(f.minVintageYears) })
      onDone()
    } catch (e) { setError(e) }
  }
  return (
    <Modal title={`Rules: ${p.name}`} onClose={onClose}>
      <div className="grid2">
        <label>Products<input value={f.products} onChange={e => setF({ ...f, products: e.target.value })} /></label>
        <label>Maximum ticket (₹)<input value={f.maxTicket} onChange={e => setF({ ...f, maxTicket: e.target.value })} /></label>
        <label>Risk bands<input value={f.allowedBands} onChange={e => setF({ ...f, allowedBands: e.target.value })} /></label>
        <label>Partner share (0.1–0.9)<input value={f.partnerShare} onChange={e => setF({ ...f, partnerShare: e.target.value })} /></label>
        <label>Partner rate (%)<input value={f.partnerRate} onChange={e => setF({ ...f, partnerRate: e.target.value })} /></label>
        <label>Minimum vintage (years)<input value={f.minVintageYears} onChange={e => setF({ ...f, minVintageYears: e.target.value })} /></label>
        <label className="row"><input type="checkbox" checked={f.ntcAllowed} onChange={e => setF({ ...f, ntcAllowed: e.target.checked })} /> New-to-credit allowed</label>
        <label className="row"><input type="checkbox" checked={f.pslOnly} onChange={e => setF({ ...f, pslOnly: e.target.checked })} /> Priority-sector loans only</label>
      </div>
      <label>Agreement reference or reason<input value={f.note} onChange={e => setF({ ...f, note: e.target.value })} /></label>
      <ErrorBox error={error} />
      <button className="btn primary" onClick={save}>Save</button>
    </Modal>
  )
}

type Inputs = Record<string, number>
interface MonthRow { month: number; disbursed: number; principalCollected: number; interestIncome: number; interestExpense: number; opex: number; creditLoss: number; profit: number; book: number; borrowings: number; ownedFunds: number; cash: number; crar: number; alerts: string[] }
interface Projection { months: MonthRow[]; minCash: number; minCashMonth: number; minCrar: number; minCrarMonth: number; alerts: string[]; endBook: number; totalDisbursed: number; totalProfit: number }

const FIELDS: [string, string, string?][] = [
  ['ownedFunds', 'Net owned funds (₹)'], ['borrowings', 'Borrowings (₹)'], ['cash', 'Cash and liquid investments (₹)'], ['book', 'Loan book (₹)'],
  ['monthlyDisbursement', 'Disbursals next month (₹)'], ['disbursementGrowthPct', 'Monthly disbursal growth (%)'], ['collectionEfficiency', 'Collection efficiency (0–1)'],
  ['yieldPct', 'Yield (% a year)'], ['costOfFundsPct', 'Cost of funds (% a year)'], ['creditLossPct', 'Credit loss (% of book a year)'], ['opexPct', 'Operating cost (% of book a year)'],
  ['newBorrowingMonthly', 'New borrowing each month (₹)'], ['borrowingRepaymentMonthly', 'Borrowing repaid each month (₹)'], ['avgTenureMonths', 'Average tenure (months)'],
  ['minCrar', 'Minimum CRAR (0.15 = 15%)'], ['riskWeight', 'Risk weight (1 = 100%)'], ['months', 'Months to project'],
]

/** Capital and liquidity planning (FSD 19.5). */
export function Capital() {
  const { user } = useAuth()
  const def = useLoad(() => api.get<Inputs & { notes: string[] }>('/api/capital/defaults'), [])
  const [f, setF] = useState<Record<string, string>>({})
  const [p, setP] = useState<Projection>()
  const [name, setName] = useState('')
  const [error, setError] = useState<unknown>()
  const [rev, setRev] = useState(0)
  const saved = useLoad(() => api.get<{ id: number; name: string; createdBy: string; createdAt: string; inputsJson: string; resultJson: string }[]>('/api/capital/scenarios'), [rev])
  useEffect(() => { if (def.data) setF(Object.fromEntries(FIELDS.map(([k]) => [k, String(def.data![k] ?? '')]))) }, [def.data])
  const inputs = () => Object.fromEntries(FIELDS.map(([k]) => [k, Number(f[k])]))
  async function run() { setError(undefined); try { setP(await api.post<Projection>('/api/capital/project', inputs())) } catch (e) { setError(e) } }
  async function save() { setError(undefined); try { await api.post('/api/capital/scenarios', { name, inputs: inputs() }); setName(''); setRev(x => x + 1) } catch (e) { setError(e) } }
  const maxBook = p ? Math.max(...p.months.map(m => m.book), 1) : 1
  return (
    <>
      <Head title="Capital and liquidity plan" sub="Twelve months of loan book, collections, cash and CRAR from your plan, with alerts when cash runs out or capital falls below the minimum." />
      <ErrorBox error={error ?? def.error} />
      {def.data && <ul className="small muted">{def.data.notes.map(n => <li key={n}>{n}</li>)}</ul>}
      <div className="panel">
        <div className="grid2">{FIELDS.map(([k, l]) => <label key={k}>{l}<input inputMode="decimal" value={f[k] ?? ''} onChange={e => setF({ ...f, [k]: e.target.value })} /></label>)}</div>
        <div className="row"><button className="btn primary" data-action="project" onClick={run}>Project</button>
          {user && ['ADMIN', 'CRO'].includes(user.role) && p && <><input placeholder="Scenario name" value={name} onChange={e => setName(e.target.value)} /><button className="btn" onClick={save} disabled={!name.trim()}>Save scenario</button></>}</div>
      </div>
      {p && <>
        <div className="kpis" style={{ marginTop: 12 }} data-testid="capital-summary">
          <div className="kpi"><span>Book at the end</span><b>{lakh(p.endBook)}</b><span>{lakh(p.totalDisbursed)} disbursed</span></div>
          <div className="kpi"><span>Lowest cash</span><b className={p.minCash < 0 ? 'bad-text' : ''}>{lakh(p.minCash)}</b><span>month {p.minCashMonth}</span></div>
          <div className="kpi"><span>Lowest CRAR</span><b className={p.minCrar < Number(f.minCrar) ? 'bad-text' : ''}>{pct(p.minCrar, 1)}</b><span>month {p.minCrarMonth}</span></div>
          <div className="kpi"><span>Profit in the period</span><b>{lakh(p.totalProfit)}</b></div>
        </div>
        {p.alerts.length > 0 && <div className="alert bad" style={{ marginTop: 12 }}><ul className="small">{p.alerts.map(a => <li key={a}>{a}</li>)}</ul></div>}
        <div className="panel" style={{ marginTop: 12 }}>
          <h3>Loan book and cash</h3>
          <svg viewBox={`0 0 ${p.months.length * 50} 140`} style={{ width: '100%', maxWidth: 700 }} role="img" aria-label="Loan book by month">
            {p.months.map((m, i) => <g key={m.month}>
              <rect x={i * 50 + 8} width="20" y={120 - 100 * m.book / maxBook} height={100 * m.book / maxBook} fill="currentColor" opacity="0.4" />
              <rect x={i * 50 + 28} width="12" y={m.cash >= 0 ? 120 - 100 * m.cash / maxBook : 120} height={Math.abs(100 * m.cash / maxBook)} fill={m.cash < 0 ? 'var(--bad, #c0392b)' : 'var(--ok, #1e8449)'} />
              <text x={i * 50 + 24} y="136" fontSize="10" textAnchor="middle" fill="currentColor">{m.month}</text></g>)}
          </svg>
          <p className="small muted">Grey: loan book; green or red: cash (red when negative).</p>
        </div>
        <div className="panel flush" style={{ marginTop: 12 }}>
          <Table head={['Month', 'Disbursed', 'Principal in', 'Interest in', 'Interest out', 'Opex', 'Loss', 'Profit', 'Book', 'Borrowings', 'Owned funds', 'Cash', 'CRAR']}
            rows={p.months.map(m => [m.month, lakh(m.disbursed), lakh(m.principalCollected), lakh(m.interestIncome), lakh(m.interestExpense), lakh(m.opex), lakh(m.creditLoss), lakh(m.profit),
              lakh(m.book), lakh(m.borrowings), lakh(m.ownedFunds), <span className={m.cash < 0 ? 'bad-text' : ''}>{lakh(m.cash)}</span>, <span className={m.alerts.some(a => a.startsWith('CRAR')) ? 'bad-text' : ''}>{pct(m.crar, 1)}</span>])} />
        </div>
      </>}
      <h3 style={{ marginTop: 16 }}>Saved scenarios</h3>
      <div className="panel flush">
        <Table head={['Name', 'By', 'When', 'Lowest cash', 'Lowest CRAR']} onRow={i => { const s = saved.data![i]; const inp = JSON.parse(s.inputsJson); setF(Object.fromEntries(FIELDS.map(([k]) => [k, String(inp[k])]))); setP(JSON.parse(s.resultJson)) }}
          rows={(saved.data ?? []).map(s => { const r = JSON.parse(s.resultJson) as Projection; return [s.name, s.createdBy, when(s.createdAt), lakh(r.minCash), pct(r.minCrar, 1)] })} empty="No saved scenarios." />
      </div>
    </>
  )
}
