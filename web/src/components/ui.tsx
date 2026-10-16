import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { useSearchParams } from 'react-router-dom'
import { ApiError } from '../api'
import { label, tone } from '../format'
import type { States } from '../types'

export function Pill({ s }: { s: string }) {
  return <span className={'pill ' + tone(s)}>{label(s)}</span>
}

export function Head({ title, sub, children }: { title: string; sub?: string; children?: ReactNode }) {
  return (
    <div className="head">
      <div>
        <h1>{title}</h1>
        {sub && <p>{sub}</p>}
      </div>
      {children && <div className="row">{children}</div>}
    </div>
  )
}

/** A page's inner tab kept in the address (?tab=), so the sidebar and links can open a page on a given tab. */
export function useTab<T extends string>(keys: readonly T[], def: T): [T, (t: T) => void] {
  const [sp, setSp] = useSearchParams()
  const q = sp.get('tab') as T | null
  const tab = q && keys.includes(q) ? q : def
  const set = (t: T) => { const n = new URLSearchParams(sp); if (t === def) n.delete('tab'); else n.set('tab', t); setSp(n, { replace: true }) }
  return [tab, set]
}

/** Prototype KPI strip: one panel, figures side by side. */
export function KpiStrip({ items }: { items: [string, ReactNode, ReactNode?, boolean?][] }) {
  return (
    <div className="panel kstrip">
      {items.map(([l, v, sub, bad]) => <div key={l}><span>{l}</span><b className={bad ? 'bad' : ''}>{v}</b>{sub && <em>{sub}</em>}</div>)}
    </div>
  )
}

export const DOMAIN_LABEL: Record<string, string> = {
  APP: 'Application', KYC: 'KYC', DATA: 'Bank & bureau data', DOCS: 'Documents', FIELD: 'Field visit',
  DECISION: 'Decision', FRAUD: 'Fraud', SANCTION: 'Sanction', DISB: 'Disbursement',
}

const DOMAIN: Record<string, string> = {
  APP: 'Application', KYC: 'KYC', DATA: 'Bank & bureau data', DOCS: 'Documents', FIELD: 'Field visit',
  DECISION: 'Decision', FRAUD: 'Fraud', SANCTION: 'Sanction', DISB: 'Disbursement',
}
export function StateBoard({ states }: { states: States }) {
  return (
    <div className="states" data-testid="states">
      {Object.entries(states).map(([d, s]) => (
        <div key={d} data-domain={d}>
          {DOMAIN[d] ?? d}
          <Pill s={s} />
        </div>
      ))}
    </div>
  )
}

export function ErrorBox({ error }: { error: unknown }) {
  if (!error) return null
  const msg = error instanceof ApiError || error instanceof Error ? error.message : String(error)
  const fields = error instanceof ApiError && error.fields
  return (
    <div className="alert bad" role="alert">
      {msg}
      {fields && (
        <ul style={{ margin: '6px 0 0', paddingLeft: 18 }}>
          {Object.entries(fields).map(([k, v]) => <li key={k}>{k}: {v}</li>)}
        </ul>
      )}
    </div>
  )
}

/** Load data from the API with loading and error state, plus a reload function. */
export function useLoad<T>(fn: () => Promise<T>, deps: unknown[]) {
  const [data, setData] = useState<T | undefined>()
  const [error, setError] = useState<unknown>()
  const [loading, setLoading] = useState(true)
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const run = useCallback(fn, deps)
  const reload = useCallback(() => {
    setLoading(true)
    run().then(d => { setData(d); setError(undefined) }).catch(setError).finally(() => setLoading(false))
  }, [run])
  useEffect(() => { reload() }, [reload])
  return { data, error, loading, reload }
}

export function Modal({ title, children, onClose }: { title: string; children: ReactNode; onClose: () => void }) {
  return (
    <div className="modal-back" onClick={onClose}>
      <div className="modal" role="dialog" aria-label={title} onClick={e => e.stopPropagation()}>
        <div className="head" style={{ marginBottom: 12 }}>
          <h3 style={{ margin: 0 }}>{title}</h3>
          <button className="btn sm" onClick={onClose}>Close</button>
        </div>
        {children}
      </div>
    </div>
  )
}

export function Table({ head, rows, onRow, empty }: { head: ReactNode[]; rows: ReactNode[][]; onRow?: (i: number) => void; empty?: string }) {
  if (!rows.length) return <div className="empty">{empty ?? 'Nothing here yet'}</div>
  return (
    <div className="tablewrap">
      <table>
        <thead><tr>{head.map((h, i) => <th key={i}>{h}</th>)}</tr></thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={i} className={onRow ? 'click' : ''} onClick={onRow ? () => onRow(i) : undefined}>
              {r.map((c, j) => <td key={j}>{c}</td>)}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
