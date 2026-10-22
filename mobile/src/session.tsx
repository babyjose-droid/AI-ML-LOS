// Who is signed in, whether the phone is online, and how many items wait in the outbox. Syncs when the signal returns.
import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react'
import { AppState } from 'react-native'
import { watchNet } from './lib/net'
import { jobs, subscribe, sync, type SyncReport } from './lib/outbox'

export type Role = 'ADMIN' | 'SALES' | 'OPERATIONS' | 'CREDIT_OFFICER' | 'CREDIT_MANAGER' | 'CRO' | 'FRAUD_ANALYST' | 'COMPLIANCE' | 'FIELD_OFFICER' | 'LEGAL_REVIEWER' | 'VALUER'
export interface User { username: string; name: string; role: Role; branchId: number; sanctionLevel: number }

interface Session { user: User; online: boolean; waiting: number; failed: number; last?: SyncReport; syncNow: () => Promise<SyncReport | undefined>; signOut: () => void }
const Ctx = createContext<Session | null>(null)
export const useSession = () => useContext(Ctx)!

export const ROLE_NAME: Record<Role, string> = {
  ADMIN: 'Administrator', SALES: 'Sales officer', OPERATIONS: 'Operations', CREDIT_OFFICER: 'Credit officer', CREDIT_MANAGER: 'Credit manager', CRO: 'Chief risk officer',
  FRAUD_ANALYST: 'Fraud analyst', COMPLIANCE: 'Compliance', FIELD_OFFICER: 'Field officer', LEGAL_REVIEWER: 'Legal reviewer', VALUER: 'Valuer',
}
export const canCreate = (r: Role) => ['SALES', 'OPERATIONS', 'ADMIN'].includes(r)

export function SessionProvider({ user, signOut, children }: { user: User; signOut: () => void; children: React.ReactNode }) {
  const [online, setOnline] = useState(true)
  const [waiting, setWaiting] = useState(0)
  const [failed, setFailed] = useState(0)
  const [last, setLast] = useState<SyncReport>()
  const onlineRef = useRef(true)

  const count = useCallback(async () => {
    const j = await jobs()
    setWaiting(j.filter(x => x.status === 'WAITING').length)
    setFailed(j.filter(x => x.status === 'FAILED').length)
  }, [])

  const retryTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const syncNow = useCallback(async (): Promise<SyncReport | undefined> => {
    if (!onlineRef.current) return undefined
    const r = await sync()
    if (r.sent.length || r.failed.length) setLast(r)
    await count()
    // the network often comes back a little after the phone says it is connected: try again shortly
    clearTimeout(retryTimer.current)
    if (r.offline && onlineRef.current) retryTimer.current = setTimeout(() => { syncNow() }, 5000)
    return r
  }, [count])

  useEffect(() => {
    count()
    const unsub = subscribe(count)
    const net = watchNet(on => {
      onlineRef.current = on
      setOnline(on)
      if (on) syncNow()
    })
    const app = AppState.addEventListener('change', s => { if (s === 'active') syncNow() })
    const timer = setInterval(() => { syncNow() }, 60_000)
    syncNow()
    return () => { unsub(); net(); app.remove(); clearInterval(timer); clearTimeout(retryTimer.current) }
  }, [count, syncNow])

  return <Ctx.Provider value={{ user, online, waiting, failed, last, syncNow, signOut }}>{children}</Ctx.Provider>
}
