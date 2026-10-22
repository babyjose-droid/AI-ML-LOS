import React, { useState } from 'react'
import { Text, View } from 'react-native'
import { ApiError, api, getServer, getTenant, isWeb, message, setServer, setTenant, setToken } from '../lib/api'
import type { User } from '../session'
import { Btn, C, Card, Input, Note, Screen } from '../ui'

const DEMO: [string, string][] = [['sales1', 'Sales'], ['field1', 'Field'], ['co1', 'Credit L1'], ['cm1', 'Credit L2'], ['cro1', 'CRO'], ['ops1', 'Operations'], ['valuer1', 'Valuer']]

export default function Login({ onLogin }: { onLogin: (u: User) => void }) {
  const [server, setSrv] = useState(getServer())
  const [org, setOrg] = useState(getTenant() ?? '')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [otp, setOtp] = useState('')
  const [needOtp, setNeedOtp] = useState(false)
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)

  async function submit() {
    setBusy(true); setError(undefined)
    try {
      if (!isWeb) await setServer(server)
      await setTenant(org)
      const r = await api.post<{ token: string; user: User }>('/api/auth/login', { username: username.trim(), password, otp: otp || null })
      await setToken(r.token)
      onLogin(r.user)
    } catch (e) {
      if (e instanceof ApiError && e.code === 'MFA_REQUIRED') { setNeedOtp(true); return }
      setError(message(e))
    } finally { setBusy(false) }
  }

  return (
    <Screen title="Rhythm LOS" sub="Sign in · sales, field, credit and operations on the go" testID="login">
      <Card>
        {error ? <Note kind="bad" testID="error">{error}</Note> : null}
        {!isWeb && <Input label="Server address" value={server} onChangeText={setSrv} placeholder="http://192.168.1.20:3000" autoCapitalize="none" autoCorrect={false} keyboardType="url"
          hint="The address of your Rhythm server on this network" />}
        <Input label="Organisation code" value={org} onChangeText={setOrg} autoCapitalize="none" hint="Leave blank for the demo lender" />
        <Input label="Username" value={username} onChangeText={setUsername} autoCapitalize="none" autoCorrect={false} textContentType="username" testID="username" />
        <Input label="Password" value={password} onChangeText={setPassword} secureTextEntry textContentType="password" testID="password" onSubmitEditing={submit} />
        {needOtp && <Input label="Code from your authenticator app" value={otp} onChangeText={setOtp} keyboardType="number-pad" maxLength={6} autoFocus />}
        <Btn title={busy ? 'Signing in…' : 'Sign in'} kind="primary" busy={busy} disabled={!username || !password} onPress={submit} testID="sign-in" />
      </Card>
      <Text style={{ color: C.muted, fontSize: 13, marginBottom: 6 }}>Demo users on a test server (password Rhythm@123):</Text>
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 6 }}>
        {DEMO.map(([u, l]) => <Btn key={u} small title={`${u} · ${l}`} onPress={() => setUsername(u)} />)}
      </View>
    </Screen>
  )
}
