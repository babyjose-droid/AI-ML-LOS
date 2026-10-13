import { useState } from 'react'
import { api } from '../api'
import { useAuth } from '../auth'
import { ErrorBox, Head, useLoad } from '../components/ui'

/** The signed-in user's security: turn on MFA with an authenticator app. */
export default function Account() {
  const { user } = useAuth()
  const me = useLoad(() => api.get<{ mfaEnabled?: boolean; username: string }>('/api/auth/me'), [])
  const [setup, setSetup] = useState<{ secret: string; otpauthUri: string } | null>(null)
  const [code, setCode] = useState('')
  const [error, setError] = useState<unknown>()
  const [done, setDone] = useState(false)
  const on = done || me.data?.mfaEnabled
  async function start() {
    try { setError(undefined); setSetup(await api.post('/api/auth/mfa/setup')) } catch (e) { setError(e) }
  }
  async function enable() {
    try { setError(undefined); await api.post('/api/auth/mfa/enable', { code }); setDone(true); setSetup(null) } catch (e) { setError(e) }
  }
  return (
    <>
      <Head title="My account" sub={`${user?.name} · ${user?.username}`} />
      <ErrorBox error={error ?? me.error} />
      <div className="panel">
        <h3 style={{ marginTop: 0 }}>Two-step sign-in (MFA)</h3>
        {on ? <p>MFA is on. Signing in asks for the 6-digit code from your authenticator app. If you lose your phone, ask the admin to reset MFA.</p> : (
          <>
            <p className="small">Use any authenticator app (Google Authenticator, Microsoft Authenticator and others). After it is on, five wrong codes or passwords lock the account for 15 minutes.</p>
            {!setup && <button className="btn primary" data-action="mfaSetup" onClick={start}>Set up MFA</button>}
            {setup && (
              <div className="form" style={{ gridTemplateColumns: '1fr', maxWidth: 460 }}>
                <p className="small">In the app choose "Enter a setup key" and type this key (time-based):</p>
                <code className="mono" data-mfa-secret style={{ fontSize: 16, wordBreak: 'break-all' }}>{setup.secret.replace(/(.{4})/g, '$1 ').trim()}</code>
                <p className="small muted" style={{ wordBreak: 'break-all' }}>Or open this link on the phone: <a href={setup.otpauthUri}>{setup.otpauthUri}</a></p>
                <label className="fld">6-digit code shown in the app<input name="mfaCode" inputMode="numeric" maxLength={6} value={code} onChange={e => setCode(e.target.value)} /></label>
                <button className="btn primary" onClick={enable}>Turn on MFA</button>
              </div>
            )}
          </>
        )}
      </div>
    </>
  )
}
