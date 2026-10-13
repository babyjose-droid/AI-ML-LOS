import { useState } from 'react'
import { ApiError, api, setToken } from '../api'
import { ErrorBox } from '../components/ui'
import type { User } from '../types'

const DEMO = [
  ['sales1', 'Sales / DSA'], ['ops1', 'Operations'], ['co1', 'Credit officer (L1)'], ['cm1', 'Credit manager (L2)'],
  ['cro1', 'CRO (L3)'], ['fraud1', 'Fraud analyst'], ['comp1', 'Compliance'], ['field1', 'Field officer'], ['admin', 'Tenant admin'],
]

export default function Login({ onLogin }: { onLogin: (u: User) => void }) {
  const [username, setUsername] = useState('sales1')
  const [password, setPassword] = useState('Rhythm@123')
  const [error, setError] = useState<unknown>()
  const [busy, setBusy] = useState(false)
  const [otp, setOtp] = useState('')
  const [needOtp, setNeedOtp] = useState(false)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true)
    try {
      const r = await api.post<{ token: string; user: User; idleTimeoutMinutes?: number }>('/api/auth/login', { username, password, otp: otp || null })
      setToken(r.token)
      try { if (r.idleTimeoutMinutes) localStorage.setItem('rhythm_idle_minutes', String(r.idleTimeoutMinutes)) } catch { /* ignore */ }
      onLogin(r.user)
    } catch (err) {
      if (err instanceof ApiError && err.code === 'MFA_REQUIRED') { setNeedOtp(true); setError(undefined); return }
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login">
      <div className="panel">
        <div className="brand" style={{ padding: '0 0 16px' }}><i /> <div>Rhythm<small>AI lending · loan origination</small></div></div>
        <ErrorBox error={error} />
        <form onSubmit={submit} className="form" style={{ gridTemplateColumns: '1fr' }}>
          <label className="fld">Username<input name="username" value={username} onChange={e => setUsername(e.target.value)} autoComplete="username" /></label>
          <label className="fld">Password<input name="password" type="password" value={password} onChange={e => setPassword(e.target.value)} autoComplete="current-password" /></label>
          {needOtp && <label className="fld">Code from your authenticator app<input name="otp" inputMode="numeric" maxLength={6} autoFocus value={otp} onChange={e => setOtp(e.target.value)} autoComplete="one-time-code" /></label>}
          <button className="btn primary" disabled={busy} type="submit">{busy ? 'Signing in…' : 'Sign in'}</button>
        </form>
        <p className="small muted" style={{ marginTop: 18 }}>Demo users (password Rhythm@123):</p>
        <div className="row">
          {DEMO.map(([u, l]) => <button key={u} className="btn sm" type="button" onClick={() => setUsername(u)} title={l}>{u}</button>)}
        </div>
      </div>
    </div>
  )
}
