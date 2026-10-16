import { useState } from 'react'
import { api, getToken } from '../api'
import { useAuth } from '../auth'
import { ErrorBox, Head, Table, useLoad, useTab } from '../components/ui'
import { inr, lakh, pct, when } from '../format'

interface Item { id: string; area: string; requirement: string; source: string; status: string; checked: number; exceptions: number; samples: string[]; evidence: string }
interface Pack { generatedAt: string; policyVersion: string; items: Item[]; pass: number; exceptions: number; notApplicable: number }
type Row = { key: string; loans: number; outstanding: number; share: number; gnpa: number }
type Count = { loans: number; amount: number }

async function download(url: string, name: string, onError: (e: unknown) => void) {
  const t = getToken()
  const r = await fetch(url, { headers: t ? { Authorization: 'Bearer ' + t } : {} })
  if (!r.ok) { onError(new Error((await r.json().catch(() => ({})))?.message ?? 'Download failed')); return }
  const u = URL.createObjectURL(await r.blob())
  const a = document.createElement('a')
  a.href = u; a.download = name; a.click()
  URL.revokeObjectURL(u)
}

const ST: Record<string, string> = { PASS: 'ok', EXCEPTION: 'bad', NOT_APPLICABLE: 'muted' }

/** Compliance pack and regulatory returns. */
export default function Compliance() {
  const { user } = useAuth()
  const [tab, setTab] = useTab(['pack', 'classification', 'movement', 'exposure', 'cic'] as const, user && ['ADMIN', 'COMPLIANCE', 'CRO'].includes(user.role) ? 'pack' : 'classification')
  const canPack = user && ['ADMIN', 'COMPLIANCE', 'CRO'].includes(user.role)
  return (
    <>
      <Head title="Compliance and regulatory reports" sub="Checks computed from the records themselves, and the returns built from the loan book." />
      <div className="tabs" role="tablist">
        {([['pack', 'Compliance pack'], ['classification', 'Asset classification'], ['movement', 'NPA movement'], ['exposure', 'Exposure'], ['cic', 'Credit bureau file']] as const)
          .filter(([k]) => k !== 'pack' || canPack).map(([k, l]) => <button key={k} role="tab" aria-selected={tab === k} className={tab === k ? 'on' : ''} onClick={() => setTab(k)}>{l}</button>)}
      </div>
      {tab === 'pack' && <PackView />}
      {tab === 'classification' && <Classification />}
      {tab === 'movement' && <Movement />}
      {tab === 'exposure' && <Exposure />}
      {tab === 'cic' && <Cic />}
    </>
  )
}

function PackView() {
  const p = useLoad(() => api.get<Pack>('/api/compliance/pack'), [])
  const [error, setError] = useState<unknown>()
  const [open, setOpen] = useState<string | null>(null)
  return (
    <>
      <ErrorBox error={error ?? p.error} />
      {p.data && <>
        <div className="kpis" data-testid="pack-summary">
          <div className="kpi"><span>Passed</span><b>{p.data.pass}</b></div>
          <div className="kpi"><span>Exceptions</span><b className={p.data.exceptions ? 'bad-text' : ''}>{p.data.exceptions}</b></div>
          <div className="kpi"><span>Not applicable yet</span><b>{p.data.notApplicable}</b></div>
          <div className="kpi"><span>Generated</span><b className="small">{when(p.data.generatedAt)}</b><span>policy {p.data.policyVersion}</span></div>
        </div>
        <div className="row" style={{ margin: '12px 0' }}>
          <button className="btn" onClick={() => download('/api/compliance/pack.csv', `compliance-pack-${new Date().toISOString().slice(0, 10)}.csv`, setError)}>Download pack (CSV)</button>
        </div>
        <div className="panel flush">
          <Table head={['', 'Area', 'Requirement', 'Checked', 'Exceptions', 'Status']} onRow={i => setOpen(open === p.data!.items[i].id ? null : p.data!.items[i].id)}
            rows={p.data.items.map(i => [i.id, i.area, <span>{i.requirement}<div className="small muted">{i.source}</div>
              {open === i.id && <div className="small" style={{ marginTop: 6 }}><b>Evidence:</b> {i.evidence}{i.samples.length > 0 && <ul>{i.samples.map(s => <li key={s}>{s}</li>)}</ul>}</div>}</span>,
              i.checked, i.exceptions, <span className={'pill ' + ST[i.status]}>{i.status.replace('_', ' ').toLowerCase()}</span>])} />
        </div>
      </>}
    </>
  )
}

function Classification() {
  const a = useLoad(() => api.get<{ asOf: string; outstanding: number; gnpa: number; nnpa: number; provisionIracp: number; ecl: number; provisionHeld: number; impairmentReserve: number
    classification: Record<string, { loans: number; outstanding: number; provisionIracp: number; ecl: number }>; npaByAge: { class: string; loans: number; outstanding: number; provisionIracp: number }[] }>('/api/regulatory/asset-classification'), [])
  return (
    <>
      <ErrorBox error={a.error} />
      {a.data && <>
        <div className="kpis">
          <div className="kpi"><span>Outstanding</span><b>{lakh(a.data.outstanding)}</b></div>
          <div className="kpi"><span>Gross / net NPA</span><b>{pct(a.data.gnpa, 2)}</b><span>net {pct(a.data.nnpa, 2)}</span></div>
          <div className="kpi"><span>Provision held</span><b>{lakh(a.data.provisionHeld)}</b><span>ECL {lakh(a.data.ecl)} · RBI minimum {lakh(a.data.provisionIracp)}</span></div>
          <div className="kpi"><span>Impairment reserve</span><b>{lakh(a.data.impairmentReserve)}</b></div>
        </div>
        <div className="grid2" style={{ marginTop: 12 }}>
          <div className="panel flush"><Table head={['Class', 'Loans', 'Outstanding', 'RBI minimum', 'ECL']} rows={Object.entries(a.data.classification).map(([k, v]) => [k, v.loans, inr(v.outstanding), inr(v.provisionIracp), inr(v.ecl)])} /></div>
          <div className="panel flush"><Table head={['NPA by age', 'Loans', 'Outstanding', 'RBI minimum']} rows={a.data.npaByAge.map(r => [r.class.toLowerCase(), r.loans, inr(r.outstanding), inr(r.provisionIracp)])} /></div>
        </div>
        <p className="small muted">As of {a.data.asOf}. Sub-standard: NPA up to 12 months; doubtful 1/2/3: up to 1, 1–3 and above 3 years in doubtful.</p>
      </>}
    </>
  )
}

function Movement() {
  const today = new Date()
  const first = new Date(today.getFullYear(), today.getMonth() - 1, 1).toISOString().slice(0, 10)
  const last = new Date(today.getFullYear(), today.getMonth(), 0).toISOString().slice(0, 10)
  const [f, setF] = useState({ from: first, to: last })
  const [q, setQ] = useState(f)
  const m = useLoad(() => api.get<Record<string, Count> & { netReductionInExisting: number }>(`/api/regulatory/npa-movement?from=${q.from}&to=${q.to}`), [q])
  const rows: [string, string][] = [['opening', 'Opening gross NPA'], ['additions', 'Add: new NPAs'], ['upgradations', 'Less: upgraded to standard'], ['recoveries', 'Less: recovered / closed'], ['writeOffs', 'Less: written off'], ['closing', 'Closing gross NPA']]
  return (
    <>
      <div className="row" style={{ marginBottom: 12 }}>
        <label>From<input type="date" value={f.from} onChange={e => setF({ ...f, from: e.target.value })} /></label>
        <label>To<input type="date" value={f.to} onChange={e => setF({ ...f, to: e.target.value })} /></label>
        <button className="btn" onClick={() => setQ(f)}>Show</button>
      </div>
      <ErrorBox error={m.error} />
      {m.data && <div className="panel flush">
        <Table head={['Movement of NPAs', 'Loans', 'Amount']} rows={[...rows.map(([k, l]) => [l, m.data![k].loans, inr(m.data![k].amount)]),
          ['Net reduction in existing NPAs (repayments)', '', inr(m.data.netReductionInExisting)]]} />
      </div>}
    </>
  )
}

function Exposure() {
  const e = useLoad(() => api.get<{ total: number; loans: number; bySegment: Row[]; byProduct: Row[]; byRegion: Row[]; top20Share: number; largestShare: number }>('/api/regulatory/exposure'), [])
  const t = (rows: Row[], h: string) => <div className="panel flush"><Table head={[h, 'Loans', 'Outstanding', 'Share', 'Gross NPA']} rows={rows.map(r => [r.key, r.loans, inr(r.outstanding), pct(r.share, 1), pct(r.gnpa, 1)])} /></div>
  return (
    <>
      <ErrorBox error={e.error} />
      {e.data && <>
        <div className="kpis">
          <div className="kpi"><span>Outstanding</span><b>{lakh(e.data.total)}</b><span>{e.data.loans} loans</span></div>
          <div className="kpi"><span>20 largest borrowers</span><b>{pct(e.data.top20Share, 1)}</b></div>
          <div className="kpi"><span>Largest borrower</span><b>{pct(e.data.largestShare, 2)}</b></div>
        </div>
        <div className="grid2" style={{ marginTop: 12 }}>{t(e.data.bySegment, 'Segment')}{t(e.data.byProduct, 'Product')}</div>
        <div style={{ marginTop: 12 }}>{t(e.data.byRegion, 'Region')}</div>
      </>}
    </>
  )
}

function Cic() {
  const { user } = useAuth()
  const c = useLoad(() => api.get<{ asOf: string; rows: number; missingPan: number; missingDob: number; ready: boolean; dueDates: string }>('/api/regulatory/cic'), [])
  const [error, setError] = useState<unknown>()
  return (
    <>
      <ErrorBox error={error ?? c.error} />
      {c.data && <div className="panel">
        <p>As of <b>{c.data.asOf}</b>: {c.data.rows} accounts. {c.data.ready ? <span className="pill ok">ready</span> : <span className="pill bad">not ready</span>}</p>
        {c.data.missingPan > 0 && <p className="small">{c.data.missingPan} accounts have no PAN (loans from the LMS feed with no application here); the bureaus will reject them until the LMS sends the identifiers.</p>}
        <p className="small muted">{c.data.dueDates}.</p>
        {user && ['ADMIN', 'COMPLIANCE'].includes(user.role) ? <button className="btn" onClick={() => download('/api/regulatory/cic.csv', `cic-${c.data!.asOf}.csv`, setError)}>Download submission file (contains PAN)</button>
          : <p className="small muted">The file contains PAN and is downloaded by compliance.</p>}
      </div>}
    </>
  )
}
