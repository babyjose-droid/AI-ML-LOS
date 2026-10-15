import { useState } from 'react'
import { api } from '../api'
import { useAuth } from '../auth'
import { ErrorBox, Head, Table, useLoad } from '../components/ui'
import { inr, pct, when } from '../format'

interface TenantRow { code: string; name: string; schema_name: string; status: string; benchmarking_opt_in: boolean; created_at: string; created_by: string; go_live_approved_at?: string; users?: number; applications?: number }

/** Platform operator: tenants (lenders) on this installation. */
export function Platform() {
  const [rev, setRev] = useState(0)
  const l = useLoad(() => api.get<TenantRow[]>('/api/platform/tenants'), [rev])
  const [f, setF] = useState({ code: '', name: '' })
  const [made, setMade] = useState<{ code: string; adminUsername: string; adminPassword: string; note: string }>()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>()
  async function create() {
    setBusy(true); setError(undefined)
    try { setMade(await api.post('/api/platform/tenants', f)); setF({ code: '', name: '' }); setRev(x => x + 1) } catch (e) { setError(e) } finally { setBusy(false) }
  }
  async function status(code: string, s: 'suspend' | 'reactivate') {
    setError(undefined)
    try { await api.post(`/api/platform/tenants/${code}/${s}`); setRev(x => x + 1) } catch (e) { setError(e) }
  }
  return (
    <>
      <Head title="Tenants" sub="Each lender has its own database schema, users, products, policy and files. Only an admin of the default tenant (the platform operator) sees this page." />
      <ErrorBox error={error ?? l.error} />
      <div className="panel flush">
        <Table head={['Code', 'Lender', 'Schema', 'Status', 'Users', 'Applications', 'Created', '']} rows={(l.data ?? []).map(t => [
          <span className="mono">{t.code}</span>, t.name, <span className="mono small">{t.schema_name}</span>,
          <span className={'pill ' + (t.status === 'LIVE' ? 'ok' : t.status === 'SUSPENDED' ? 'bad' : 'warn')}>{t.status.toLowerCase()}</span>, t.users ?? '—', t.applications ?? '—',
          <span className="small">{when(t.created_at)} · {t.created_by}</span>,
          t.code === 'default' ? '' : t.status === 'SUSPENDED' ? <button className="btn sm" onClick={() => status(t.code, 'reactivate')}>Reactivate</button>
            : <button className="btn sm" onClick={() => status(t.code, 'suspend')}>Suspend</button>])} />
      </div>
      <div className="panel" style={{ marginTop: 12 }}>
        <h3>New tenant</h3>
        <div className="grid2">
          <label>Organisation code (3–16 lower-case letters or digits)<input value={f.code} onChange={e => setF({ ...f, code: e.target.value.toLowerCase() })} /></label>
          <label>Lender's name<input value={f.name} onChange={e => setF({ ...f, name: e.target.value })} /></label>
        </div>
        <button className="btn primary" disabled={busy || !f.code || !f.name} onClick={create}>{busy ? 'Creating the database…' : 'Create tenant'}</button>
        <p className="small muted">Creates the schema from the current release with the template products, policy, workflows and collateral rules, and one admin user. Demo users and branches are not copied.</p>
      </div>
      {made && <div className="alert ok" style={{ marginTop: 12 }} data-testid="tenant-created">
        Tenant <b>{made.code}</b> created. Admin: <b>{made.adminUsername}</b>, temporary password <b className="mono">{made.adminPassword}</b>. {made.note}
      </div>}
    </>
  )
}

interface Step { step: number; title: string; what: string; evidence: string; ready: boolean; needsConfirm: boolean; confirmedBy?: string; confirmedAt?: string; done: boolean }
interface Check { id: string; name: string; passed: boolean; detail: string }
interface Onb { code: string; name: string; status: string; benchmarkingOptIn: boolean; steps: Step[]; sandbox?: { ranAt: string; ranBy: string; total: number; passed: number; allPassed: boolean; checks: Check[] }; rollbackPlan?: string; goLiveRequestedBy?: string; goLiveApprovedBy?: string }

/** Tenant onboarding (SCR-094): the ten steps to go live. */
export function Onboarding() {
  const { user } = useAuth()
  const [rev, setRev] = useState(0)
  const o = useLoad(() => api.get<Onb>('/api/onboarding'), [rev])
  const [plan, setPlan] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>()
  async function act(fn: () => Promise<unknown>) { setBusy(true); setError(undefined); try { await fn(); setRev(x => x + 1) } catch (e) { setError(e) } finally { setBusy(false) } }
  const admin = user?.role === 'ADMIN'
  return (
    <>
      <Head title="Onboarding" sub={o.data ? `${o.data.name} (${o.data.code}) · ${o.data.status.toLowerCase()}` : ''} />
      <ErrorBox error={error ?? o.error} />
      {o.data && <>
        <div className="panel flush">
          <Table head={['', 'Step', 'What it needs', 'Where it stands', '']} rows={o.data.steps.map(s => [
            <span className={'pill ' + (s.done ? 'ok' : s.ready ? 'warn' : 'muted')}>{s.done ? 'done' : s.ready ? 'to confirm' : 'open'}</span>,
            <b>{s.step}. {s.title}</b>, <span className="small">{s.what}</span>,
            <span className="small">{s.evidence}{s.confirmedBy && <div className="muted">confirmed by {s.confirmedBy}, {when(s.confirmedAt!)}</div>}</span>,
            admin && s.needsConfirm && !s.confirmedBy && s.ready ? <button className="btn sm" onClick={() => act(() => api.post(`/api/onboarding/steps/${s.step}/confirm`))}>Confirm</button> : ''])} />
        </div>
        <div className="grid2" style={{ marginTop: 12 }}>
          <div className="panel">
            <h3>Sandbox test pack</h3>
            {o.data.sandbox ? <p className="small">{o.data.sandbox.passed} of {o.data.sandbox.total} passed · {when(o.data.sandbox.ranAt)} by {o.data.sandbox.ranBy}</p> : <p className="small muted">Not run yet.</p>}
            {o.data.sandbox && <ul className="small" data-testid="sandbox-checks">{o.data.sandbox.checks.map(c => <li key={c.id}><span className={'pill ' + (c.passed ? 'ok' : 'bad')}>{c.passed ? 'pass' : 'fail'}</span> {c.id} {c.name}: <span className="muted">{c.detail}</span></li>)}</ul>}
            {(admin || user?.role === 'CRO') && <button className="btn" disabled={busy} onClick={() => act(() => api.post('/api/onboarding/sandbox'))}>{busy ? 'Running…' : 'Run the 12 checks'}</button>}
          </div>
          <div className="panel">
            <h3>Go-live</h3>
            {o.data.status === 'LIVE' ? <p>Live{o.data.goLiveApprovedBy && <> · approved by {o.data.goLiveApprovedBy}</>}.</p> : o.data.goLiveRequestedBy ? <>
              <p className="small">Requested by {o.data.goLiveRequestedBy}. Rollback plan: {o.data.rollbackPlan}</p>
              {user?.role === 'CRO' && <button className="btn primary" onClick={() => act(() => api.post('/api/onboarding/go-live/approve'))}>Approve go-live</button>}
            </> : admin ? <>
              <label>Rollback plan<textarea rows={3} value={plan} onChange={e => setPlan(e.target.value)} /></label>
              <button className="btn primary" onClick={() => act(() => api.post('/api/onboarding/go-live/request', { rollbackPlan: plan }))}>Request go-live</button>
              <p className="small muted">Steps 1 to 9 must be done. The CRO approves.</p>
            </> : <p className="small muted">Not requested yet.</p>}
            <h3 style={{ marginTop: 16 }}>Benchmarking</h3>
            <p className="small">{o.data.benchmarkingOptIn ? 'Taking part: anonymised figures are shared with peers.' : 'Not taking part.'} Peers are shown only when at least five lenders take part.</p>
            {admin && <button className="btn sm" onClick={() => act(() => api.post('/api/onboarding/benchmarking', { optIn: !o.data!.benchmarkingOptIn }))}>{o.data.benchmarkingOptIn ? 'Stop sharing' : 'Take part'}</button>}
          </div>
        </div>
      </>}
    </>
  )
}

interface Bm { optedIn: boolean; minPeers: number; includesSyntheticPeers: boolean; period: string; metrics: { key: string; label: string; unit: string; higherIsBetter: boolean; mine?: number; lenders: number; p25?: number; median?: number; p75?: number; quartile?: number }[] }

const fmt = (v: number | undefined, unit: string) => v == null || Number.isNaN(v) ? '—' : unit === 'share' ? pct(v, 1) : unit === 'days' ? v.toFixed(1) + ' days' : unit === '₹' ? inr(Math.round(v)) : v.toFixed(2) + '%'

/** Anonymised benchmarking against peer lenders (FSD 19.5). */
export function Benchmarking() {
  const { user } = useAuth()
  const [rev, setRev] = useState(0)
  const b = useLoad(() => api.get<Bm>('/api/benchmarking'), [rev])
  const [error, setError] = useState<unknown>()
  async function refresh() { setError(undefined); try { await api.post('/api/benchmarking/refresh'); setRev(x => x + 1) } catch (e) { setError(e) } }
  return (
    <>
      <Head title="Benchmarking" sub="Your figures against anonymised peer lenders. Peers are never named; quartiles appear only when at least five lenders take part.">
        {user?.role === 'ADMIN' && <button className="btn" onClick={refresh}>Recompute</button>}
      </Head>
      <ErrorBox error={error ?? b.error} />
      {b.data && <>
        {!b.data.optedIn && <div className="alert">Your organisation is not taking part. The tenant admin can opt in on the Onboarding page; until then only your own figures are shown.</div>}
        {b.data.includesSyntheticPeers && <div className="alert warn">Demo: the peer group includes five synthetic lenders.</div>}
        <div className="panel flush">
          <Table head={['Measure', 'You', 'Lower quartile', 'Median', 'Upper quartile', 'Your position', 'Lenders']} rows={b.data.metrics.map(m => [
            <span>{m.label}<div className="small muted">{m.higherIsBetter ? 'higher is better' : 'lower is better'}</div></span>, fmt(m.mine, m.unit), fmt(m.p25, m.unit), fmt(m.median, m.unit), fmt(m.p75, m.unit),
            m.quartile ? <span className={'pill ' + (m.quartile === 1 ? 'ok' : m.quartile === 4 ? 'bad' : 'warn')}>{['', 'top quartile', 'second', 'third', 'bottom quartile'][m.quartile]}</span> : <span className="small muted">{m.lenders < b.data!.minPeers ? `needs ${b.data!.minPeers} lenders` : '—'}</span>,
            m.lenders])} />
        </div>
        <p className="small muted">Period {b.data.period}. Origination measures cover the last 180 days; portfolio measures use the live loan book.</p>
      </>}
      <CostPerFile />
    </>
  )
}

interface Cost {
  days: number; files: number; disbursed: number; directPerDisbursed?: number; vendorPerDisbursed?: number; staffPerDisbursed?: number; allInPerDisbursed?: number; perFileStarted?: number
  byProduct: { product: string; files: number; disbursed: number; directPerDisbursed?: number; allInPerDisbursed?: number }[]
  byItem: { item: string; label: string; kind: string; uses: number; cost: number }[]
}
interface Rate { item: string; label: string; kind: string; unitCost: number; updatedBy: string; updatedAt: string }

/** What a loan file costs: vendor calls and staff handling at the lender's own rates. */
function CostPerFile() {
  const { user } = useAuth()
  const [days, setDays] = useState(180)
  const [r, setR] = useState(0)
  const c = useLoad(() => api.get<Cost>(`/api/benchmarking/cost?days=${days}`), [days, r])
  const rates = useLoad(() => api.get<Rate[]>('/api/benchmarking/cost-rates'), [r])
  const [edit, setEdit] = useState<Record<string, string>>({})
  const [error, setError] = useState<unknown>()
  const canEdit = !!user && ['ADMIN', 'CRO'].includes(user.role)
  async function save() {
    setError(undefined)
    try {
      await api.put('/api/benchmarking/cost-rates', { rates: Object.entries(edit).map(([item, v]) => ({ item, unitCost: Number(v) })) })
      setEdit({}); setR(x => x + 1)
    } catch (e) { setError(e) }
  }
  const money = (v?: number | null) => v == null ? '—' : inr(v)
  return (
    <div className="panel" data-testid="cost-per-file" style={{ marginTop: 12 }}>
      <div className="row" style={{ justifyContent: 'space-between' }}>
        <h3 style={{ margin: 0 }}>Cost per file</h3>
        <label className="small">Period <select value={days} onChange={e => setDays(Number(e.target.value))}>{[30, 90, 180, 365].map(d => <option key={d} value={d}>last {d} days</option>)}</select></label>
      </div>
      <ErrorBox error={error ?? c.error} />
      {c.data && <>
        <div className="grid2" style={{ marginTop: 8 }}>
          <dl className="dl">
            <dt>Files started</dt><dd>{c.data.files}</dd><dt>Disbursed</dt><dd>{c.data.disbursed}</dd>
            <dt>Cost of a disbursed file</dt><dd><b>{money(c.data.directPerDisbursed)}</b> (vendors {money(c.data.vendorPerDisbursed)}, staff {money(c.data.staffPerDisbursed)})</dd>
          </dl>
          <dl className="dl">
            <dt>All-in per disbursed file</dt><dd><b>{money(c.data.allInPerDisbursed)}</b><div className="small muted">includes the files rejected or withdrawn along the way</div></dd>
            <dt>Average per file started</dt><dd>{money(c.data.perFileStarted)}</dd>
          </dl>
        </div>
        <Table head={['Product', 'Files', 'Disbursed', 'Per disbursed file', 'All-in']} rows={c.data.byProduct.map(p => [p.product, p.files, p.disbursed, money(p.directPerDisbursed), money(p.allInPerDisbursed)])} empty="No files in this period." />
        <h4>Where the money goes</h4>
        <Table head={['Item', 'Kind', 'Uses', 'Cost']} rows={c.data.byItem.slice(0, 12).map(i => [i.label, i.kind === 'VENDOR' ? 'vendor' : 'staff', i.uses, inr(i.cost)])} empty="Nothing yet." />
      </>}
      <details style={{ marginTop: 8 }}><summary className="small">Rates used</summary>
        <Table head={['Item', 'Kind', '₹ each', 'Changed']} rows={(rates.data ?? []).map(x => [x.label, x.kind === 'VENDOR' ? 'per successful call' : 'per stage completed',
          canEdit ? <input inputMode="decimal" style={{ maxWidth: 100 }} aria-label={`Rate for ${x.label}`} value={edit[x.item] ?? String(x.unitCost)} onChange={e => setEdit({ ...edit, [x.item]: e.target.value })} /> : inr(x.unitCost),
          <span className="small muted">{x.updatedBy} · {when(x.updatedAt)}</span>])} />
        {canEdit && Object.keys(edit).length > 0 && <button className="btn primary" onClick={save}>Save rates</button>}
      </details>
    </div>
  )
}
