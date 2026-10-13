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

const GOOD = ['VERIFIED', 'PASS', 'OK', 'FETCHED', 'COMPLETE', 'NOT_REQUIRED', 'DONE', 'APPROVE', 'CLEAR', 'SANCTIONED', 'KFS_ACCEPTED', 'DISBURSED', 'SUCCESS', 'RESOLVED', 'APPROVE_WITH_CONDITIONS', 'READY', 'PUBLISHED', 'SKIPPED']
const BAD = ['FAILED', 'FAIL', 'UNREADABLE', 'REJECT', 'REJECTED', 'BLOCK', 'DECLINED', 'DLQ', 'WITHDRAWN']
const WARN = ['REVIEW', 'WARN', 'NEEDS_REVIEW', 'IN_REVIEW', 'PARTIAL', 'DEFICIENT', 'REQUIRED', 'REFER', 'PENDING_L1', 'PENDING_L2', 'PENDING_L3', 'ACTIVE', 'PENDING_APPROVAL']
export function tone(s: string): 'ok' | 'bad' | 'warn' | 'muted' {
  if (GOOD.includes(s)) return 'ok'
  if (BAD.includes(s)) return 'bad'
  if (WARN.includes(s)) return 'warn'
  return 'muted'
}

/** Reason codes (FSD 26) plus lender extensions RC-021 and RC-022. */
export const REASON: Record<string, string> = {
  'RC-001': 'High probability of default', 'RC-002': 'High existing debt obligation', 'RC-003': 'FOIR above policy threshold',
  'RC-004': 'Income insufficient or unstable', 'RC-005': 'Recent delinquency', 'RC-006': 'High credit enquiry velocity',
  'RC-007': 'High credit utilisation', 'RC-008': 'Short credit history', 'RC-009': 'Cash-flow stress', 'RC-010': 'Document inconsistency',
  'RC-011': 'Identity / KYC mismatch', 'RC-012': 'Fraud risk above review threshold', 'RC-013': 'Application data incomplete',
  'RC-014': 'Policy exception needs human approval', 'RC-015': 'External data unavailable (thin file)', 'RC-016': 'Exposure limit exceeded',
  'RC-017': 'Business cash-flow volatility', 'RC-018': 'Recent borrowing increase', 'RC-019': 'Negative internal repayment history',
  'RC-020': 'Model or data quality error', 'RC-021': 'Betting / gambling transactions in bank statement', 'RC-022': 'Not eligible for this product',
}
