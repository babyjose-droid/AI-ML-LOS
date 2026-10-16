import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api'
import { ErrorBox, Head, Pill, Table, useLoad } from '../components/ui'
import { inr, when } from '../format'
import { CustomerPanel } from '../flow/CasePanels'
import type { AppSummary } from '../types'

/** Search box plus the matching applications; used by the Application 360 and Customer 360 entry screens. */
function useApps(q: string) {
  return useLoad(() => api.get<AppSummary[]>(`/api/applications?state=&q=${encodeURIComponent(q)}`), [q])
}

/** SCR-021 entry: pick an application to open its 360 view (the sidebar item, as in the prototype). */
export function AppPicker() {
  const nav = useNavigate()
  const [q, setQ] = useState('')
  const list = useApps(q)
  const apps = list.data ?? []
  return (
    <>
      <Head title="Application 360" sub="Pick an application to see every stage, what AI did and what is left for people. You can switch application from inside the 360 view too." />
      <div className="toolbar"><div className="row" style={{ marginTop: 0 }}>
        <input style={{ width: 320 }} placeholder="Search name, PAN or application no." value={q} onChange={e => setQ(e.target.value)} aria-label="Search applications" />
      </div></div>
      <ErrorBox error={list.error} />
      <div className="panel flush">
        <Table head={['Application', 'Applicant', 'Product', 'Amount', 'Stage', 'Updated']} onRow={i => nav('/applications/' + apps[i].id)}
          rows={apps.map(a => [<b>{a.appNo}</b>, a.applicantName, a.productCode, <span className="num">{inr(a.loanAmount)}</span>, <Pill s={a.states.APP} />, <span className="small muted">{when(a.updatedAt)}</span>])}
          empty={list.loading ? 'Loading…' : 'No applications match.'} />
      </div>
    </>
  )
}

/** SCR-092: everything about one customer, picked by name, PAN or application. */
export function CustomerPicker() {
  const [q, setQ] = useState('')
  const [id, setId] = useState<string>()
  const list = useApps(q)
  const apps = list.data ?? []
  return (
    <>
      <Head title="Customer 360" sub="Everything about one customer in one place: all their applications, exposure, linked people and household, each linked to its source." />
      <div className="panel">
        <div className="fgrid2">
          <label className="fld">Find customer<input placeholder="Name, PAN or application no." value={q} onChange={e => setQ(e.target.value)} /></label>
          <label className="fld">Customer
            <select value={id ?? ''} onChange={e => setId(e.target.value || undefined)} aria-label="Customer">
              <option value="">{list.loading ? 'Loading…' : `Choose (${apps.length} found)`}</option>
              {apps.map(a => <option key={a.id} value={a.id}>{a.applicantName} · {a.appNo}</option>)}
            </select>
          </label>
        </div>
      </div>
      <ErrorBox error={list.error} />
      {id ? <CustomerPanel id={id} rev={0} /> : <div className="empty">Choose a customer to see their 360 view.</div>}
    </>
  )
}
