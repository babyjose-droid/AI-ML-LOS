import { Link } from 'react-router-dom'
import { api } from '../api'
import { ErrorBox, Head, Table, useLoad } from '../components/ui'
import { when } from '../format'

// The officials' queue: customers waiting on a video KYC call now (longest first), calls in progress, and booked calls.

interface Row {
  sessionId: number; applicationId: number; appNo: string; person: string; party: boolean; product: string; status: string; slotAt?: string; joinedAt?: string
  officer?: string; inIndia?: boolean; ipCountry?: string; languages: string[]; waitingMinutes?: number; late?: boolean
}
interface Q { waiting: Row[]; inCall: Row[]; booked: Row[]; slotCapacity: number }

const time = (s?: string) => s ? new Date(s).toLocaleString('en-IN', { weekday: 'short', day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' }) : '—'
const where = (r: Row) => <span className="small">{r.inIndia == null ? '—' : r.inIndia ? 'GPS in India' : <b className="warn-text">GPS outside India</b>}
  {r.ipCountry && <> · {r.ipCountry === 'IN' ? 'IP in India' : r.ipCountry === 'PRIVATE' ? 'private network' : r.ipCountry === 'UNKNOWN' ? 'IP country unknown' : <b className="warn-text">IP in {r.ipCountry}</b>}</>}</span>
const open = (r: Row) => <Link className="btn sm primary" to={`/applications/${r.applicationId}?tab=Video%20KYC`}>Open</Link>

export default function VkycQueue() {
  const q = useLoad(() => api.get<Q>('/api/vkyc/queue'), [])
  const d = q.data
  return (
    <>
      <Head title="Video KYC queue" sub={`Customers waiting now, calls in progress and booked calls. Each 15-minute slot takes ${d?.slotCapacity ?? '…'} calls.`} />
      <ErrorBox error={q.error} />
      {d && <>
        <div className="panel flush" data-testid="vkyc-waiting">
          <h3 style={{ padding: '12px 14px 0' }}>Waiting now ({d.waiting.length})</h3>
          <Table head={['Customer', 'Application', 'Waiting', 'Booked for', 'Location', 'Languages', '']} empty="Nobody is waiting."
            rows={d.waiting.map(r => [<b>{r.person}{r.party ? ' (co-applicant/guarantor)' : ''}</b>, r.appNo + ' · ' + r.product,
              <span className={r.late ? 'warn-text' : ''}>{r.waitingMinutes} min</span>, time(r.slotAt), where(r), r.languages.join(', '), open(r)])} />
        </div>
        <div className="panel flush">
          <h3 style={{ padding: '12px 14px 0' }}>In a call ({d.inCall.length})</h3>
          <Table head={['Customer', 'Application', 'Official', 'Joined', '']} empty="No calls in progress."
            rows={d.inCall.map(r => [r.person, r.appNo, r.officer ?? '—', when(r.joinedAt ?? ''), open(r)])} />
        </div>
        <div className="panel flush" data-testid="vkyc-booked">
          <h3 style={{ padding: '12px 14px 0' }}>Booked ({d.booked.length})</h3>
          <Table head={['When', 'Customer', 'Application', 'Languages', '']} empty="No calls booked."
            rows={d.booked.map(r => [<b>{time(r.slotAt)}</b>, r.person, r.appNo + ' · ' + r.product, r.languages.join(', '), open(r)])} />
        </div>
      </>}
    </>
  )
}
