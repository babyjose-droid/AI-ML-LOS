import { useState } from 'react'
import { api } from '../api'
import { useAuth } from '../auth'
import { ErrorBox, Head, Table, useLoad } from '../components/ui'
import { inr, label } from '../format'
import type { Branch } from '../types'

interface Holiday { id: number; date: string; name: string; branchId?: number; createdBy?: string }
interface Limit { level: string; role: string; maxAmount?: number; maxBand: string; maxDeviations: number; updatedBy?: string; updatedAt: string }
interface RoleRow { role: string; can: string[]; dataScope: string; sanctionLevel?: string }

/** SCR-122 / SCR-123: roles and permissions with sanction limits, and the holiday calendar. */
export default function Masters() {
  const [tab, setTab] = useState<'roles' | 'holidays'>('roles')
  return (
    <>
      <Head title="Masters" sub="Roles and permissions with sanction limits, and the holiday calendar used by SLA clocks. PIN codes are set per branch in Branches." />
      <div className="tabs" role="tablist">
        <button role="tab" className={tab === 'roles' ? 'on' : ''} aria-selected={tab === 'roles'} onClick={() => setTab('roles')}>Roles and permissions</button>
        <button role="tab" className={tab === 'holidays' ? 'on' : ''} aria-selected={tab === 'holidays'} onClick={() => setTab('holidays')}>Holiday calendar</button>
      </div>
      {tab === 'roles' ? <Roles /> : <Holidays />}
    </>
  )
}

function Roles() {
  const { user } = useAuth()
  const [rev, setRev] = useState(0)
  const r = useLoad(() => api.get<{ roles: RoleRow[]; limits: Limit[] }>('/api/masters/roles'), [rev])
  const [edit, setEdit] = useState<Limit | null>(null)
  const [f, setF] = useState({ maxAmount: '', maxBand: 'C', maxDeviations: '0', note: '' })
  const [error, setError] = useState<unknown>()
  const admin = user?.role === 'ADMIN'
  async function save() {
    try {
      setError(undefined)
      await api.put('/api/admin/sanction-limits/' + edit!.level, {
        maxAmount: edit!.level === 'L3' || f.maxAmount === '' ? null : Number(f.maxAmount), maxBand: f.maxBand, maxDeviations: Number(f.maxDeviations), note: f.note,
      })
      setEdit(null); setRev(x => x + 1)
    } catch (e) { setError(e) }
  }
  return (
    <>
      <ErrorBox error={r.error ?? error} />
      <div className="panel" data-testid="sanction-limits">
        <h3 style={{ marginTop: 0 }}>Delegation of powers (sanction limits)</h3>
        <p className="small muted">A case goes to the lowest level whose limits cover its amount, risk band and number of policy deviations. Any fraud flag goes to L3. Changes apply to new decisions at once and are recorded in the audit trail.</p>
        <Table head={['Level', 'Role', 'Up to amount', 'Worst band', 'Deviations', 'Changed', '']} rows={(r.data?.limits ?? []).map(l => [
          <b>{l.level}</b>, label(l.role), l.maxAmount == null ? 'No limit' : inr(l.maxAmount), l.maxBand, l.maxDeviations >= 99 ? 'Any' : l.maxDeviations,
          <span className="small muted">{l.updatedBy} · {new Date(l.updatedAt).toLocaleDateString()}</span>,
          admin ? <button className="btn sm" onClick={() => { setEdit(l); setF({ maxAmount: l.maxAmount == null ? '' : String(l.maxAmount), maxBand: l.maxBand, maxDeviations: String(l.maxDeviations), note: '' }) }}>Edit</button> : '',
        ])} />
        {edit && (
          <div className="form" style={{ marginTop: 12 }}>
            {edit.level !== 'L3' && <label className="fld">Up to amount (₹)<input type="number" name="maxAmount" value={f.maxAmount} onChange={e => setF({ ...f, maxAmount: e.target.value })} /></label>}
            <label className="fld">Worst band<select value={f.maxBand} onChange={e => setF({ ...f, maxBand: e.target.value })}>{['A', 'B', 'C', 'D', 'E'].map(b => <option key={b}>{b}</option>)}</select></label>
            <label className="fld">Deviations allowed<input type="number" min={0} max={99} value={f.maxDeviations} onChange={e => setF({ ...f, maxDeviations: e.target.value })} /></label>
            <label className="fld wide">Board approval or reason<input name="limitNote" value={f.note} onChange={e => setF({ ...f, note: e.target.value })} /></label>
            <div className="row"><button className="btn primary" onClick={save}>Save {edit.level}</button><button className="btn" onClick={() => setEdit(null)}>Cancel</button></div>
          </div>
        )}
      </div>
      <div className="panel flush">
        <Table head={['Role', 'Can do', 'Data scope', 'Sanction']} rows={(r.data?.roles ?? []).map(x => [
          <b>{label(x.role)}</b>, <ul className="small" style={{ margin: 0, paddingLeft: 16 }}>{x.can.map(c => <li key={c}>{c}</li>)}</ul>,
          <span className="small">{x.dataScope}</span>, x.sanctionLevel ?? '—',
        ])} />
      </div>
      <p className="small muted">Stage-level work (who does KYC, documents, field visit and so on) is set per product in the workflow designer.</p>
    </>
  )
}

function Holidays() {
  const { user } = useAuth()
  const [rev, setRev] = useState(0)
  const h = useLoad(() => api.get<Holiday[]>('/api/masters/holidays'), [rev])
  const br = useLoad(() => api.get<Branch[]>('/api/branches'), [])
  const [f, setF] = useState({ date: '', name: '', branchId: '' })
  const [error, setError] = useState<unknown>()
  const admin = user?.role === 'ADMIN'
  const bname = (id?: number) => id == null ? 'All branches' : br.data?.find(b => b.id === id)?.name ?? '#' + id
  async function add() {
    try { setError(undefined); await api.post('/api/admin/holidays', { ...f, branchId: f.branchId ? Number(f.branchId) : null }); setF({ date: '', name: '', branchId: '' }); setRev(x => x + 1) } catch (e) { setError(e) }
  }
  async function remove(id: number) {
    try { setError(undefined); await api.del('/api/admin/holidays/' + id); setRev(x => x + 1) } catch (e) { setError(e) }
  }
  return (
    <>
      <ErrorBox error={h.error ?? error} />
      {admin && (
        <div className="panel">
          <div className="form">
            <label className="fld">Date<input type="date" name="holidayDate" value={f.date} onChange={e => setF({ ...f, date: e.target.value })} /></label>
            <label className="fld">Name<input name="holidayName" value={f.name} onChange={e => setF({ ...f, name: e.target.value })} /></label>
            <label className="fld">Applies to<select value={f.branchId} onChange={e => setF({ ...f, branchId: e.target.value })}>
              <option value="">All branches</option>{(br.data ?? []).filter(b => b.level === 'BRANCH').map(b => <option key={b.id} value={b.id}>{b.name}</option>)}
            </select></label>
            <button className="btn primary" onClick={add}>Add holiday</button>
          </div>
        </div>
      )}
      <div className="panel flush">
        <Table head={['Date', 'Holiday', 'Applies to', '']} rows={(h.data ?? []).map(x => [
          new Date(x.date + 'T00:00:00').toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' }), x.name, bname(x.branchId),
          admin ? <button className="btn sm" onClick={() => remove(x.id)}>Remove</button> : '',
        ])} empty="No holidays." />
      </div>
    </>
  )
}
