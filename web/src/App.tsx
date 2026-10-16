import { useEffect, useState } from 'react'
import { Link, NavLink, Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { api, hasToken, setToken, setUnauthorizedHandler } from './api'
import { AuthContext } from './auth'
import type { Role, User } from './types'
import Login from './pages/Login'
import MyWork from './pages/MyWork'
import Pipeline from './pages/Pipeline'
import NewApplication from './pages/NewApplication'
import Application360 from './pages/Application360'
import Integrations from './pages/Integrations'
import Audit from './pages/Audit'
import { Branches, Users } from './pages/Admin'
import Products from './pages/Products'
import PolicyStudio from './pages/PolicyStudio'
import Reports from './pages/Reports'
import Models from './pages/Models'
import Workflows from './pages/Workflows'
import StateMachinePage from './pages/StateMachine'
import ReviewQueue from './pages/ReviewQueue'
import Overview from './pages/Overview'
import Masters from './pages/Masters'
import Account from './pages/Account'
import SanctionLetter from './pages/SanctionLetter'
import Fairness from './pages/Fairness'
import FieldApp from './pages/FieldApp'
import Portfolio from './pages/Portfolio'
import Pricing from './pages/Pricing'
import Compliance from './pages/Compliance'
import Scores from './pages/Scores'
import Assistant from './pages/Assistant'
import CollateralOverview, { CollateralCase } from './collateral/Collateral'
import VkycQueue from './vkyc/VkycQueue'
import Templates from './pages/Templates'
import DocTemplates from './docgen/DocTemplates'
import { Capital, CoLending } from './pages/Scale'
import { Benchmarking, Onboarding, Platform } from './pages/Tenancy'
import { useLoad } from './components/ui'
import { AppPicker, CustomerPicker } from './pages/Pickers'
import { api as client } from './api'

type NavItem = { to: string; label: string; roles?: Role[]; scr?: string; badge?: 'mywork' | 'review' }
const PORTFOLIO_ROLES: Role[] = ['ADMIN', 'OPERATIONS', 'CREDIT_OFFICER', 'CREDIT_MANAGER', 'CRO', 'COMPLIANCE']
const CASE_ROLES: Role[] = ['ADMIN', 'OPERATIONS', 'CREDIT_OFFICER', 'CREDIT_MANAGER', 'CRO', 'FRAUD_ANALYST', 'COMPLIANCE']
const COLLATERAL_ROLES: Role[] = ['ADMIN', 'OPERATIONS', 'CREDIT_OFFICER', 'CREDIT_MANAGER', 'CRO', 'COMPLIANCE', 'LEGAL_REVIEWER', 'VALUER', 'FIELD_OFFICER', 'SALES']
const REVIEW_ROLES: Role[] = ['CREDIT_OFFICER', 'CREDIT_MANAGER', 'CRO', 'ADMIN', 'COMPLIANCE']
// Menu groups and order follow prototype v3.1 (Start, Work, Origination, Credit, Growth, Portfolio, Compliance, Management, Admin, Integrations).
const NAV: [string, NavItem[]][] = [
  ['Start', [
    { to: '/overview', label: 'Overview', scr: 'SCR-001' },
  ]],
  ['Work', [
    { to: '/my-work', label: 'My work', scr: 'SCR-091', badge: 'mywork' },
    { to: '/applications/new', label: 'New application', roles: ['SALES', 'OPERATIONS', 'ADMIN'], scr: 'SCR-010' },
    { to: '/customers', label: 'Customer 360', roles: CASE_ROLES, scr: 'SCR-092' },
    { to: '/state-machine', label: 'State machine', scr: 'SCR-093' },
  ]],
  ['Origination', [
    { to: '/applications', label: 'Pipeline', scr: 'SCR-020' },
    { to: '/app360', label: 'Application 360', scr: 'SCR-021' },
    { to: '/field', label: 'Field officer app', roles: ['FIELD_OFFICER', 'OPERATIONS', 'ADMIN'], scr: 'SCR-040' },
    { to: '/vkyc-queue', label: 'Video KYC queue', roles: ['ADMIN', 'OPERATIONS', 'CREDIT_OFFICER', 'CREDIT_MANAGER'] },
  ]],
  ['Credit', [
    { to: '/collateral', label: 'Collateral AI', roles: COLLATERAL_ROLES, scr: 'SCR-140' },
    { to: '/collateral?tab=reviews', label: 'Collateral reviews', roles: COLLATERAL_ROLES, scr: 'SCR-147' },
    { to: '/review-queue', label: 'Review queue', roles: REVIEW_ROLES, scr: 'SCR-051', badge: 'review' },
    { to: '/admin/policy', label: 'Credit policy studio', scr: 'SCR-060' },
    { to: '/assistant', label: 'Policy assistant', roles: ['ADMIN', 'SALES', 'OPERATIONS', 'CREDIT_OFFICER', 'CREDIT_MANAGER', 'CRO', 'FRAUD_ANALYST', 'COMPLIANCE', 'FIELD_OFFICER', 'LEGAL_REVIEWER', 'VALUER'], scr: 'SCR-061' },
  ]],
  ['Growth', [
    { to: '/portfolio?tab=topups', label: 'Top-up offers', roles: PORTFOLIO_ROLES, scr: 'SCR-070' },
    { to: '/pricing', label: 'Rate simulator', roles: ['ADMIN', 'CREDIT_MANAGER', 'CRO'], scr: 'SCR-071' },
    { to: '/colending', label: 'Co-lending', roles: ['ADMIN', 'CRO', 'CREDIT_MANAGER', 'OPERATIONS', 'COMPLIANCE'], scr: 'SCR-072' },
  ]],
  ['Portfolio', [
    { to: '/portfolio', label: 'Portfolio & EWS', roles: PORTFOLIO_ROLES, scr: 'SCR-080' },
    { to: '/admin/models', label: 'Risk models', roles: ['CREDIT_MANAGER', 'CRO', 'ADMIN', 'COMPLIANCE'], scr: 'SCR-062' },
    { to: '/portfolio?tab=restructures', label: 'Restructuring', roles: PORTFOLIO_ROLES, scr: 'SCR-082' },
    { to: '/portfolio?tab=collections', label: 'Collections', roles: PORTFOLIO_ROLES, scr: 'SCR-083' },
  ]],
  ['Compliance', [
    { to: '/compliance', label: 'Compliance pack', roles: ['ADMIN', 'COMPLIANCE', 'CRO', 'CREDIT_MANAGER'], scr: 'SCR-100' },
    { to: '/compliance?tab=classification', label: 'Regulatory returns', roles: ['ADMIN', 'COMPLIANCE', 'CRO', 'CREDIT_MANAGER'], scr: 'SCR-102' },
    { to: '/fairness', label: 'Fairness', roles: ['CRO', 'CREDIT_MANAGER', 'COMPLIANCE', 'ADMIN'], scr: 'SCR-101' },
    { to: '/audit', label: 'Audit trail', roles: ['ADMIN', 'COMPLIANCE', 'CRO', 'CREDIT_MANAGER'] },
  ]],
  ['Management', [
    { to: '/scores', label: 'Branch and officer scores', roles: ['ADMIN', 'CRO', 'CREDIT_MANAGER', 'OPERATIONS', 'COMPLIANCE'], scr: 'SCR-110' },
    { to: '/capital', label: 'Capital and liquidity', roles: ['ADMIN', 'CRO', 'COMPLIANCE'], scr: 'SCR-111' },
    { to: '/benchmarking', label: 'Benchmarking', roles: ['ADMIN', 'CRO', 'CREDIT_MANAGER', 'COMPLIANCE'], scr: 'SCR-112' },
    { to: '/reports', label: 'Reports', roles: ['ADMIN', 'CRO', 'CREDIT_MANAGER', 'COMPLIANCE', 'OPERATIONS'] },
  ]],
  ['Admin', [
    { to: '/onboarding', label: 'Onboarding and go-live', roles: ['ADMIN', 'CRO', 'COMPLIANCE'], scr: 'SCR-094' },
    { to: '/admin/branches', label: 'Branches', scr: 'SCR-120' },
    { to: '/admin/users', label: 'Users', roles: ['ADMIN'], scr: 'SCR-121' },
    { to: '/admin/masters', label: 'Masters and roles', scr: 'SCR-123' },
    { to: '/admin/products', label: 'Products', scr: 'SCR-124' },
    { to: '/admin/workflows', label: 'Workflow designer', scr: 'SCR-125' },
    { to: '/admin/doc-templates', label: 'Document templates', roles: ['ADMIN', 'COMPLIANCE', 'CREDIT_MANAGER', 'CRO', 'LEGAL_REVIEWER', 'OPERATIONS'] },
    { to: '/admin/templates', label: 'Message templates', roles: ['ADMIN', 'COMPLIANCE', 'CRO', 'CREDIT_MANAGER', 'OPERATIONS'] },
    { to: '/platform/tenants', label: 'Tenants (platform)', roles: ['ADMIN'] },
  ]],
  ['Integrations', [
    { to: '/integrations', label: 'Integration hub', roles: ['ADMIN', 'OPERATIONS', 'COMPLIANCE'], scr: 'SCR-130' },
  ]],
]
const ALL_ITEMS = NAV.flatMap(([, items]) => items)
const roleName = (r: string) => r === 'CRO' ? 'CRO' : r.replace(/_/g, ' ').toLowerCase().replace(/^./, c => c.toUpperCase())

/** The menu item for the current address: path and ?tab= both match, else the plain path, else the closest parent path. */
function activeItem(pathname: string, search: string): NavItem | undefined {
  const tab = new URLSearchParams(search).get('tab')
  const path = (to: string) => to.split('?')[0]
  const tabOf = (to: string) => new URLSearchParams(to.split('?')[1] ?? '').get('tab')
  return ALL_ITEMS.find(i => path(i.to) === pathname && tab && tabOf(i.to) === tab)
    ?? ALL_ITEMS.find(i => path(i.to) === pathname && !tabOf(i.to))
    ?? (/^\/applications\/\d+/.test(pathname) ? ALL_ITEMS.find(i => i.to === '/app360') : undefined)
    ?? (/^\/collateral\/\d+/.test(pathname) ? ALL_ITEMS.find(i => i.to === '/collateral') : undefined)
}

function readJson<T>(key: string, def: T): T { try { const v = localStorage.getItem(key); return v ? JSON.parse(v) as T : def } catch { return def } }
function writeJson(key: string, v: unknown) { try { localStorage.setItem(key, JSON.stringify(v)) } catch { /* ignore */ } }

function SideNav({ user, active }: { user: User; active?: NavItem }) {
  const [openMap, setOpenMap] = useState<Record<string, boolean>>(() => readJson('rhythm_nav_open', {}))
  const loc = useLocation()
  const counts = useCounts(user, loc.pathname)
  const groups = NAV.map(([g, items]) => [g, items.filter(i => !i.roles || i.roles.includes(user.role))] as const).filter(([, items]) => items.length)
  const isOpen = (g: string, items: readonly NavItem[]) => g === 'Start' || (openMap[g] ?? false) || items.some(i => i === active)
  const save = (m: Record<string, boolean>) => { setOpenMap(m); writeJson('rhythm_nav_open', m) }
  const toggle = (g: string, items: readonly NavItem[]) => save({ ...openMap, [g]: !isOpen(g, items) })
  const setAll = (v: boolean) => save(Object.fromEntries(groups.map(([g]) => [g, v])))
  return (
    <nav className="navlist" aria-label="Sections">
      <div className="navtools">
        <button type="button" onClick={() => setAll(true)} data-nav-expand>Expand all</button>
        <button type="button" onClick={() => setAll(false)} data-nav-collapse>Collapse all</button>
      </div>
      {groups.map(([g, items]) => {
        const open = isOpen(g, items)
        const hidden = open ? 0 : items.reduce((n, i) => n + (i.badge ? counts[i.badge] ?? 0 : 0), 0)
        return (
          <div key={g} className={'ng' + (open ? '' : ' closed')} data-nav-group={g}>
            <button type="button" className="ngl" aria-expanded={open} onClick={() => toggle(g, items)}>
              {g}{hidden > 0 && <b className="cnt gcnt">{hidden}</b>}<span className="chev" aria-hidden>{open ? '−' : '+'}</span>
            </button>
            {items.map(i => (
              <Link key={i.to} to={i.to} className={'nav' + (i === active ? ' active' : '')} aria-current={i === active ? 'page' : undefined}>
                {i.label}{i.badge && counts[i.badge] ? <b className="cnt">{counts[i.badge]}</b> : null}
              </Link>
            ))}
          </div>
        )
      })}
          </nav>
  )
}

/** Count badges: open tasks in My work and cases waiting in the review queue (refreshed when the page changes). */
function useCounts(user: User, pathname: string) {
  const [c, setC] = useState<{ mywork?: number; review?: number }>({})
  useEffect(() => {
    let live = true
    client.get<unknown[]>('/api/my-work').then(t => live && setC(x => ({ ...x, mywork: t.length }))).catch(() => {})
    if (REVIEW_ROLES.includes(user.role)) client.get<unknown[]>('/api/review-queue').then(t => live && setC(x => ({ ...x, review: t.length }))).catch(() => {})
    return () => { live = false }
  }, [user.role, pathname])
  return c
}

export default function App() {
  const [user, setUser] = useState<User | null>(null)
  const [ready, setReady] = useState(!hasToken())
  const [open, setOpen] = useState(false)
  const loc = useLocation()
  const [navHidden, setNavHidden] = useState(() => readJson('rhythm_nav_hidden', false))
  const setHidden = (v: boolean) => { setNavHidden(v); writeJson('rhythm_nav_hidden', v) }
  const active = activeItem(loc.pathname, loc.search)

  const logout = () => { setToken(null); setUser(null) }
  useEffect(() => { setUnauthorizedHandler(() => setUser(null)) }, [])
  useEffect(() => {
    if (hasToken()) api.get<User>('/api/auth/me').then(setUser).catch(() => setToken(null)).finally(() => setReady(true))
  }, [])
  useEffect(() => setOpen(false), [loc.pathname])
  // idle timeout (FSD 23): sign out after the configured minutes without keyboard, mouse or touch
  useEffect(() => {
    if (!user) return
    let minutes = 15
    try { minutes = Number(localStorage.getItem('rhythm_idle_minutes')) || 15 } catch { /* ignore */ }
    let last = Date.now()
    const touch = () => { last = Date.now() }
    const evs = ['mousemove', 'keydown', 'click', 'touchstart', 'scroll']
    evs.forEach(e => window.addEventListener(e, touch, { passive: true }))
    const t = window.setInterval(() => { if (Date.now() - last > minutes * 60_000) { setToken(null); setUser(null) } }, 30_000)
    return () => { evs.forEach(e => window.removeEventListener(e, touch)); window.clearInterval(t) }
  }, [user])

  if (!ready) return null
  if (!user) return <Login onLogin={u => setUser(u)} />

  return (
    <AuthContext.Provider value={{ user, logout }}>
      <div className="topbar">
        <button className="btn sm" onClick={() => setOpen(!open)} aria-label="Menu">☰</button>
        <b>Rhythm</b>
      </div>
      {navHidden && <button className="btn sm show-nav" onClick={() => setHidden(false)} aria-label="Show menu" data-nav-show>☰ Menu</button>}
      <div className={'shell' + (navHidden ? ' nav-hidden' : '')}>
        <aside className={'side' + (open ? ' open' : '')}>
          <div className="brand"><i /> <div>Rhythm<small>AI lending platform</small></div>
            <button className="hide-nav" onClick={() => setHidden(true)} aria-label="Hide menu" title="Hide menu" data-nav-hide>«</button></div>
          <div className="viewing">Signed in as<b>{roleName(user.role)}</b></div>
          <SideNav user={user} active={active} />
          <div className="meta">
            <TenantBadge />
            <b><NavLink to="/account" style={{ color: 'inherit' }}>{user.name}</NavLink></b>
            <span>{user.username} · {roleName(user.role)}</span>
            <div style={{ marginTop: 8 }}><button className="btn sm" onClick={logout}>Sign out</button></div>
          </div>
        </aside>
        <main className="main">
          {active && <div className="scrtag" data-testid="scrtag">{active.scr && <span>{active.scr}</span>}{active.roles ? 'Roles: ' + active.roles.map(roleName).join(', ') : 'All roles'}</div>}
          <Routes>
            <Route path="/" element={<Navigate to="/my-work" replace />} />
            <Route path="/my-work" element={<MyWork />} />
            <Route path="/review-queue" element={<ReviewQueue />} />
            <Route path="/overview" element={<Overview />} />
            <Route path="/board" element={<Navigate to="/applications?view=board" replace />} />
            <Route path="/app360" element={<AppPicker />} />
            <Route path="/customers" element={<CustomerPicker />} />
            <Route path="/admin/masters" element={<Masters />} />
            <Route path="/account" element={<Account />} />
            <Route path="/applications" element={<Pipeline />} />
            <Route path="/applications/new" element={<NewApplication />} />
            <Route path="/applications/:id/edit" element={<NewApplication />} />
            <Route path="/applications/:id" element={<Application360 />} />
            <Route path="/applications/:id/letter" element={<SanctionLetter />} />
            <Route path="/fairness" element={<Fairness />} />
            <Route path="/field" element={<FieldApp />} />
            <Route path="/portfolio" element={<Portfolio />} />
            <Route path="/pricing" element={<Pricing />} />
            <Route path="/compliance" element={<Compliance />} />
            <Route path="/scores" element={<Scores />} />
            <Route path="/assistant" element={<Assistant />} />
            <Route path="/collateral" element={<CollateralOverview />} />
            <Route path="/vkyc-queue" element={<VkycQueue />} />
            <Route path="/colending" element={<CoLending />} />
            <Route path="/platform/tenants" element={<Platform />} />
            <Route path="/onboarding" element={<Onboarding />} />
            <Route path="/benchmarking" element={<Benchmarking />} />
            <Route path="/capital" element={<Capital />} />
            <Route path="/collateral/:id" element={<CollateralCase />} />
            <Route path="/integrations" element={<Integrations />} />
            <Route path="/audit" element={<Audit />} />
            <Route path="/reports" element={<Reports />} />
            <Route path="/state-machine" element={<StateMachinePage />} />
            <Route path="/admin/branches" element={<Branches />} />
            <Route path="/admin/users" element={<Users />} />
            <Route path="/admin/products" element={<Products />} />
            <Route path="/admin/policy" element={<PolicyStudio />} />
            <Route path="/admin/models" element={<Models />} />
            <Route path="/admin/workflows" element={<Workflows />} />
            <Route path="/admin/templates" element={<Templates />} />
            <Route path="/admin/doc-templates" element={<DocTemplates />} />
            <Route path="*" element={<Navigate to="/my-work" replace />} />
          </Routes>
        </main>
      </div>
    </AuthContext.Provider>
  )
}

function TenantBadge() {
  const t = useLoad(() => client.get<{ code: string; name: string; status: string; isDefault: boolean }>('/api/tenant'), [])
  if (!t.data) return null
  return <span data-testid={t.data.isDefault ? undefined : 'tenant-badge'}>Tenant {t.data.code}{!t.data.isDefault && <> · <b style={{ display: 'inline' }}>{t.data.name}</b></>}
    {t.data.status !== 'LIVE' && <span className="pill warn" style={{ marginLeft: 4 }}>{t.data.status.toLowerCase()}</span>}</span>
}
