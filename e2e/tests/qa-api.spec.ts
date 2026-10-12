/**
 * QA pack: negative paths, security and role checks against the running stack (through nginx, as a browser would).
 * Runs after journeys.spec.ts.
 */
import fs from 'fs'
import path from 'path'
import { expect, test, type APIRequestContext } from '@playwright/test'

const fixture = (f: string) => path.join(__dirname, '..', 'fixtures', f)
const tokens: Record<string, string> = {}

async function token(request: APIRequestContext, user: string) {
  if (tokens[user]) return tokens[user]
  const r = await request.post('/api/auth/login', { data: { username: user, password: 'Rhythm@123' } })
  expect(r.status(), `login ${user}`).toBe(200)
  tokens[user] = (await r.json()).token
  return tokens[user]
}
const auth = (t: string) => ({ Authorization: 'Bearer ' + t })

function application(over: Record<string, unknown> = {}) {
  return {
    productCode: 'PL', applicantName: 'Kavya Iyer', pan: 'KAVPI5566K', mobile: '9876512345', dob: '1993-08-21',
    city: 'Chennai', pincode: '600004', address: '18 Temple Street', businessVintageYears: 6, declaredMonthlyIncome: 90000,
    essentialExpenses: 30000, loanAmount: 300000, tenureMonths: 24, purpose: 'Wedding', bankAccountNo: '123456789012',
    bankIfsc: 'HDFC0001234', consentBureau: true, consentAa: true, consentKyc: true, ...over,
  }
}

async function upload(request: APIRequestContext, t: string, id: number, type: string, file: string, mime = 'image/png') {
  return request.post(`/api/applications/${id}/documents`, {
    headers: auth(t),
    multipart: { type, file: { name: path.basename(file), mimeType: mime, buffer: fs.readFileSync(fixture(file)) } },
  })
}

test.describe.configure({ mode: 'serial' })

test('authentication: no token, bad token, wrong password, deactivated user', async ({ request }) => {
  expect((await request.get('/api/applications')).status()).toBe(401)
  expect((await request.get('/api/applications', { headers: { Authorization: 'Bearer not-a-jwt' } })).status()).toBe(401)
  const forged = 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhZG1pbiIsInJvbGUiOiJBRE1JTiIsInJvbGVzIjpbIkFETUlOIl19.' + 'x'.repeat(43)
  expect((await request.get('/api/admin/users', { headers: { Authorization: 'Bearer ' + forged } })).status()).toBe(401)
  expect((await request.post('/api/auth/login', { data: { username: 'sales1', password: 'wrong' } })).status()).toBe(401)
  expect((await request.post('/api/auth/login', { data: { username: "admin' OR '1'='1", password: "x' OR '1'='1" } })).status()).toBe(401)

  const admin = await token(request, 'admin')
  const u = await request.post('/api/admin/users', { headers: auth(admin), data: { username: 'qa.temp', fullName: 'QA Temp', role: 'SALES', password: 'Temp@12345' } })
  expect(u.status()).toBe(200)
  const id = (await u.json()).id
  expect((await request.post('/api/auth/login', { data: { username: 'qa.temp', password: 'Temp@12345' } })).status()).toBe(200)
  expect((await request.patch(`/api/admin/users/${id}/active?value=false`, { headers: auth(admin) })).status()).toBe(200)
  expect((await request.post('/api/auth/login', { data: { username: 'qa.temp', password: 'Temp@12345' } })).status()).toBe(401)
  // an admin cannot lock themselves out
  const me = (await (await request.get('/api/admin/users', { headers: auth(admin) })).json()).find((x: { username: string }) => x.username === 'admin')
  expect((await request.patch(`/api/admin/users/${me.id}/active?value=false`, { headers: auth(admin) })).status()).toBe(422)
})

test('role matrix: every protected action allows only its roles', async ({ request }) => {
  const roles: Record<string, string> = {
    admin: 'ADMIN', sales1: 'SALES', ops1: 'OPERATIONS', co1: 'CREDIT_OFFICER', cm1: 'CREDIT_MANAGER',
    cro1: 'CRO', fraud1: 'FRAUD_ANALYST', comp1: 'COMPLIANCE',
  }
  // application 999999 does not exist: an allowed role gets 404 (or a validation error), a forbidden role gets 403
  const matrix: [string, string, string[], unknown?][] = [
    ['GET', '/api/admin/users', ['ADMIN']],
    ['POST', '/api/admin/branches', ['ADMIN'], { code: 'QA', name: 'x', level: 'NOPE' }],
    ['POST', '/api/admin/products', ['ADMIN'], {}],
    ['GET', '/api/audit', ['ADMIN', 'COMPLIANCE', 'CRO', 'CREDIT_MANAGER']],
    ['GET', '/api/integrations/logs', ['ADMIN', 'OPERATIONS', 'COMPLIANCE']],
    ['POST', '/api/integrations/logs/999999/retry', ['ADMIN', 'OPERATIONS']],
    ['POST', '/api/applications', ['SALES', 'OPERATIONS', 'ADMIN'], {}],
    ['POST', '/api/applications/999999/submit', ['SALES', 'OPERATIONS', 'ADMIN']],
    ['POST', '/api/applications/999999/process', ['OPERATIONS', 'ADMIN']],
    ['POST', '/api/applications/999999/kyc/run', ['OPERATIONS', 'ADMIN']],
    ['POST', '/api/applications/999999/decision/run', ['OPERATIONS', 'ADMIN']],
    ['POST', '/api/applications/999999/documents/1/review', ['OPERATIONS', 'ADMIN'], { action: 'APPROVE', note: 'x' }],
    ['POST', '/api/applications/999999/field-visit', ['OPERATIONS', 'CREDIT_OFFICER', 'ADMIN'], { note: 'visited the shop today' }],
    ['POST', '/api/applications/999999/fraud/disposition', ['FRAUD_ANALYST'], { clear: true, note: 'checked and cleared' }],
    ['POST', '/api/applications/999999/sanction', ['CREDIT_OFFICER', 'CREDIT_MANAGER', 'CRO'], {}],
    ['POST', '/api/applications/999999/sanction/decline', ['CREDIT_OFFICER', 'CREDIT_MANAGER', 'CRO'], { note: 'x' }],
    ['POST', '/api/applications/999999/kfs/accept', ['SALES', 'OPERATIONS', 'ADMIN'], { otp: '1' }],
    ['POST', '/api/applications/999999/disburse', ['OPERATIONS', 'ADMIN']],
  ]
  const failures: string[] = []
  for (const [user, role] of Object.entries(roles)) {
    const t = await token(request, user)
    for (const [method, url, allowed, body] of matrix) {
      const r = await request.fetch(url, { method, headers: auth(t), data: body ?? (method === 'POST' ? {} : undefined) })
      const ok = allowed.includes(role)
      if (ok && r.status() === 403) failures.push(`${role} should be allowed ${method} ${url}`)
      if (!ok && r.status() !== 403) failures.push(`${role} should be blocked from ${method} ${url} (got ${r.status()})`)
      if (r.status() >= 500) failures.push(`${role} ${method} ${url} -> server error ${r.status()}`)
    }
  }
  expect(failures, failures.join('\n')).toEqual([])
})

test('validation: bad input is refused with clear field errors', async ({ request }) => {
  const t = await token(request, 'sales1')
  const cases: [Record<string, unknown>, number, string][] = [
    [{ pan: 'ABCDE1234F' }, 400, 'pan'],              // 4th letter must be P for an individual
    [{ pan: 'KAVPI5566' }, 400, 'pan'],
    [{ mobile: '12345' }, 400, 'mobile'],
    [{ mobile: '5876512345' }, 400, 'mobile'],         // Indian mobiles start 6-9
    [{ dob: '2099-01-01' }, 400, 'dob'],
    [{ loanAmount: -5 }, 400, 'loanAmount'],
    [{ bankIfsc: 'HDFC1234' }, 400, 'bankIfsc'],
    [{ pincode: '012345' }, 400, 'pincode'],
    [{ email: 'not-an-email' }, 400, 'email'],
    [{ loanAmount: 9_000_000 }, 422, 'AMOUNT_OUT_OF_RANGE'],
    [{ tenureMonths: 120 }, 422, 'TENURE_OUT_OF_RANGE'],
    [{ productCode: 'NOPE' }, 422, 'PRODUCT_NOT_LIVE'],
  ]
  for (const [over, status, marker] of cases) {
    const r = await request.post('/api/applications', { headers: auth(t), data: application({ pan: 'QAVPZ1111Q', ...over }) })
    const body = await r.text()
    expect(r.status(), JSON.stringify(over) + ' ' + body).toBe(status)
    expect(body).toContain(marker)
  }
  const bad = await request.post('/api/applications', { headers: { ...auth(t), 'Content-Type': 'application/json' }, data: '{not json' })
  expect(bad.status()).toBe(400)
  // submit without consents
  const draft = await request.post('/api/applications', { headers: auth(t), data: application({ pan: 'QAVPZ2222Q', consentAa: false }) })
  expect(draft.status()).toBe(200)
  const sub = await request.post(`/api/applications/${(await draft.json()).id}/submit`, { headers: auth(t) })
  expect(sub.status()).toBe(422)
  expect(await sub.text()).toContain('CONSENT_MISSING')
})

test('injection and script text are stored as plain text and never executed', async ({ request, page }) => {
  const t = await token(request, 'sales1')
  const nasty = `<img src=x onerror=alert(1)> Robert'); DROP TABLE loan_application;--`
  const r = await request.post('/api/applications', { headers: auth(t), data: application({ pan: 'QAVPZ3333Q', applicantName: nasty.slice(0, 120), purpose: '<script>alert(2)</script>' }) })
  expect(r.status()).toBe(200)
  const id = (await r.json()).id
  const s = await request.get(`/api/applications?q=${encodeURIComponent("' OR 1=1 --")}`, { headers: auth(t) })
  expect(s.status()).toBe(200)
  expect(await s.json()).toEqual([])
  const all = await request.get('/api/applications', { headers: auth(t) })
  expect((await all.json()).length).toBeGreaterThan(5) // the table is still there

  let dialog = false
  page.on('dialog', d => { dialog = true; d.dismiss() })
  await page.goto('/')
  await page.getByLabel('Username').fill('sales1')
  await page.getByLabel('Password').fill('Rhythm@123')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await page.goto(`/applications/${id}`)
  await expect(page.getByText('<script>alert(2)</script>')).toBeVisible()
  expect(dialog).toBe(false)
})

test('uploads: wrong type, empty, too large, unknown document type, other application', async ({ request }) => {
  const t = await token(request, 'sales1')
  const id = (await (await request.post('/api/applications', { headers: auth(t), data: application({ pan: 'QAVPZ4444Q' }) })).json()).id
  const post = (name: string, mime: string, buffer: Buffer, type = 'PAN') =>
    request.post(`/api/applications/${id}/documents`, { headers: auth(t), multipart: { type, file: { name, mimeType: mime, buffer } } })
  expect((await post('a.txt', 'text/plain', Buffer.from('hello'))).status()).toBe(422)
  expect((await post('a.exe', 'application/x-msdownload', Buffer.from('MZ'))).status()).toBe(422)
  expect((await post('empty.png', 'image/png', Buffer.alloc(0))).status()).toBe(422)
  expect((await post('a.png', 'image/png', Buffer.from('x'), 'SELFIE_WITH_CAT')).status()).toBe(422)
  const big = await post('big.pdf', 'application/pdf', Buffer.alloc(11 * 1024 * 1024, 1))
  expect([413]).toContain(big.status())
  // a document of one application cannot be fetched through another application's URL
  const ok = await upload(request, t, id, 'PAN', 'kavya-pan.png')
  const docId = (await ok.json()).id
  expect((await request.get(`/api/applications/${id}/documents/${docId}/content`, { headers: auth(t) })).status()).toBe(200)
  expect((await request.get(`/api/applications/1/documents/${docId}/content`, { headers: auth(t) })).status()).toBe(404)
})

test('state machine: actions out of order are refused and nothing changes', async ({ request }) => {
  const sales = await token(request, 'sales1'), ops = await token(request, 'ops1'), cm = await token(request, 'cm1')
  const id = (await (await request.post('/api/applications', { headers: auth(sales), data: application({ pan: 'QAVPZ5555Q' }) })).json()).id
  const before = (await (await request.get(`/api/applications/${id}/history`, { headers: auth(ops) })).json()).length
  const expectCode = async (p: Promise<{ status(): number; text(): Promise<string> }>, status: number, code: string) => {
    const r = await p
    expect(r.status()).toBe(status)
    expect(await r.text()).toContain(code)
  }
  await expectCode(request.post(`/api/applications/${id}/disburse`, { headers: auth(ops) }), 409, 'NOT_READY')
  await expectCode(request.post(`/api/applications/${id}/kfs/accept`, { headers: auth(sales), data: { otp: '123456' } }), 409, 'NOT_SANCTIONED')
  await expectCode(request.post(`/api/applications/${id}/sanction`, { headers: auth(cm), data: {} }), 409, 'NOT_PENDING')
  await expectCode(request.post(`/api/applications/${id}/decision/run`, { headers: auth(ops) }), 409, 'PRECONDITION_FAILED')
  await expectCode(request.post(`/api/applications/${id}/kyc/run`, { headers: auth(ops) }), 409, 'WRONG_STAGE')
  const after = (await (await request.get(`/api/applications/${id}/history`, { headers: auth(ops) })).json()).length
  expect(after).toBe(before)
  expect((await request.post(`/api/applications/${id}/submit`, { headers: auth(sales) })).status()).toBe(200)
  await expectCode(request.put(`/api/applications/${id}`, { headers: auth(sales), data: application({ pan: 'QAVPZ5555Q', loanAmount: 400000 }) }), 409, 'NOT_EDITABLE')
  await expectCode(request.post(`/api/applications/${id}/submit`, { headers: auth(sales) }), 409, 'INVALID_TRANSITION')
})

test('full API journey with real OCR, concurrent sanction, and no Aadhaar number anywhere', async ({ request }) => {
  const sales = await token(request, 'sales1'), ops = await token(request, 'ops1')
  const r = await request.post('/api/applications', { headers: auth(sales), data: application() })
  expect(r.status(), await r.text()).toBe(200)
  const id = (await r.json()).id
  // an edited PAN is caught, then replaced with the genuine one
  expect((await upload(request, sales, id, 'PAN', 'kavya-pan-photoshopped.jpg', 'image/jpeg')).status()).toBe(200)
  expect((await upload(request, sales, id, 'AADHAAR', 'kavya-aadhaar.png')).status()).toBe(200)
  expect((await upload(request, sales, id, 'SALARY_SLIP', 'kavya-salary.png')).status()).toBe(200)
  expect((await request.post(`/api/applications/${id}/submit`, { headers: auth(sales) })).status()).toBe(200)
  await request.post(`/api/applications/${id}/process`, { headers: auth(ops), timeout: 90_000 })
  let view = await (await request.get(`/api/applications/${id}`, { headers: auth(ops) })).json()
  expect(view.states.DOCS).toBe('IN_REVIEW')
  const docs = await (await request.get(`/api/applications/${id}/documents`, { headers: auth(ops) })).json()
  const edited = docs.find((d: { docType: string }) => d.docType === 'PAN')
  expect(edited.status).toBe('NEEDS_REVIEW')
  expect(edited.tamperJson).toContain('Photoshop')
  const rej = await request.post(`/api/applications/${id}/documents/${edited.id}/review`, { headers: auth(ops), data: { action: 'REJECT', note: 'Edited in Photoshop, asked for original' } })
  expect(rej.status()).toBe(200)
  expect((await upload(request, sales, id, 'PAN', 'kavya-pan.png')).status()).toBe(200)
  await request.post(`/api/applications/${id}/process`, { headers: auth(ops), timeout: 90_000 })
  view = await (await request.get(`/api/applications/${id}`, { headers: auth(ops) })).json()
  expect(view.states.DOCS).toBe('COMPLETE')
  expect(view.states.KYC).toBe('VERIFIED')
  expect(view.states.DECISION).not.toBe('PENDING')
  const checks = await (await request.get(`/api/applications/${id}/kyc-checks`, { headers: auth(ops) })).json()
  expect(checks.filter((c: { result: string }) => c.result === 'FAIL'), JSON.stringify(checks)).toEqual([])

  // the full Aadhaar number (from the specimen generator) must not appear in any API response
  const fullAadhaar = '255235133406'
  const comp = await token(request, 'comp1')
  const everything = [
    await (await request.get(`/api/applications/${id}/documents`, { headers: auth(ops) })).text(),
    await (await request.get(`/api/applications/${id}/snapshots`, { headers: auth(ops) })).text(),
    await (await request.get(`/api/applications/${id}/kyc-checks`, { headers: auth(ops) })).text(),
    await (await request.get(`/api/applications/${id}/integrations`, { headers: auth(ops) })).text(),
    await (await request.get(`/api/audit?applicationId=${id}`, { headers: auth(comp) })).text(),
  ].join('\n').replace(/\s/g, '')
  expect(everything).not.toContain(fullAadhaar)
  expect(everything).not.toContain(fullAadhaar.slice(0, 8))

  // concurrent sanction: exactly one succeeds
  const dec = await (await request.get(`/api/applications/${id}/decision`, { headers: auth(ops) })).json()
  if (dec.decision !== 'REJECT') {
    const who = { L1: 'co1', L2: 'cm1', L3: 'cro1' }[dec.delegationLevel as 'L1' | 'L2' | 'L3']
    const t = await token(request, who)
    const body = { note: 'QA concurrency check: approving referred case' }
    const [a, b] = await Promise.all([
      request.post(`/api/applications/${id}/sanction`, { headers: auth(t), data: body }),
      request.post(`/api/applications/${id}/sanction`, { headers: auth(t), data: body }),
    ])
    expect([a.status(), b.status()].sort()).toEqual([200, 409])
    const recs = await (await request.get(`/api/applications/${id}/sanctions`, { headers: auth(t) })).json()
    expect(recs.filter((x: { action: string }) => x.action === 'APPROVED').length).toBe(1)
  }
})

test('errors do not leak internals; security headers are set', async ({ request }) => {
  const t = await token(request, 'ops1')
  const r = await request.get('/api/applications/not-a-number', { headers: auth(t) })
  expect(r.status()).toBe(400)
  const text = await r.text()
  expect(text).not.toMatch(/Exception|at com\.|org\.springframework|hibernate|SQL/i)
  const nf = await request.get('/api/does-not-exist', { headers: auth(t) })
  expect(nf.status()).toBe(404)
  const home = await request.get('/')
  expect(home.headers()['x-content-type-options']).toBe('nosniff')
  expect(home.headers()['x-frame-options']).toBe('DENY')
  const health = await request.get('/actuator/health')
  expect(health.status()).toBe(200)
  expect(await health.text()).not.toContain('jdbc')
})
