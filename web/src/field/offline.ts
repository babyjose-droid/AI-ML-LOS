// Offline store for the field officer app (FR-FV-03): visits captured without a network wait here, encrypted with
// a non-extractable AES-GCM key kept by the browser, until they are synced. Assigned visits are cached too, so the
// app opens with no signal. Kept for up to 3 days.

const DB = 'rhythm-field'
const VERSION = 1
export const MAX_AGE_MS = 3 * 24 * 3600 * 1000

function open(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const r = indexedDB.open(DB, VERSION)
    r.onupgradeneeded = () => {
      const db = r.result
      if (!db.objectStoreNames.contains('keys')) db.createObjectStore('keys')
      if (!db.objectStoreNames.contains('queue')) db.createObjectStore('queue', { keyPath: 'clientId' })
      if (!db.objectStoreNames.contains('cache')) db.createObjectStore('cache')
    }
    r.onsuccess = () => resolve(r.result)
    r.onerror = () => reject(r.error)
  })
}

function tx<T>(store: string, mode: IDBTransactionMode, fn: (s: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  return open().then(db => new Promise<T>((resolve, reject) => {
    const t = db.transaction(store, mode)
    const req = fn(t.objectStore(store))
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
  }))
}

async function key(): Promise<CryptoKey> {
  const k = await tx<CryptoKey | undefined>('keys', 'readonly', s => s.get('aes') as IDBRequest<CryptoKey | undefined>)
  if (k) return k
  const fresh = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt'])
  await tx('keys', 'readwrite', s => s.put(fresh, 'aes'))
  return fresh
}

async function seal(data: ArrayBuffer): Promise<{ iv: Uint8Array; data: ArrayBuffer }> {
  const iv = crypto.getRandomValues(new Uint8Array(12))
  return { iv, data: await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, await key(), data) }
}

async function unseal(x: { iv: Uint8Array; data: ArrayBuffer }): Promise<ArrayBuffer> {
  return crypto.subtle.decrypt({ name: 'AES-GCM', iv: x.iv }, await key(), x.data)
}

export interface QueuedPhoto { kind: string; takenAt: string; lat?: number; lng?: number; type: string; blob: Blob }
export interface QueuedVisit { clientId: string; appId: number; appNo: string; applicant: string; savedAt: number; visit: Record<string, unknown>; photos: QueuedPhoto[] }

const enc = new TextEncoder(), dec = new TextDecoder()

export async function enqueue(v: QueuedVisit): Promise<void> {
  const visit = await seal(enc.encode(JSON.stringify(v.visit)).buffer as ArrayBuffer)
  const photos: Stored['photos'] = []
  for (const p of v.photos) photos.push({ kind: p.kind, takenAt: p.takenAt, lat: p.lat, lng: p.lng, type: p.type, sealed: await seal(await p.blob.arrayBuffer()) })
  await tx('queue', 'readwrite', s => s.put({ clientId: v.clientId, appId: v.appId, appNo: v.appNo, applicant: v.applicant, savedAt: v.savedAt, visit, photos }))
}

type Stored = { clientId: string; appId: number; appNo: string; applicant: string; savedAt: number; visit: { iv: Uint8Array; data: ArrayBuffer };
  photos: { kind: string; takenAt: string; lat?: number; lng?: number; type: string; sealed: { iv: Uint8Array; data: ArrayBuffer } }[] }

export async function queued(): Promise<QueuedVisit[]> {
  const rows = await tx<Stored[]>('queue', 'readonly', s => s.getAll() as IDBRequest<Stored[]>)
  const out: QueuedVisit[] = []
  for (const r of rows) {
    if (Date.now() - r.savedAt > MAX_AGE_MS + 24 * 3600 * 1000) continue   // the server refuses visits older than 3 days anyway
    const visit = JSON.parse(dec.decode(await unseal(r.visit)))
    const photos: QueuedPhoto[] = []
    for (const p of r.photos) photos.push({ kind: p.kind, takenAt: p.takenAt, lat: p.lat, lng: p.lng, type: p.type, blob: new Blob([await unseal(p.sealed)], { type: p.type }) })
    out.push({ clientId: r.clientId, appId: r.appId, appNo: r.appNo, applicant: r.applicant, savedAt: r.savedAt, visit, photos })
  }
  return out
}

export async function count(): Promise<number> {
  return tx<number>('queue', 'readonly', s => s.count())
}

export async function remove(clientId: string): Promise<void> {
  await tx('queue', 'readwrite', s => s.delete(clientId))
}

export async function cacheSet(k: string, v: unknown): Promise<void> {
  const sealed = await seal(enc.encode(JSON.stringify(v)).buffer as ArrayBuffer)
  await tx('cache', 'readwrite', s => s.put({ at: Date.now(), sealed }, k))
}

export async function cacheGet<T>(k: string): Promise<{ at: number; value: T } | null> {
  const r = await tx<{ at: number; sealed: { iv: Uint8Array; data: ArrayBuffer } } | undefined>('cache', 'readonly', s => s.get(k) as IDBRequest<{ at: number; sealed: { iv: Uint8Array; data: ArrayBuffer } } | undefined>)
  if (!r) return null
  return { at: r.at, value: JSON.parse(dec.decode(await unseal(r.sealed))) as T }
}

/** Sends one queued visit; returns the server's answer. A visit already received (same clientId) is not stored twice. */
export async function send(v: QueuedVisit, token: string | null): Promise<Record<string, unknown>> {
  const form = new FormData()
  form.append('visit', new Blob([JSON.stringify({ ...v.visit, clientId: v.clientId, photos: v.photos.map(p => ({ kind: p.kind, takenAt: p.takenAt, lat: p.lat, lng: p.lng })) })], { type: 'application/json' }))
  v.photos.forEach((p, i) => form.append('photos', new File([p.blob], `${p.kind}-${i + 1}.${p.type === 'image/png' ? 'png' : 'jpg'}`, { type: p.type })))
  const r = await fetch(`/api/field/visits/${v.appId}`, { method: 'POST', body: form, headers: token ? { Authorization: 'Bearer ' + token } : {} })
  const body = await r.json().catch(() => ({}))
  if (!r.ok) {
    const e = new Error(body?.message ?? `Sync failed (${r.status})`) as Error & { status?: number; code?: string }
    e.status = r.status
    e.code = body?.code
    throw e
  }
  return body
}
