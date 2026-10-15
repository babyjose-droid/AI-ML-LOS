import { getTenant, getToken } from '../api'

/** Opens a PDF the API returns (GET, or POST with a JSON body) in a new tab. */
export async function openPdf(url: string, body?: unknown): Promise<void> {
  const headers: Record<string, string> = {}
  const t = getToken()
  if (t) headers.Authorization = 'Bearer ' + t
  const org = getTenant()
  if (org) headers['X-Tenant'] = org
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const r = await fetch(url, { method: body === undefined ? 'GET' : 'POST', headers, body: body === undefined ? undefined : JSON.stringify(body) })
  if (!r.ok) {
    let msg = 'The PDF could not be made'
    try { msg = (await r.json()).message ?? msg } catch { /* not JSON */ }
    throw new Error(msg)
  }
  window.open(URL.createObjectURL(await r.blob()), '_blank')
}

export const STAGE_LABEL: Record<string, string> = {
  APPLICATION: 'With the application', SANCTION: 'At sanction', PRE_DISBURSE: 'Before disbursement', POST_DISBURSE: 'After disbursement', CLOSURE: 'At closure',
}
export const SIGNER_LABEL: Record<string, string> = { BORROWER: 'Borrower', CO_APPLICANT: 'Co-applicants', GUARANTOR: 'Guarantors' }
export const CONDITION_LABEL: Record<string, string> = {
  ALWAYS: 'Always', HAS_CO_APPLICANT: 'When there is a co-applicant', HAS_GUARANTOR: 'When there is a guarantor', HAS_COLLATERAL: 'When there is collateral', UNSECURED: 'When there is no collateral',
}
export const TYPE_LABEL: Record<string, string> = {
  APPLICATION_FORM: 'Application form', SANCTION_LETTER: 'Sanction letter', KFS: 'Key Fact Statement', LOAN_AGREEMENT: 'Loan agreement', GUARANTEE_DEED: 'Guarantee deed',
  DPN: 'Promissory note', HYPOTHECATION: 'Hypothecation deed', MORTGAGE: 'Mortgage / MOD', WELCOME: 'Welcome letter', NOC: 'No dues / NOC', OTHER: 'Other', CLAUSE: 'Clause',
}

/** A filled-in document shown safely: in a sandboxed frame with no scripts and no access to this site. */
export function frameDoc(html: string): string {
  return `<!doctype html><html><head><meta charset="utf-8"><style>
body{font:14px/1.5 system-ui,"Noto Sans",sans-serif;color:#111;background:#fff;margin:24px auto;max-width:820px;padding:0 16px}
table{width:100%;border-collapse:collapse;margin:8px 0}th,td{border:1px solid #bbb;padding:4px 6px;text-align:left;vertical-align:top}th{background:#f2f2f2}
.sign{display:flex;flex-wrap:wrap;gap:24px;margin-top:28px}.sign div{min-width:180px;border-top:1px solid #333;padding-top:4px;font-size:12px}.small{font-size:12px;color:#555}
</style></head><body>${html}</body></html>`
}
