// API client for the Rhythm backend. On the web build the app is served next to the API, so paths are relative; on a
// phone the server address (for example http://192.168.1.20:3000) is entered on the sign-in screen and kept.
import AsyncStorage from '@react-native-async-storage/async-storage'
import { Platform } from 'react-native'
import { part, type LocalFile } from './files'
import { secureDel, secureGet, secureSet } from './secure'

export class ApiError extends Error {
  status: number
  code: string
  fields?: Record<string, string>
  constructor(status: number, code: string, message: string, fields?: Record<string, string>) {
    super(message)
    this.status = status
    this.code = code
    this.fields = fields
  }
}

/** A request that never reached the server (no signal, server down): safe to retry later. */
export class OfflineError extends Error {}

let server = ''
let token: string | null = null
let tenant: string | null = null
let device: string | null = null
let onUnauthorized: () => void = () => {}

export const isWeb = Platform.OS === 'web'
export const getServer = () => server
export const getToken = () => token
export const getTenant = () => tenant
export const setUnauthorizedHandler = (f: () => void) => { onUnauthorized = f }

export async function init() {
  server = (await AsyncStorage.getItem('server')) ?? ''
  tenant = await AsyncStorage.getItem('tenant')
  token = await secureGet('token')
  device = await AsyncStorage.getItem('device')
  if (!device) {
    device = 'm-' + Date.now().toString(36) + Math.random().toString(36).slice(2, 10)
    await AsyncStorage.setItem('device', device)
  }
}

export async function setServer(s: string) {
  server = s.trim().replace(/\/+$/, '')
  if (server && !/^https?:\/\//.test(server)) server = 'http://' + server
  await AsyncStorage.setItem('server', server)
}
export async function setTenant(t: string) {
  tenant = t.trim().toLowerCase() || null
  if (tenant) await AsyncStorage.setItem('tenant', tenant); else await AsyncStorage.removeItem('tenant')
}
export async function setToken(t: string | null) {
  token = t
  if (t) await secureSet('token', t); else await secureDel('token')
}

export const url = (path: string) => (isWeb ? '' : server) + path

export type Field = { name: string; value: string } | { name: string; file: LocalFile }

async function request<T>(method: string, path: string, body?: unknown, fields?: Field[]): Promise<T> {
  if (!isWeb && !server) throw new ApiError(0, 'NO_SERVER', 'Enter the server address on the sign-in screen')
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (token) headers.Authorization = 'Bearer ' + token
  if (tenant) headers['X-Tenant'] = tenant
  if (method === 'POST' && device) headers['X-Device-Id'] = device
  let payload: BodyInit | undefined
  if (fields) {
    const form = new FormData()
    for (const f of fields) {
      if ('file' in f) form.append(f.name, (await part(f.file)) as unknown as Blob, f.file.name)
      else form.append(f.name, f.value)
    }
    payload = form
  } else if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
    payload = JSON.stringify(body)
  }
  let res: Response
  try {
    const ctl = new AbortController()
    const timer = setTimeout(() => ctl.abort(), fields ? 120_000 : 45_000)
    try { res = await fetch(url(path), { method, headers, body: payload, signal: ctl.signal }) } finally { clearTimeout(timer) }
  } catch {
    throw new OfflineError('No connection to the server')
  }
  if (res.status === 401 && !path.endsWith('/auth/login')) {
    await setToken(null)
    onUnauthorized()
  }
  if (res.status === 204) return undefined as T
  const text = await res.text()
  let data: any
  try { data = text ? JSON.parse(text) : undefined } catch { data = undefined }
  if (!res.ok) {
    if (res.status === 502 || res.status === 503 || res.status === 504) throw new OfflineError('The server is not reachable right now')
    const fallback = res.status === 413 ? 'The file is larger than 10 MB' : res.status >= 500 ? 'The server had a problem. Please try again.' : `Request failed (${res.status})`
    throw new ApiError(res.status, data?.code ?? 'ERROR', data?.message ?? fallback, data?.fields)
  }
  return data as T
}

export const api = {
  get: <T>(p: string) => request<T>('GET', p),
  post: <T>(p: string, body?: unknown) => request<T>('POST', p, body ?? {}),
  put: <T>(p: string, body: unknown) => request<T>('PUT', p, body),
  upload: <T>(p: string, fields: Field[]) => request<T>('POST', p, undefined, fields),
  send: <T>(method: string, p: string, body?: unknown, fields?: Field[]) => request<T>(method, p, body, fields),
}

/** GET that falls back to the last copy kept on the phone when there is no signal. */
export async function getCached<T>(p: string): Promise<{ data: T; savedAt?: number }> {
  try {
    const data = await api.get<T>(p)
    AsyncStorage.setItem('cache:' + p, JSON.stringify({ at: Date.now(), data })).catch(() => undefined)
    return { data }
  } catch (e) {
    if (!(e instanceof OfflineError)) throw e
    const c = await AsyncStorage.getItem('cache:' + p)
    if (!c) throw e
    const { at, data } = JSON.parse(c)
    return { data, savedAt: at }
  }
}

export async function clearCache() {
  const keys = (await AsyncStorage.getAllKeys()).filter(k => k.startsWith('cache:'))
  await AsyncStorage.multiRemove(keys)
}

export const message = (e: unknown) => e instanceof Error ? e.message : String(e)
