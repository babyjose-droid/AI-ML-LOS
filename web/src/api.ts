// Thin API client. The token is kept in memory and, where allowed, in localStorage.
const KEY = 'rhythm_token'

let token: string | null = (() => {
  try { return localStorage.getItem(KEY) } catch { return null }
})()

export function setToken(t: string | null) {
  token = t
  try { if (t) localStorage.setItem(KEY, t); else localStorage.removeItem(KEY) } catch { /* storage unavailable */ }
}
export const hasToken = () => !!token

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

let onUnauthorized: () => void = () => {}
export const setUnauthorizedHandler = (f: () => void) => { onUnauthorized = f }

// a random id kept on this browser or phone: applications keyed on the same device are linked by the network check
function deviceId(): string | null {
  try {
    let d = localStorage.getItem('rhythm_device')
    if (!d) { d = (crypto.randomUUID?.() ?? Math.random().toString(36).slice(2) + Date.now().toString(36)).replace(/[^A-Za-z0-9-]/g, ''); localStorage.setItem('rhythm_device', d) }
    return d
  } catch { return null }
}

async function request<T>(method: string, url: string, body?: unknown, isForm = false): Promise<T> {
  const headers: Record<string, string> = {}
  const dev = method === 'POST' ? deviceId() : null
  if (dev) headers['X-Device-Id'] = dev
  if (token) headers.Authorization = 'Bearer ' + token
  if (body !== undefined && !isForm) headers['Content-Type'] = 'application/json'
  const res = await fetch(url, {
    method,
    headers,
    body: body === undefined ? undefined : isForm ? (body as FormData) : JSON.stringify(body),
  })
  if (res.status === 401 && !url.endsWith('/auth/login')) {
    setToken(null)
    onUnauthorized()
  }
  if (res.status === 204) return undefined as T
  const text = await res.text()
  let data: any
  try { data = text ? JSON.parse(text) : undefined } catch { data = undefined }
  if (!res.ok) {
    const fallback = res.status === 413 ? 'The file is larger than 10 MB' : res.status >= 500 ? 'The server had a problem. Please try again.' : res.statusText
    throw new ApiError(res.status, data?.code ?? 'ERROR', data?.message ?? fallback, data?.fields)
  }
  return data as T
}

export const api = {
  get: <T>(url: string) => request<T>('GET', url),
  post: <T>(url: string, body?: unknown) => request<T>('POST', url, body ?? {}),
  put: <T>(url: string, body: unknown) => request<T>('PUT', url, body),
  patch: <T>(url: string) => request<T>('PATCH', url, {}),
  del: <T>(url: string) => request<T>('DELETE', url),
  upload: <T>(url: string, form: FormData) => request<T>('POST', url, form, true),
}
