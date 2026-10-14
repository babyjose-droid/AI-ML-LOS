import { useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { api } from '../api'
import { ErrorBox, useLoad } from '../components/ui'
import { inr } from '../format'
import { CHARGE_KEY, LANGS, both } from '../i18n/kfs'
import type { Kfs } from '../types'

interface Letter {
  language: string; lender: string; applicationNo: string; borrower: string; address: string; product: string; sanctionedOn: string
  approver: string; level: string; conditions: string[]; kfs: Kfs; acceptedAt?: string; acceptedLanguage?: string; validDays: number
}

/** FR-SAN-03: sanction letter and KFS in the borrower's language with English, ready to print or save as PDF. */
export default function SanctionLetter() {
  const { id } = useParams()
  const [q, setQ] = useSearchParams()
  const [lang, setLang] = useState(q.get('lang') ?? '')
  const l = useLoad(() => api.get<Letter>(`/api/applications/${id}/sanction-letter${lang ? '?lang=' + lang : ''}`), [id, lang])
  if (!l.data) return <ErrorBox error={l.error} />
  const d = l.data, k = d.kfs, L = d.language
  const T = ({ k: key, suffix }: { k: string; suffix?: string }) => {
    const b = both(L, key)
    return <span>{b.local && <span className="local" lang={L}>{b.local}{suffix}<br /></span>}<span className={b.local ? 'small muted' : ''}>{b.en}{suffix}</span></span>
  }
  const row = (key: string, value: React.ReactNode) => <tr><th><T k={key} /></th><td>{value}</td></tr>
  const date = (s?: string) => s ? new Date(s).toLocaleDateString('en-IN', { day: 'numeric', month: 'long', year: 'numeric' }) : ''
  return (
    <div className="letter" data-letter={L}>
      <style>{`
        .letter { max-width: 820px; margin: 0 auto; background: var(--panel, #fff); padding: 24px; }
        .letter table { width: 100%; border-collapse: collapse; margin: 8px 0 16px; }
        .letter th, .letter td { border: 1px solid var(--line, #ccc); padding: 6px 8px; text-align: left; vertical-align: top; }
        .letter th { width: 55%; font-weight: 500; }
        .letter .local { font-weight: 600; }
        .letter h2 { margin: 18px 0 6px; }
        @media print { .noprint { display: none !important; } .letter { padding: 0; } body { background: #fff; } }
      `}</style>
      <div className="row noprint" style={{ justifyContent: 'space-between', marginBottom: 12 }}>
        <Link to={`/applications/${id}`}>← Back to the application</Link>
        <div className="row">
          <label className="small">Language{' '}
            <select value={L} onChange={e => { setLang(e.target.value); setQ({ lang: e.target.value }) }}>
              {Object.entries(LANGS).map(([c, n]) => <option key={c} value={c}>{n}</option>)}
            </select>
          </label>
          <button className="btn primary" onClick={() => window.print()}>Print / save as PDF</button>
        </div>
      </div>
      <p><b>{d.lender}</b><br /><span className="small muted">{date(d.sanctionedOn)} · {d.applicationNo}</span></p>
      <h1 style={{ marginBottom: 4 }}><T k="letter" /></h1>
      <p><T k="dear" /> {d.borrower},<br /><span className="small">{d.address}</span></p>
      <p><T k="intro" /></p>

      <h2><T k="kfs" /></h2>
      <table><tbody>
        {row('appNo', d.applicationNo)}
        {row('product', d.product)}
        {row('amount', <b>{inr(k.sanctionedAmount)}</b>)}
        {row('fee', inr(k.processingFee))}
        {row('gst', inr(k.gstOnFee))}
        {k.insurance > 0 && row('insurance', inr(k.insurance))}
        {row('net', <b>{inr(k.netDisbursed)}</b>)}
        {row('rate', `${k.ratePa}%`)}
        {row('apr', <b>{k.aprPa}%</b>)}
        {row('tenure', <>{k.tenureMonths} <T k="months" /></>)}
        {row('emi', inr(k.emi))}
        {row('count', k.schedule.length)}
        {row('interest', inr(k.totalInterest))}
        {row('payable', <b>{inr(k.totalPayable)}</b>)}
        {row('repayment', k.repaymentMode)}
        {row('cooling', <>{k.coolingOffDays} <T k="coolingText" /></>)}
        {row('grievance', k.grievanceOfficer)}
      </tbody></table>

      {(k.contingentCharges ?? []).length > 0 && <>
        <h2><T k="contingent" /></h2>
        <table><tbody>{(k.contingentCharges ?? []).map(c => <tr key={c.name}><th>{CHARGE_KEY[c.name] ? <T k={CHARGE_KEY[c.name]} /> : c.name}</th><td>{c.amount}</td></tr>)}</tbody></table>
      </>}

      {d.conditions.length > 0 && <>
        <h2><T k="conditions" /></h2>
        <ol>{d.conditions.map(c => <li key={c}>{c}</li>)}</ol>
      </>}

      <p><T k="validity" /></p>
      <p className="small"><T k="recovery" /></p>
      <table><tbody>
        {row('by', `${d.approver} (${d.level})`)}
        {row('date', date(d.sanctionedOn))}
        {row('acceptance', d.acceptedAt ? <><T k="accepted" /> {date(d.acceptedAt)} ({LANGS[d.acceptedLanguage ?? 'en']})</> : <T k="notAccepted" />)}
      </tbody></table>

      <h2><T k="schedule" /></h2>
      <table className="small"><thead><tr><th style={{ width: 'auto' }}><T k="month" /></th><th style={{ width: 'auto' }}><T k="emi" /></th><th style={{ width: 'auto' }}><T k="interest" /></th><th style={{ width: 'auto' }}><T k="principal" /></th><th style={{ width: 'auto' }}><T k="balance" /></th></tr></thead>
        <tbody>{k.schedule.map(r => <tr key={r.month}><td>{r.month}</td><td>{inr(r.emi)}</td><td>{inr(r.interest)}</td><td>{inr(r.principal)}</td><td>{inr(r.balance)}</td></tr>)}</tbody></table>
      {L !== 'en' && <p className="small muted"><T k="english" /></p>}
    </div>
  )
}
