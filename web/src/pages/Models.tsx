import { useState } from 'react'
import { api } from '../api'
import { useAuth } from '../auth'
import { ErrorBox, Head, Pill, Table, useLoad } from '../components/ui'
import { pct, when } from '../format'

// Risk models: train a PD model on past loans, compare it with the model in use on hold-out loans,
// and the CRO (not the trainer) makes it the champion used by the decision engine.

interface ModelVersion {
  id: number; name: string; version: string; status: string; kind?: string; dataSource: string; rowsUsed: number
  trainedBy: string; trainedAt: string; decidedBy?: string; decidedAt?: string; decisionNote?: string
}
interface Score { auc: number; gini: number; ks: number; brier: number; avgPd: number; badRate: number }
interface Decile { decile: number; loans: number; avgPd: number; badRate: number }
interface Detail {
  model: ModelVersion
  terms: { feature: string; label: string; weight: number; mean: number; standardisedWeight: number; unexpectedSign: boolean }[]
  metrics: { train: Score; holdout: Score; champion?: Score; aucGain?: number; deciles: Decile[]; championDeciles?: Decile[]; psi: number; warnings: string[]; trainRows: number; holdoutRows: number; championName: string }
}

export default function Models() {
  const { user } = useAuth()
  const [rev, setRev] = useState(0)
  const list = useLoad(() => api.get<ModelVersion[]>('/api/models'), [rev])
  const [sel, setSel] = useState<number | null>(null)
  const [error, setError] = useState<unknown>()
  const [busy, setBusy] = useState(false)
  const models = list.data ?? []
  const champions = models.filter(m => m.status === 'CHAMPION')
  const champion = champions[0]
  const [skipped, setSkipped] = useState<string[]>([])
  const current = sel ?? models.find(m => m.status === 'CANDIDATE')?.id ?? champion?.id ?? models[0]?.id ?? null
  const canTrain = !!user && ['CREDIT_MANAGER', 'CRO', 'ADMIN'].includes(user.role)
  const [onlyOwn, setOnlyOwn] = useState(false)

  async function train() {
    setBusy(true)
    setError(undefined)
    try {
      const v = await api.post<{ candidates: ModelVersion[]; skipped: string[] }>('/api/models/train', { onlyOwn })
      setSel(v.candidates[0]?.id ?? null)
      setSkipped(v.skipped)
      setRev(r => r + 1)
    } catch (e) { setError(e) } finally { setBusy(false) }
  }
  async function trainFraud() {
    setBusy(true)
    setError(undefined)
    try {
      const v = await api.post<{ candidate: ModelVersion }>('/api/models/train-fraud', { onlyOwn })
      setSel(v.candidate.id)
      setRev(r => r + 1)
    } catch (e) { setError(e) } finally { setBusy(false) }
  }
  async function retire() {
    const note = window.prompt('Why go back to the built-in model? (at least 10 characters)')
    if (!note) return
    try { await api.post('/api/models/champion/retire', { note }); setRev(r => r + 1) } catch (e) { setError(e) }
  }

  return (
    <>
      <Head title="Risk models" sub="Train the probability-of-default model on past loans, compare it with the model in use, and approve it (CRO) before it decides anything.">
        {canTrain && <label className="check small"><input type="checkbox" checked={onlyOwn} onChange={e => setOnlyOwn(e.target.checked)} /> Only our own loans</label>}
        {canTrain && <button className="btn primary" disabled={busy} onClick={train} data-model="train">{busy ? 'Training…' : 'Train a new candidate'}</button>}
        {canTrain && <button className="btn" disabled={busy} onClick={trainFraud} data-model="train-fraud">Train a fraud model</button>}
      </Head>
      <ErrorBox error={error ?? list.error} />
      <div className="panel small">
        In use now: <b data-model="inuse">{['THICK', 'NTC'].map(k => {
          const c = champions.find(m => m.kind === k)
          return c ? `${k === 'THICK' ? 'bureau record' : 'new to credit'}: ${c.name} ${c.version} (approved by ${c.decidedBy})` : `${k === 'THICK' ? 'bureau record' : 'new to credit'}: built-in ${k === 'THICK' ? 'RHY-PD-THICK' : 'RHY-PD-NTC'}`
        }).join(' · ')} · fraud: {champions.find(m => m.kind === 'FRAUD') ? `${champions.find(m => m.kind === 'FRAUD')!.name} ${champions.find(m => m.kind === 'FRAUD')!.version}` : 'built-in fraud score'}</b>
        <div className="small muted">Separate models score applicants with a bureau record (THICK) and new-to-credit applicants (NTC); a separate fraud model learns from confirmed frauds and the fraud analysts' reviews.</div>
        {skipped.length > 0 && <div className="small muted">Not trained this time: {skipped.join('; ')}</div>}
        {champion && user?.role === 'CRO' && <> · <a href="#" onClick={e => { e.preventDefault(); retire() }}>go back to the built-in model</a></>}
      </div>
      <div className="policy-layout">
        <div className="panel flush">
          <Table head={['Model', 'Status', 'Loans']} onRow={i => setSel(models[i].id)} rows={models.map(m => [
            <span><b className={m.id === current ? '' : 'muted'}>{m.kind ?? ''} {m.version}</b></span>, <Pill s={m.status} />, <span className="small">{m.rowsUsed} · {m.dataSource.toLowerCase()}</span>,
          ])} empty="No trained models yet." />
        </div>
        <div>{current && <ModelDetail key={current + ':' + rev} id={current} onChange={() => setRev(r => r + 1)} />}</div>
      </div>
      <BuiltInMonitoring champions={champions.map(c => c.kind ?? '')} />
      {canTrain && <HistoryImport onDone={() => setRev(r => r + 1)} />}
    </>
  )
}

interface History { bySource: { source: string; loans: number; bad: number; fraud: number }[]; columns: string[]; recent: { ref: string; segment: string; product: string; amount: number; bad: boolean; fraud: boolean; batch: string }[] }

/** The lender's own past loans: paste or upload a CSV; each row becomes a training case with its 12-month outcome. */
function HistoryImport({ onDone }: { onDone: () => void }) {
  const [r, setR] = useState(0)
  const h = useLoad(() => api.get<History>('/api/models/history'), [r])
  const [csv, setCsv] = useState('')
  const [res, setRes] = useState<{ rows: number; added: number; updated: number; bad: number; fraud: number; errors: string[]; batch: string }>()
  const [error, setError] = useState<unknown>()
  async function load() {
    setError(undefined); setRes(undefined)
    const lines = csv.trim().split(/\r?\n/).filter(Boolean)
    const head = (lines[0] ?? '').split(',').map(x => x.trim())
    if (!head.includes('ref')) { setError(new Error('The first line must name the columns (ref, segment, amount, …)')); return }
    const rows = lines.slice(1).map(l => { const v = l.split(','); const o: Record<string, string> = {}; head.forEach((k, i) => { o[k] = (v[i] ?? '').trim() }); return o })
    try { setRes(await api.post('/api/models/history', { rows })); setR(x => x + 1); onDone() } catch (e) { setError(e) }
  }
  async function file(f?: File) { if (f) setCsv(await f.text()) }
  return (
    <div className="panel" data-testid="history-import" style={{ marginTop: 12 }}>
      <h3>Our own loan history</h3>
      <p className="small">Import past loans from your LMS so the models learn from your borrowers. Outcome: <b>bad</b> when the loan reached 90 days past due in its first 12 months or was written off; <b>fraud</b> when fraud was confirmed. Rows with the same reference are updated.</p>
      <Table head={['Source', 'Loans', 'Bad', 'Fraud']} rows={(h.data?.bySource ?? []).map(x => [x.source.toLowerCase(), x.loans, x.bad, x.fraud])} empty="No history yet." />
      <details className="small" style={{ margin: '8px 0' }}><summary>Columns</summary>
        <p className="mono">{(h.data?.columns ?? []).join(',')}</p>
        <p>Required: ref, segment (SALARIED, MSME or MICROFINANCE), amount, tenureMonths, age, declaredMonthlyIncome, monthlyCredits (6 to 12 months separated by |) or avgMonthlyCredits, and maxDpd or writtenOff. Leave bureauScore blank when there was no bureau record. Y/N for ever90, secured, docTamper, writtenOff and fraud.</p>
      </details>
      <textarea name="historyCsv" rows={5} value={csv} onChange={e => setCsv(e.target.value)} placeholder="ref,segment,amount,tenureMonths,age,declaredMonthlyIncome,monthlyCredits,bureauScore,maxDpd,fraud" style={{ width: '100%' }} />
      <div className="row" style={{ marginTop: 6 }}>
        <input type="file" accept=".csv,text/csv" aria-label="History CSV" onChange={e => file(e.target.files?.[0])} />
        <button className="btn primary" data-action="importHistory" disabled={!csv.trim()} onClick={load}>Import</button>
      </div>
      <ErrorBox error={error ?? h.error} />
      {res && <p className="small">{res.added} added and {res.updated} updated of {res.rows} ({res.bad} bad, {res.fraud} fraud) · batch {res.batch}
        {res.errors.length > 0 && <ul>{res.errors.slice(0, 20).map(e => <li key={e}>{e}</li>)}</ul>}</p>}
    </div>
  )
}

function ModelDetail({ id, onChange }: { id: number; onChange: () => void }) {
  const { user } = useAuth()
  const d = useLoad(() => api.get<Detail>(`/api/models/${id}`), [id])
  const [note, setNote] = useState('')
  const [error, setError] = useState<unknown>()
  if (!d.data) return <ErrorBox error={d.error} />
  const { model: m, metrics: x, terms } = d.data
  const canDecide = m.status === 'CANDIDATE' && user?.role === 'CRO' && user.username !== m.trainedBy
  const maxW = Math.max(0.01, ...terms.map(t => Math.abs(t.standardisedWeight)))
  const row = (label: string, f: (s: Score) => string, better: 'up' | 'down') => {
    const c = x.champion ? f(x.champion) : '—'
    const n = f(x.holdout)
    const delta = x.champion ? Number(n.replace('%', '')) - Number(c.replace('%', '')) : 0
    const good = better === 'up' ? delta > 0 : delta < 0
    return [label, c, <b className={x.champion && Math.abs(delta) > 1e-9 ? (good ? 'ok-text' : 'bad-text') : ''}>{n}</b>]
  }
  async function decide(approve: boolean) {
    try { await api.post(`/api/models/${id}/decision`, { approve, note }); onChange() } catch (e) { setError(e) }
  }
  return (
    <div data-model-detail={m.status}>
      <div className="panel">
        <h3 style={{ marginTop: 0 }}>{m.name} {m.version} <Pill s={m.status} /></h3>
        <p className="small muted" style={{ margin: 0 }}>
          Trained by {m.trainedBy} {when(m.trainedAt)} on {m.rowsUsed} past loans ({x.trainRows} to fit, {x.holdoutRows} held back to test)
          {m.dataSource === 'SYNTHETIC' && <b> · synthetic demo book: retrain on your own loan history before real use</b>}
          {m.decidedBy && <> · {m.status === 'REJECTED' ? 'rejected' : 'approved'} by {m.decidedBy} {when(m.decidedAt)}: {m.decisionNote}</>}
        </p>
      </div>
      {x.warnings.length > 0 && <div className="alert warn" data-model-warnings>{x.warnings.join('; ')}</div>}
      <div className="grid2">
        <div className="panel">
          <h3 style={{ marginTop: 0 }}>On the hold-out loans</h3>
          <Table head={['', `In use (${x.championName})`, 'This model']} rows={[
            row('AUC (ranking power)', s => s.auc.toFixed(3), 'up'),
            row('Gini', s => s.gini.toFixed(3), 'up'),
            row('KS', s => s.ks.toFixed(3), 'up'),
            row('Brier score (lower is better)', s => s.brier.toFixed(4), 'down'),
            ['Average predicted PD vs actual bad rate', x.champion ? `${pct(x.champion.avgPd, 1)} vs ${pct(x.champion.badRate, 1)}` : '—', `${pct(x.holdout.avgPd, 1)} vs ${pct(x.holdout.badRate, 1)}`],
          ]} />
          <p className="small muted">Train AUC {x.train.auc.toFixed(3)} vs hold-out {x.holdout.auc.toFixed(3)} (a large gap means over-fitting) · score stability (PSI) {x.psi.toFixed(3)}</p>
        </div>
        <div className="panel">
          <h3 style={{ marginTop: 0 }}>Does risk rise with the score? (hold-out deciles)</h3>
          <div className="deciles">
            {x.deciles.map((dd, i) => (
              <div key={dd.decile} title={`Decile ${dd.decile}: predicted ${pct(dd.avgPd, 1)}, actual ${pct(dd.badRate, 1)}`}>
                <span className="dec-actual" style={{ height: `${Math.min(100, dd.badRate * 400)}%` }} />
                {x.championDeciles?.[i] && <span className="dec-champ" style={{ height: `${Math.min(100, x.championDeciles[i].badRate * 400)}%` }} />}
              </div>
            ))}
          </div>
          <p className="small muted">Actual bad rate per tenth of loans, from lowest to highest predicted risk: this model (dark), model in use (light).</p>
        </div>
      </div>
      <div className="panel">
        <h3 style={{ marginTop: 0 }}>What the model learned</h3>
        <Table head={['Feature', 'Effect (per standard deviation)', 'Weight', 'Training average']} rows={[...terms].sort((a, b) => Math.abs(b.standardisedWeight) - Math.abs(a.standardisedWeight)).map(t => [
          <span>{t.label}{t.unexpectedSign && <span className="pill warn" style={{ marginLeft: 6 }}>unexpected direction</span>}</span>,
          <div className="row" style={{ flexWrap: 'nowrap', gap: 6 }}><div className="bar" style={{ width: 120 }}><span style={{ width: `${Math.abs(t.standardisedWeight) / maxW * 100}%`, background: t.standardisedWeight > 0 ? 'var(--bad)' : 'var(--ok)' }} /></div>
            <span className="small num">{t.standardisedWeight > 0 ? 'raises' : 'lowers'} risk</span></div>,
          <span className="num small">{t.weight.toFixed(4)}</span>, <span className="num small">{t.mean.toFixed(3)}</span>,
        ])} />
      </div>
      <Monitoring url={`/api/models/${id}/monitoring`} />
      {canDecide && (
        <div className="panel">
          <h3 style={{ marginTop: 0 }}>Your decision</h3>
          <p className="small muted">Approving makes this model decide every new application. The policy rules and limits stay as they are. You can go back to the built-in model at any time.</p>
          <ErrorBox error={error} />
          <label className="fld">Note (required)<textarea name="modelNote" rows={2} value={note} onChange={e => setNote(e.target.value)} /></label>
          <div className="row" style={{ marginTop: 8 }}>
            <button className="btn primary" data-model="approve" onClick={() => decide(true)}>Approve as champion</button>
            <button className="btn danger" data-model="reject" onClick={() => decide(false)}>Reject</button>
          </div>
        </div>
      )}
    </div>
  )
}

interface PsiRow { feature: string; label: string; psi: number; status: string; live: number; bins: { range: string; expected: number; actual: number }[] }
interface MonitoringView {
  kind: string; reference: string; liveDecisions: number; drifting: number; psi: PsiRow[]
  shadow?: { scored: number; bandAgreement?: number; decisionAgreement?: number; avgPdDifference?: number; disagreements?: { applicationId: number; champion: string; candidate: string }[] }
}

const PSI_PILL: Record<string, string> = { STABLE: 'ok', WATCH: 'warn', DRIFT: 'bad', TOO_FEW: 'muted' }

/** Model governance: shadow agreement and population stability (PSI) per feature on live decisions. */
function Monitoring({ url, title }: { url: string; title?: string }) {
  const m = useLoad(() => api.get<MonitoringView>(url), [url])
  const [open, setOpen] = useState<string | null>(null)
  if (!m.data) return <ErrorBox error={m.error} />
  const d = m.data
  return (
    <div className="panel" data-model-monitoring>
      <h3 style={{ marginTop: 0 }}>{title ?? 'Monitoring'}</h3>
      {d.shadow && (d.shadow.scored === 0
        ? <p className="small muted">Shadow mode: no decisions scored yet. Every new decision is also scored by this candidate, without acting on it.</p>
        : <div className="kpis" data-shadow>
            <div className="kpi"><span>Scored in shadow</span><b>{d.shadow.scored}</b></div>
            <div className="kpi"><span>Same band as the model in use</span><b>{pct(d.shadow.bandAgreement ?? 0, 0)}</b></div>
            <div className="kpi"><span>Same decision</span><b>{pct(d.shadow.decisionAgreement ?? 0, 0)}</b></div>
            <div className="kpi"><span>Average PD difference</span><b>{((d.shadow.avgPdDifference ?? 0) * 100).toFixed(2)} pts</b></div>
          </div>)}
      {d.shadow?.disagreements && d.shadow.disagreements.length > 0 && (
        <Table head={['Application', 'Model in use', 'This candidate']} rows={d.shadow.disagreements.map(x => [<a href={`/applications/${x.applicationId}`}>#{x.applicationId}</a>, x.champion, x.candidate])} />
      )}
      <p className="small">Drift against the {d.reference} on {d.liveDecisions} live {d.kind === 'THICK' ? 'bureau-record' : 'new-to-credit'} decisions: <b className={d.drifting ? 'bad-text' : ''}>{d.drifting} feature(s) drifting</b> (PSI above 0.25; 0.10–0.25 is worth watching).</p>
      <Table head={['Feature', 'PSI', 'Status', '']} rows={d.psi.map(p => [p.label, p.psi.toFixed(3), <span className={'pill ' + (PSI_PILL[p.status] ?? '')}>{p.status === 'TOO_FEW' ? 'Too few decisions' : p.status.toLowerCase()}</span>,
        <button className="btn sm" onClick={() => setOpen(open === p.feature ? null : p.feature)}>{open === p.feature ? 'Hide' : 'Bins'}</button>])} />
      {open && (() => {
        const p = d.psi.find(x => x.feature === open)!
        return <Table head={['Range', 'Reference share', 'Live share']} rows={p.bins.map(b => [b.range, pct(b.expected, 0), pct(b.actual, 0)])} />
      })()}
    </div>
  )
}

function BuiltInMonitoring({ champions }: { champions: string[] }) {
  const kinds = ['THICK', 'NTC'].filter(k => !champions.includes(k))
  if (kinds.length === 0) return null
  return <>{kinds.map(k => <Monitoring key={k} url={`/api/models/monitoring/built-in/${k}`} title={`Drift for the built-in ${k === 'THICK' ? 'bureau-record' : 'new-to-credit'} model`} />)}</>
}
