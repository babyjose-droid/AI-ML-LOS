// Outbox: work done without signal (new applications, document and photo uploads, field visit reports) is saved on the
// phone and sent in order when the connection returns. A job can depend on an earlier one: documents captured with a new
// application wait for it, and their address uses the id the server gave it (":app" in the path).
import { vault } from './vault'
import { ApiError, OfflineError, api, type Field } from './api'
import { discard, type LocalFile } from './files'

export type JobField = { name: string; value: string } | { name: string; file: LocalFile }

export interface Job {
  id: string
  kind: 'application' | 'submit' | 'document' | 'visit' | 'photo' | 'upload'
  label: string
  method: 'POST' | 'PUT'
  path: string                  // may contain ":app", filled from the job named in dependsOn
  json?: unknown
  fields?: JobField[]
  dependsOn?: string
  createdAt: number
  attempts: number
  status: 'WAITING' | 'FAILED'
  error?: string
  owner?: string                // the officer who captured it; only their session sends it
}

const KEY = 'outbox'
const RESULTS = 'outbox:results'
let listeners: (() => void)[] = []
let running = false
let owner = ''
export const setOwner = (u: string) => { owner = u }

export const newId = () => Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10)

async function all(): Promise<Job[]> {
  const s = await vault.get(KEY)
  return s ? JSON.parse(s) : []
}
/** The signed-in officer's items. */
export async function jobs(): Promise<Job[]> {
  return (await all()).filter(j => j.owner === owner)
}
async function save(mine: Job[]) {
  const others = (await all()).filter(j => j.owner !== owner)
  await vault.set(KEY, JSON.stringify([...others, ...mine]))
  listeners.forEach(l => l())
}
async function results(): Promise<Record<string, number>> {
  const s = await vault.get(RESULTS)
  return s ? JSON.parse(s) : {}
}

export function subscribe(f: () => void) {
  listeners.push(f)
  return () => { listeners = listeners.filter(x => x !== f) }
}

export async function add(...j: Omit<Job, 'createdAt' | 'attempts' | 'status'>[]) {
  const list = await jobs()
  await save([...list, ...j.map(x => ({ ...x, createdAt: Date.now(), attempts: 0, status: 'WAITING' as const, owner }))])
}

export async function remove(id: string) {
  const list = await jobs()
  const gone = list.filter(j => j.id === id || j.dependsOn === id)
  for (const j of gone) for (const f of j.fields ?? []) if ('file' in f) await discard(f.file)
  await save(list.filter(j => !gone.includes(j)))
}

export async function retry(id: string) {
  await save((await jobs()).map(j => j.id === id ? { ...j, status: 'WAITING', error: undefined } : j))
}

export interface SyncReport { sent: string[]; failed: string[]; offline: boolean }

/** Sends waiting jobs in order. Stops at the first one that cannot reach the server; a job the server refuses is kept as FAILED. */
export async function sync(onSent?: (j: Job, r: unknown) => void): Promise<SyncReport> {
  const report: SyncReport = { sent: [], failed: [], offline: false }
  if (running) return report
  running = true
  try {
    for (;;) {
      const list = await jobs()
      const done = await results()
      const next = list.find(j => j.status === 'WAITING' && (!j.dependsOn || done[j.dependsOn] !== undefined || !list.some(x => x.id === j.dependsOn)))
      if (!next) break
      if (next.dependsOn && done[next.dependsOn] === undefined) {
        // the job it depended on failed and was deleted: this one can no longer be sent
        await save(list.map(j => j.id === next.id ? { ...j, status: 'FAILED', error: 'The application it belongs to was not created' } : j))
        report.failed.push(next.label)
        continue
      }
      const path = next.path.replace(':app', String(next.dependsOn ? done[next.dependsOn] : ''))
      try {
        const r = await api.send<any>(next.method, path, next.fields ? undefined : next.json, next.fields as Field[] | undefined)
        if (r && typeof r === 'object' && typeof r.id === 'number') await vault.set(RESULTS, JSON.stringify({ ...done, [next.id]: r.id }))
        else await vault.set(RESULTS, JSON.stringify({ ...done, [next.id]: 0 }))
        for (const f of next.fields ?? []) if ('file' in f) await discard(f.file)
        await save((await jobs()).filter(j => j.id !== next.id))
        report.sent.push(next.label)
        onSent?.(next, r)
      } catch (e) {
        if (e instanceof OfflineError || (e instanceof ApiError && [0, 401, 408, 429].includes(e.status))) {
          await save((await jobs()).map(j => j.id === next.id ? { ...j, attempts: j.attempts + 1, error: e.message } : j))
          report.offline = true
          break
        }
        const msg = e instanceof ApiError ? e.message + (e.fields ? ': ' + Object.entries(e.fields).map(([k, v]) => `${k} ${v}`).join(', ') : '') : String(e)
        await save((await jobs()).map(j => j.id === next.id ? { ...j, attempts: j.attempts + 1, status: 'FAILED', error: msg } : j))
        report.failed.push(next.label)
      }
    }
  } finally {
    running = false
  }
  return report
}

/** The server id an earlier job produced (for example the application created from the outbox). */
export async function resultOf(jobId: string): Promise<number | undefined> {
  return (await results())[jobId]
}
