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
import { Branches, Users, Products, Policy } from './pages/Admin'
import StateMachinePage from './pages/StateMachine'

type NavItem = { to: string; label: string; roles?: Role[] }
const NAV: [string, NavItem[]][] = [
  ['Work', [
    { to: '/my-work', label: 'My work' },
    { to: '/applications', label: 'Pipeline' },
    { to: '/applications/new', label: 'New application', roles: ['SALES', 'OPERATIONS', 'ADMIN'] },
  ]],
  ['Operations', [
    { to: '/integrations', label: 'Integration logs', roles: ['ADMIN', 'OPERATIONS', 'COMPLIANCE'] },
    { to: '/audit', label: 'Audit trail', roles: ['ADMIN', 'COMPLIANCE', 'CRO', 'CREDIT_MANAGER'] },
    { to: '/state-machine', label: 'State machine' },
  ]],
  ['Admin', [
    { to: '/admin/branches', label: 'Branches' },
    { to: '/admin/users', label: 'Users', roles: ['ADMIN'] },
    { to: '/admin/products', label: 'Products' },
    { to: '/admin/policy', label: 'Credit policy' },
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
            <b>{user.name}</b>
            <span className="muted">{user.username} · {user.role.replace(/_/g, ' ').toLowerCase()}</span>
            <div style={{ marginTop: 10 }}><button className="btn sm" onClick={logout}>Sign out</button></div>
          </div>
        </aside>
        <main className="main">
          <Routes>
            <Route path="/" element={<Navigate to="/my-work" replace />} />
            <Route path="/my-work" element={<MyWork />} />
            <Route path="/applications" element={<Pipeline />} />
            <Route path="/applications/new" element={<NewApplication />} />
            <Route path="/applications/:id/edit" element={<NewApplication />} />
            <Route path="/applications/:id" element={<Application360 />} />
            <Route path="/integrations" element={<Integrations />} />
            <Route path="/audit" element={<Audit />} />
            <Route path="/state-machine" element={<StateMachinePage />} />
            <Route path="/admin/branches" element={<Branches />} />
            <Route path="/admin/users" element={<Users />} />
            <Route path="/admin/products" element={<Products />} />
            <Route path="/admin/policy" element={<Policy />} />
            <Route path="*" element={<Navigate to="/my-work" replace />} />
          </Routes>
        </main>
      </div>
    </AuthContext.Provider>
  )
}
