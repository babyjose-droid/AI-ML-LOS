export function inr(x: number | null | undefined): string {
  if (x === null || x === undefined || isNaN(Number(x))) return '—'
  return '₹' + Math.round(Number(x)).toLocaleString('en-IN')
}
export function lakh(x: number): string {
  return '₹' + (x / 1e5).toLocaleString('en-IN', { maximumFractionDigits: 2 }) + ' L'
}
export const pct = (x: number, d = 1) => (Number(x) * 100).toFixed(d) + '%'
export const when = (iso?: string) =>
  iso ? new Date(iso).toLocaleString('en-IN', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' }) : '—'
export const label = (s: string) => s.replace(/_/g, ' ').toLowerCase().replace(/^\w/, c => c.toUpperCase())

const GOOD = ['VERIFIED', 'PASS', 'OK', 'FETCHED', 'COMPLETE', 'NOT_REQUIRED', 'DONE', 'APPROVE', 'CLEAR', 'SANCTIONED', 'KFS_ACCEPTED', 'DISBURSED', 'SUCCESS', 'RESOLVED', 'APPROVE_WITH_CONDITIONS', 'READY']
const BAD = ['FAILED', 'FAIL', 'UNREADABLE', 'REJECT', 'REJECTED', 'BLOCK', 'DECLINED', 'DLQ', 'WITHDRAWN']
const WARN = ['REVIEW', 'WARN', 'NEEDS_REVIEW', 'IN_REVIEW', 'PARTIAL', 'DEFICIENT', 'REQUIRED', 'REFER', 'PENDING_L1', 'PENDING_L2', 'PENDING_L3']
export function tone(s: string): 'ok' | 'bad' | 'warn' | 'muted' {
  if (GOOD.includes(s)) return 'ok'
  if (BAD.includes(s)) return 'bad'
  if (WARN.includes(s)) return 'warn'
  return 'muted'
}
