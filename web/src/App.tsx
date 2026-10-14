import { useEffect, useState } from 'react'
import { NavLink, Navigate, Route, Routes, useLocation } from 'react-router-dom'
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
import Board from './pages/Board'
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
import { Capital, CoLending } from './pages/Scale'

type NavItem = { to: string; label: string; roles?: Role[] }
const NAV: [string, NavItem[]][] = [
  ['Work', [
    { to: '/overview', label: 'Overview' },
    { to: '/my-work', label: 'My work' },
    { to: '/field', label: 'Field visits', roles: ['FIELD_OFFICER', 'OPERATIONS', 'ADMIN'] },
    { to: '/collateral', label: 'Collateral AI', roles: ['ADMIN', 'OPERATIONS', 'CREDIT_OFFICER', 'CREDIT_MANAGER', 'CRO', 'COMPLIANCE', 'LEGAL_REVIEWER', 'VALUER', 'FIELD_OFFICER', 'SALES'] },
    { to: '/review-queue', label: 'Review queue', roles: ['CREDIT_OFFICER', 'CREDIT_MANAGER', 'CRO', 'ADMIN', 'COMPLIANCE'] },
    { to: '/applications', label: 'Pipeline' },
    { to: '/board', label: 'Stage board' },
    { to: '/applications/new', label: 'New application', roles: ['SALES', 'OPERATIONS', 'ADMIN'] },
  ]],
  ['Portfolio', [
    { to: '/portfolio', label: 'Loan book', roles: ['ADMIN', 'OPERATIONS', 'CREDIT_OFFICER', 'CREDIT_MANAGER', 'CRO', 'COMPLIANCE'] },
    { to: '/pricing', label: 'Rate simulator', roles: ['ADMIN', 'CREDIT_MANAGER', 'CRO'] },
    { to: '/colending', label: 'Co-lending', roles: ['ADMIN', 'CRO', 'CREDIT_MANAGER', 'OPERATIONS', 'COMPLIANCE'] },
    { to: '/capital', label: 'Capital and liquidity', roles: ['ADMIN', 'CRO', 'COMPLIANCE'] },
    { to: '/compliance', label: 'Compliance and returns', roles: ['ADMIN', 'COMPLIANCE', 'CRO', 'CREDIT_MANAGER'] },
    { to: '/scores', label: 'Branch and officer scores', roles: ['ADMIN', 'CRO', 'CREDIT_MANAGER', 'OPERATIONS', 'COMPLIANCE'] },
    { to: '/assistant', label: 'Policy assistant', roles: ['ADMIN', 'SALES', 'OPERATIONS', 'CREDIT_OFFICER', 'CREDIT_MANAGER', 'CRO', 'FRAUD_ANALYST', 'COMPLIANCE', 'FIELD_OFFICER', 'LEGAL_REVIEWER', 'VALUER'] },
  ]],
  ['Operations', [
    { to: '/reports', label: 'Reports', roles: ['ADMIN', 'CRO', 'CREDIT_MANAGER', 'COMPLIANCE', 'OPERATIONS'] },
    { to: '/integrations', label: 'Integration hub', roles: ['ADMIN', 'OPERATIONS', 'COMPLIANCE'] },
    { to: '/fairness', label: 'Fairness', roles: ['CRO', 'CREDIT_MANAGER', 'COMPLIANCE', 'ADMIN'] },
    { to: '/audit', label: 'Audit trail', roles: ['ADMIN', 'COMPLIANCE', 'CRO', 'CREDIT_MANAGER'] },
    { to: '/state-machine', label: 'State machine' },
  ]],
  ['Admin', [
    { to: '/admin/branches', label: 'Branches' },
    { to: '/admin/users', label: 'Users', roles: ['ADMIN'] },
    { to: '/admin/masters', label: 'Masters and roles' },
    { to: '/admin/products', label: 'Products' },
    { to: '/admin/workflows', label: 'Workflow designer' },
    { to: '/admin/policy', label: 'Credit policy studio' },
    { to: '/admin/models', label: 'Risk models', roles: ['CREDIT_MANAGER', 'CRO', 'ADMIN', 'COMPLIANCE'] },
  ]],
]

export default function App() {
  const [user, setUser] = useState<User | null>(null)
  const [ready, setReady] = useState(!hasToken())
  const [open, setOpen] = useState(false)
  const loc = useLocation()

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
        <b>Rhythm LOS</b>
      </div>
      <div className="shell">
        <aside className={'side' + (open ? ' open' : '')}>
          <div className="brand"><i /> <div>Rhythm<small>AI lending · LOS</small></div></div>
          {NAV.map(([g, items]) => (
            <div key={g}>
              <div className="navgroup">{g}</div>
              {items.filter(i => !i.roles || i.roles.includes(user.role)).map(i => (
                <NavLink key={i.to} to={i.to} end className={({ isActive }) => 'nav' + (isActive ? ' active' : '')}>{i.label}</NavLink>
              ))}
            </div>
          ))}
          <div className="who">
            <b><NavLink to="/account" style={{ color: 'inherit' }}>{user.name}</NavLink></b>
            <span className="muted">{user.username} · {user.role.replace(/_/g, ' ').toLowerCase()}</span>
            <div style={{ marginTop: 10 }}><button className="btn sm" onClick={logout}>Sign out</button></div>
          </div>
        </aside>
        <main className="main">
          <Routes>
            <Route path="/" element={<Navigate to="/my-work" replace />} />
            <Route path="/my-work" element={<MyWork />} />
            <Route path="/review-queue" element={<ReviewQueue />} />
            <Route path="/overview" element={<Overview />} />
            <Route path="/board" element={<Board />} />
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
            <Route path="/colending" element={<CoLending />} />
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
            <Route path="*" element={<Navigate to="/my-work" replace />} />
          </Routes>
        </main>
      </div>
    </AuthContext.Provider>
  )
}
