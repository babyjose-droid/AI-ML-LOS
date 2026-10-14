import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'
import { ErrorBox, Head } from '../components/ui'

interface Answer { question: string; application?: string; answer: string; mode: string; sources: { id: string; title: string; text: string; source: string }[] }

const EXAMPLES = ['Who can sanction a loan of ₹8 lakh?', 'What is the maximum FOIR?', 'What does RC-023 mean?', 'When is a restructured loan upgraded?', 'Which documents does the PL product need?']

/** Policy assistant: questions about policy, products, delegation, rules and reason codes; explains a decision by application number. */
export default function Assistant() {
  const [q, setQ] = useState('')
  const [busy, setBusy] = useState(false)
  const [log, setLog] = useState<Answer[]>([])
  const [error, setError] = useState<unknown>()
  async function ask(text = q) {
    if (!text.trim()) return
    setBusy(true); setError(undefined)
    try { const a = await api.post<Answer>('/api/assistant/ask', { question: text }); setLog(l => [a, ...l]); setQ('') }
    catch (e) { setError(e) } finally { setBusy(false) }
  }
  return (
    <>
      <Head title="Policy assistant" sub="Answers from the live credit policy, products, delegation matrix, underwriting rules and reason codes. Give an application number (APP-…) to have its decision explained. Names, PAN, Aadhaar and mobile numbers are masked before anything is sent to the AI." />
      <div className="panel">
        <form className="row" onSubmit={e => { e.preventDefault(); ask() }}>
          <input style={{ flex: 1 }} value={q} maxLength={1000} placeholder="Ask about the policy, or e.g. 'Why was APP-300001 referred?'" onChange={e => setQ(e.target.value)} aria-label="Question" />
          <button className="btn primary" disabled={busy || !q.trim()}>{busy ? 'Thinking…' : 'Ask'}</button>
        </form>
        <div className="row small" style={{ marginTop: 8, flexWrap: 'wrap' }}>{EXAMPLES.map(x => <button key={x} className="btn small" onClick={() => ask(x)}>{x}</button>)}</div>
      </div>
      <ErrorBox error={error} />
      {log.map((a, i) => (
        <div key={i} className="panel" style={{ marginTop: 12 }} data-answer>
          <p className="small muted">{a.question}{a.application && <> · <Link to={`/applications?q=${a.application}`}>{a.application}</Link></>}</p>
          <div style={{ whiteSpace: 'pre-wrap' }}>{a.answer}</div>
          <p className="small muted">{a.mode === 'generative' ? 'Written by the AI from the sources below' : a.mode === 'extractive' ? 'Quoted from the sources below (no AI key configured)' : ''}</p>
          {a.sources.length > 0 && <details><summary className="small">Sources ({a.sources.length})</summary>
            <ul className="small">{a.sources.map(s => <li key={s.id}><b>[{s.id}]</b> {s.title} — <span className="muted">{s.source}</span></li>)}</ul></details>}
        </div>
      ))}
    </>
  )
}
