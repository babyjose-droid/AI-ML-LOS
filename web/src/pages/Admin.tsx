import { Fragment, useState } from 'react'
import { api } from '../api'
import { useAuth } from '../auth'
import { ErrorBox, Head, Modal, Pill, Table, useLoad } from '../components/ui'
import { inr } from '../format'
import type { AppUser, Branch, Product, Role } from '../types'

const ROLES: Role[] = ['ADMIN', 'SALES', 'OPERATIONS', 'CREDIT_OFFICER', 'CREDIT_MANAGER', 'CRO', 'FRAUD_ANALYST', 'COMPLIANCE']

export function Branches() {
  const { user } = useAuth()
  const [rev, setRev] = useState(0)
  const [open, setOpen] = useState(false)
  const b = useLoad(() => api.get<Branch[]>('/api/branches'), [rev])
  const list = b.data ?? []
  const name = (id?: number) => list.find(x => x.id === id)?.name ?? '—'
  const depth = (x: Branch): number => (x.parentId ? 1 + depth(list.find(p => p.id === x.parentId) ?? { ...x, parentId: undefined }) : 0)
  return (
    <>
      <Head title="Branch hierarchy" sub="Head office → zone → region → branch. Users belong to one branch.">
        {user?.role === 'ADMIN' && <button className="btn primary" onClick={() => setOpen(true)}>Add branch</button>}
      </Head>
      <ErrorBox error={b.error} />
      <div className="panel flush">
        <Table head={['Code', 'Name', 'Level', 'Parent', 'City', 'Status']} rows={list.map(x => [
          <span className="mono">{x.code}</span>, <span style={{ paddingLeft: depth(x) * 16 }}>{x.name}</span>, x.level.replace('_', ' ').toLowerCase(),
          name(x.parentId), x.city ?? '—', <Pill s={x.active ? 'SUCCESS' : 'WITHDRAWN'} />,
        ])} />
      </div>
      {open && <BranchForm branches={list} onClose={() => setOpen(false)} onSaved={() => { setOpen(false); setRev(r => r + 1) }} />}
    </>
  )
}

function BranchForm({ branches, onClose, onSaved }: { branches: Branch[]; onClose: () => void; onSaved: () => void }) {
  const [f, setF] = useState({ code: '', name: '', level: 'BRANCH', parentId: String(branches.find(b => b.level === 'REGION')?.id ?? ''), city: '' })
  const [error, setError] = useState<unknown>()
  async function save() {
    try { await api.post('/api/admin/branches', { ...f, code: f.code.toUpperCase(), parentId: f.parentId ? Number(f.parentId) : null }); onSaved() } catch (e) { setError(e) }
  }
  return (
    <Modal title="Add branch" onClose={onClose}>
      <ErrorBox error={error} />
      <div className="form" style={{ gridTemplateColumns: '1fr' }}>
        <label className="fld">Code<input value={f.code} onChange={e => setF({ ...f, code: e.target.value })} /></label>
        <label className="fld">Name<input value={f.name} onChange={e => setF({ ...f, name: e.target.value })} /></label>
        <label className="fld">Level<select value={f.level} onChange={e => setF({ ...f, level: e.target.value })}>{['ZONE', 'REGION', 'BRANCH'].map(l => <option key={l}>{l}</option>)}</select></label>
        <label className="fld">Parent<select value={f.parentId} onChange={e => setF({ ...f, parentId: e.target.value })}>{branches.map(b => <option key={b.id} value={b.id}>{b.name}</option>)}</select></label>
        <label className="fld">City<input value={f.city} onChange={e => setF({ ...f, city: e.target.value })} /></label>
        <button className="btn primary" onClick={save}>Save</button>
      </div>
    </Modal>
  )
}

export function Users() {
  const [rev, setRev] = useState(0)
  const [edit, setEdit] = useState<AppUser | 'new' | null>(null)
  const [error, setError] = useState<unknown>()
  const u = useLoad(() => api.get<AppUser[]>('/api/admin/users'), [rev])
  const br = useLoad(() => api.get<Branch[]>('/api/branches'), [])
  const bname = (id?: number) => br.data?.find(b => b.id === id)?.name ?? '—'
  async function toggle(x: AppUser) {
    try { await api.patch(`/api/admin/users/${x.id}/active?value=${!x.active}`); setRev(r => r + 1) } catch (e) { setError(e) }
  }
  return (
    <>
      <Head title="Users and roles" sub="Each user has one role. Sanction authority: credit officer L1, credit manager L2, CRO L3.">
        <button className="btn primary" onClick={() => setEdit('new')}>Add user</button>
      </Head>
      <ErrorBox error={u.error ?? error} />
      <div className="panel flush">
        <Table head={['Username', 'Name', 'Role', 'Branch', 'Status', '']} rows={(u.data ?? []).map(x => [
          <span className="mono">{x.username}</span>, x.fullName, x.role.replace(/_/g, ' ').toLowerCase(), bname(x.branchId),
          <Pill s={x.active ? 'SUCCESS' : 'WITHDRAWN'} />,
          <div className="row"><button className="btn sm" onClick={() => setEdit(x)}>Edit</button><button className="btn sm" onClick={() => toggle(x)}>{x.active ? 'Deactivate' : 'Activate'}</button></div>,
        ])} />
      </div>
      {edit && <UserForm user={edit === 'new' ? null : edit} branches={br.data ?? []} onClose={() => setEdit(null)} onSaved={() => { setEdit(null); setRev(r => r + 1) }} />}
    </>
  )
}

function UserForm({ user, branches, onClose, onSaved }: { user: AppUser | null; branches: Branch[]; onClose: () => void; onSaved: () => void }) {
  const [f, setF] = useState({
    username: user?.username ?? '', fullName: user?.fullName ?? '', role: user?.role ?? 'SALES', branchId: String(user?.branchId ?? ''),
    email: user?.email ?? '', mobile: user?.mobile ?? '', password: '',
  })
  const [error, setError] = useState<unknown>()
  async function save() {
    const body = { ...f, branchId: f.branchId ? Number(f.branchId) : null, email: f.email || null, password: f.password || null }
    try {
      if (user) await api.put('/api/admin/users/' + user.id, body)
      else await api.post('/api/admin/users', body)
      onSaved()
    } catch (e) { setError(e) }
  }
  return (
    <Modal title={user ? 'Edit user' : 'Add user'} onClose={onClose}>
      <ErrorBox error={error} />
      <div className="form" style={{ gridTemplateColumns: '1fr' }}>
        <label className="fld">Username<input value={f.username} disabled={!!user} onChange={e => setF({ ...f, username: e.target.value.toLowerCase() })} /></label>
        <label className="fld">Full name<input value={f.fullName} onChange={e => setF({ ...f, fullName: e.target.value })} /></label>
        <label className="fld">Role<select value={f.role} onChange={e => setF({ ...f, role: e.target.value as Role })}>{ROLES.map(r => <option key={r} value={r}>{r.replace(/_/g, ' ').toLowerCase()}</option>)}</select></label>
        <label className="fld">Branch<select value={f.branchId} onChange={e => setF({ ...f, branchId: e.target.value })}><option value="">—</option>{branches.map(b => <option key={b.id} value={b.id}>{b.name}</option>)}</select></label>
        <label className="fld">Email<input value={f.email} onChange={e => setF({ ...f, email: e.target.value })} /></label>
        <label className="fld">Mobile<input value={f.mobile} onChange={e => setF({ ...f, mobile: e.target.value })} /></label>
        <label className="fld">{user ? 'New password (leave empty to keep)' : 'Password (8+ characters)'}<input type="password" value={f.password} onChange={e => setF({ ...f, password: e.target.value })} /></label>
        <button className="btn primary" onClick={save}>Save</button>
      </div>
    </Modal>
  )
}

export function Products() {
  const { user } = useAuth()
  const [rev, setRev] = useState(0)
  const [edit, setEdit] = useState<Product | 'new' | null>(null)
  const p = useLoad(() => api.get<Product[]>('/api/products'), [rev])
  return (
    <>
      <Head title="Products" sub="Loan products and their limits, fees, field-visit rule and required documents.">
        {user?.role === 'ADMIN' && <button className="btn primary" onClick={() => setEdit('new')}>Add product</button>}
      </Head>
      <ErrorBox error={p.error} />
      <div className="panel flush">
        <Table head={['Code', 'Name', 'Segment', 'Amount', 'Tenure', 'Rate', 'Fee', 'Field visit', 'Documents', 'Status', '']} rows={(p.data ?? []).map(x => [
          <span className="mono">{x.code}</span>, x.name, x.segment.toLowerCase(), `${inr(x.minAmount)}–${inr(x.maxAmount)}`, `${x.minTenure}–${x.maxTenure} m`,
          `${x.rateMin}–${x.rateMax}%`, `${x.processingFeePct}%`,
          x.fieldVisitRule === 'ABOVE_AMOUNT' ? `Above ${inr(x.fieldVisitThreshold)}` : x.fieldVisitRule.toLowerCase(), <span className="small">{x.requiredDocs.replace(/,/g, ', ').replace(/\|/g, ' or ')}</span>,
          <Pill s={x.status === 'LIVE' ? 'SUCCESS' : 'WITHDRAWN'} />, user?.role === 'ADMIN' ? <button className="btn sm" onClick={() => setEdit(x)}>Edit</button> : '',
        ])} />
      </div>
      {edit && <ProductForm product={edit === 'new' ? null : edit} onClose={() => setEdit(null)} onSaved={() => { setEdit(null); setRev(r => r + 1) }} />}
    </>
  )
}

function ProductForm({ product, onClose, onSaved }: { product: Product | null; onClose: () => void; onSaved: () => void }) {
  const init = product ?? {
    code: '', name: '', segment: 'MSME', secured: false, status: 'LIVE', minAmount: 50000, maxAmount: 500000, minTenure: 6, maxTenure: 36, minAge: 21, maxAge: 65,
    processingFeePct: 2, rateMin: 13, rateMax: 26, fieldVisitRule: 'NEVER', fieldVisitThreshold: undefined, requiredDocs: 'PAN,AADHAAR',
  }
  const [f, setF] = useState<Record<string, string | number | boolean | undefined>>({ ...init })
  const [error, setError] = useState<unknown>()
  const num = (k: string, l: string) => <label className="fld">{l}<input type="number" value={String(f[k] ?? '')} onChange={e => setF({ ...f, [k]: e.target.value === '' ? undefined : Number(e.target.value) })} /></label>
  async function save() {
    try {
      if (product) await api.put('/api/admin/products/' + product.code, f)
      else await api.post('/api/admin/products', f)
      onSaved()
    } catch (e) { setError(e) }
  }
  return (
    <Modal title={product ? 'Edit product' : 'Add product'} onClose={onClose}>
      <ErrorBox error={error} />
      <div className="form" style={{ gridTemplateColumns: '1fr 1fr' }}>
        <label className="fld">Code<input value={String(f.code)} disabled={!!product} onChange={e => setF({ ...f, code: e.target.value.toUpperCase() })} /></label>
        <label className="fld">Name<input value={String(f.name)} onChange={e => setF({ ...f, name: e.target.value })} /></label>
        <label className="fld">Segment<select value={String(f.segment)} onChange={e => setF({ ...f, segment: e.target.value })}><option>MSME</option><option>SALARIED</option><option>MICROFINANCE</option></select></label>
        <label className="fld">Status<select value={String(f.status)} onChange={e => setF({ ...f, status: e.target.value })}><option>LIVE</option><option>PILOT</option><option>RETIRED</option></select></label>
        {num('minAmount', 'Min amount')}{num('maxAmount', 'Max amount')}{num('minTenure', 'Min tenure')}{num('maxTenure', 'Max tenure')}
        {num('minAge', 'Min age')}{num('maxAge', 'Max age')}{num('rateMin', 'Min rate %')}{num('rateMax', 'Max rate %')}{num('processingFeePct', 'Fee %')}
        <label className="fld">Field visit<select value={String(f.fieldVisitRule)} onChange={e => setF({ ...f, fieldVisitRule: e.target.value })}><option>NEVER</option><option>ALWAYS</option><option>ABOVE_AMOUNT</option></select></label>
        {num('fieldVisitThreshold', 'Field visit above (₹)')}
        <label className="fld wide">Required documents (comma separated)<input value={String(f.requiredDocs)} onChange={e => setF({ ...f, requiredDocs: e.target.value })} /></label>
        <label className="check"><input type="checkbox" checked={!!f.secured} onChange={e => setF({ ...f, secured: e.target.checked })} /> Secured</label>
        <button className="btn primary" onClick={save}>Save</button>
      </div>
    </Modal>
  )
}

export function Policy() {
  const p = useLoad(() => api.get<Record<string, number | string>>('/api/policies/live'), [])
  const LABELS: Record<string, string> = {
    version: 'Version', minAge: 'Minimum age', maxAge: 'Maximum age', kycFail: 'KYC match: reject below', kycReview: 'KYC match: review below',
    fraudReview: 'Fraud score: review at', fraudBlock: 'Fraud score: block at', maxFoir: 'Maximum FOIR', safety: 'Surplus safety factor',
    approvePd: 'PD: auto-approve up to', referPd: 'PD: decline above', maxEnq6: 'Max bureau enquiries (6m)', dpdReject: 'Reject DPD at',
    minBankMonths: 'Minimum bank months', baseRate: 'Base rate %', opexRate: 'Opex loading %', maxRate: 'Rate cap %',
    lgdUnsecured: 'LGD unsecured', lgdSecured: 'LGD secured', exposureCap: 'Exposure cap ₹', autoApproveLimit: 'Auto-approval limit ₹',
  }
  return (
    <>
      <Head title="Credit policy" sub="Live policy used by the decision engine. Policy studio with drafts, simulation and maker-checker comes in Phase 2." />
      <ErrorBox error={p.error} />
      <div className="panel">
        <dl className="dl">{Object.entries(p.data ?? {}).map(([k, v]) => <Fragment key={k}><dt>{LABELS[k] ?? k}</dt><dd className="num">{String(v)}</dd></Fragment>)}</dl>
      </div>
    </>
  )
}
