import { api } from '../api'
import { ErrorBox, Head, Pill, useLoad } from '../components/ui'

export default function StateMachinePage() {
  const sm = useLoad(() => api.get<Record<string, Record<string, string[]>>>('/api/state-machine'), [])
  return (
    <>
      <Head title="State machine" sub="Allowed moves for each part of an application. Any other move is refused by the server and logged." />
      <ErrorBox error={sm.error} />
      <div className="grid2">
        {Object.entries(sm.data ?? {}).map(([d, m]) => (
          <div className="panel" key={d}>
            <h3>{d}</h3>
            {Object.entries(m).map(([from, to]) => (
              <div key={from} className="row" style={{ marginBottom: 6 }}>
                <Pill s={from} /> <span className="muted">→</span> {to.map(t => <Pill key={t} s={t} />)}
              </div>
            ))}
          </div>
        ))}
      </div>
    </>
  )
}
