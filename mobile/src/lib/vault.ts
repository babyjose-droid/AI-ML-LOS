// Everything kept on the phone about customers (cached cases and lists, the outbox, drafts) is encrypted with
// XChaCha20-Poly1305. The key is made on the phone and kept in the Keychain / Keystore (secure.native.ts), so the data
// cannot be read from a copy of the app's storage. Photos and files waiting to upload stay in the app's private folder
// and are deleted once sent.
import AsyncStorage from '@react-native-async-storage/async-storage'
import { xchacha20poly1305 } from '@noble/ciphers/chacha'
import { getRandomBytes } from 'expo-crypto'
import { secureGet, secureSet } from './secure'

const PREFIX = 'v1:'
let key: Uint8Array | null = null
let loading: Promise<Uint8Array> | null = null

// UTF-8 without TextEncoder / TextDecoder, which not every JavaScript engine on phones has
const utf8ToBytes = (t: string) => { const bin = unescape(encodeURIComponent(t)); const b = new Uint8Array(bin.length); for (let i = 0; i < bin.length; i++) b[i] = bin.charCodeAt(i); return b }
const bytesToUtf8 = (b: Uint8Array) => { let s = ''; for (let i = 0; i < b.length; i += 8192) s += String.fromCharCode(...b.subarray(i, i + 8192)); return decodeURIComponent(escape(s)) }
const toB64 = (b: Uint8Array) => { let s = ''; for (let i = 0; i < b.length; i += 8192) s += String.fromCharCode(...b.subarray(i, i + 8192)); return btoa(s) }
const fromB64 = (s: string) => { const bin = atob(s); const b = new Uint8Array(bin.length); for (let i = 0; i < bin.length; i++) b[i] = bin.charCodeAt(i); return b }

async function getKey(): Promise<Uint8Array> {
  if (key) return key
  if (!loading) loading = (async () => {
    const saved = await secureGet('vault-key')
    if (saved) return fromB64(saved)
    const k = getRandomBytes(32)
    await secureSet('vault-key', toB64(k))
    return k
  })()
  key = await loading
  return key
}

export async function seal(text: string): Promise<string> {
  const nonce = getRandomBytes(24)
  const ct = xchacha20poly1305(await getKey(), nonce).encrypt(utf8ToBytes(text))
  const out = new Uint8Array(nonce.length + ct.length)
  out.set(nonce); out.set(ct, nonce.length)
  return PREFIX + toB64(out)
}

export async function open(stored: string): Promise<string | null> {
  if (!stored.startsWith(PREFIX)) return stored   // written by an earlier version: read once, encrypted on the next save
  try {
    const b = fromB64(stored.slice(PREFIX.length))
    const pt = xchacha20poly1305(await getKey(), b.subarray(0, 24)).decrypt(b.subarray(24))
    return bytesToUtf8(pt)
  } catch {
    return null   // the key changed (app reinstalled): the old data cannot be read
  }
}

/** AsyncStorage with encrypted values. */
export const vault = {
  async get(k: string): Promise<string | null> {
    const s = await AsyncStorage.getItem(k)
    return s == null ? null : open(s)
  },
  async set(k: string, v: string) { await AsyncStorage.setItem(k, await seal(v)) },
  remove: (k: string) => AsyncStorage.removeItem(k),
}
