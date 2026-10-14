import { useState } from 'react'
import { api } from '../api'
import { ErrorBox, Head, Table } from '../components/ui'
import { inr, lakh } from '../format'

interface Outcome { changePp: number; loans: number; volume: number; yield: number; nim: number; expectedLossRate: number; riskAdjustedReturn: number; annualNetIncome: number; capped: number }
interface Sim { mixLoans: number; base: Outcome; scenario: Outcome; curve: Outcome[]; bestChangeBps: number; notes: string[] }

/** Rate simulator: what a change in lending rates does to volume, yield, NIM, expected loss and income. */
export default function Pricing() {
  const [f, setF] = useState({ changeBps: '100', productCode: '', segment: '', elasticityPerPp: '6', adverseSelectionPerPp: '5', costOfFunds: '11' })
  const [sim, setSim] = useState<Sim>()
  const [error, setError] = useState<unknown>()
  async function run() {
    setError(undefined)
    try {
      setSim(await api.post<Sim>('/api/pricing/simulate', { changeBps: Number(f.changeBps), productCode: f.productCode || undefined, segment: f.segment || undefined,
        elasticityPerPp: Number(f.elasticityPerPp), adverseSelectionPerPp: Number(f.adverseSelectionPerPp), costOfFunds: Number(f.costOfFunds) }))
    } catch (e) { setError(e) }
  }
  const rows: [string, (o: Outcome) => string][] = [['Loans (a year)', o => String(o.loans)], ['Volume', o => lakh(o.volume)], ['Yield', o => o.yield + '%'], ['NIM', o => o.nim + '%'],
    ['Expected loss', o => o.expectedLossRate + '%'], ['Risk-adjusted return', o => o.riskAdjustedReturn + '%'], ['Net income a year', o => inr(o.annualNetIncome)], ['Capped at the maximum rate', o => String(o.capped)]]
  const max = sim ? Math.max(...sim.curve.map(c => Math.abs(c.annualNetIncome)), 1) : 1
  return (
    <>
      <Head title="Rate simulator" sub="Change lending rates on the current book's mix and see volume, yield, NIM, expected loss and risk-adjusted income." />
      <div className="panel">
        <div className="grid2">
          <label>Rate change (basis points)<input inputMode="numeric" value={f.changeBps} onChange={e => setF({ ...f, changeBps: e.target.value })} /></label>
          <label>Cost of funds (%)<input inputMode="decimal" value={f.costOfFunds} onChange={e => setF({ ...f, costOfFunds: e.target.value })} /></label>
          <label>Product (blank = all)<input value={f.productCode} onChange={e => setF({ ...f, productCode: e.target.value.toUpperCase() })} /></label>
          <label>Segment<select value={f.segment} onChange={e => setF({ ...f, segment: e.target.value })}><option value="">All</option>{['SALARIED', 'SELF_EMPLOYED', 'MSME', 'NTC'].map(s => <option key={s}>{s}</option>)}</select></label>
          <label>Volume lost per 1% of rate (%)<input inputMode="decimal" value={f.elasticityPerPp} onChange={e => setF({ ...f, elasticityPerPp: e.target.value })} /></label>
          <label>PD rise per 1% of rate (%)<input inputMode="decimal" value={f.adverseSelectionPerPp} onChange={e => setF({ ...f, adverseSelectionPerPp: e.target.value })} /></label>
        </div>
        <button className="btn primary" data-action="simulate" onClick={run}>Simulate</button>
      </div>
      <ErrorBox error={error} />
      {sim && <>
        <div className="panel flush" style={{ marginTop: 12 }}>
          <Table head={['', 'Today', `${Number(f.changeBps) > 0 ? '+' : ''}${f.changeBps} bps`]} rows={rows.map(([l, g]) => [l, g(sim.base), g(sim.scenario)])} />
        </div>
        <div className="panel" style={{ marginTop: 12 }}>
          <h3>Net income a year by rate change</h3>
          <svg viewBox="0 0 650 160" style={{ width: '100%', maxWidth: 650 }} role="img" aria-label="Net income by rate change">
            <line x1="0" x2="650" y1="80" y2="80" stroke="currentColor" opacity="0.3" />
            {sim.curve.map((c, i) => {
              const h = 70 * c.annualNetIncome / max
              return <g key={i}><rect x={i * 50 + 8} width="34" y={h >= 0 ? 80 - h : 80} height={Math.abs(h)} fill={Math.round(c.changePp * 100) === sim.bestChangeBps ? 'var(--accent, #2f6fde)' : 'currentColor'} opacity={Math.round(c.changePp * 100) === sim.bestChangeBps ? 1 : 0.35} />
                <text x={i * 50 + 25} y="156" fontSize="10" textAnchor="middle" fill="currentColor">{c.changePp > 0 ? '+' : ''}{Math.round(c.changePp * 100)}</text></g>
            })}
          </svg>
          <p className="small">Highest risk-adjusted income at <b>{sim.bestChangeBps > 0 ? '+' : ''}{sim.bestChangeBps} bps</b> under these assumptions ({sim.mixLoans} loans in the mix).</p>
          <ul className="small muted">{sim.notes.map(n => <li key={n}>{n}</li>)}</ul>
        </div>
      </>}
    </>
  )
}
