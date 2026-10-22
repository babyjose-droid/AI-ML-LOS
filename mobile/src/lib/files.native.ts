// Phone build: a picked or photographed file is copied into the app's own folder (outbox) so it is kept until it is sent,
// even if the camera roll or cache is cleared, and removed after it is uploaded.
import { Directory, File, Paths } from 'expo-file-system'

export interface LocalFile { uri: string; name: string; type: string; size?: number }

const outbox = () => {
  const d = new Directory(Paths.document, 'outbox')
  if (!d.exists) d.create({ intermediates: true, idempotent: true })
  return d
}

export async function keep(uri: string, name: string, type: string, size?: number): Promise<LocalFile> {
  const safe = name.replace(/[^A-Za-z0-9._-]/g, '_')
  const dest = new File(outbox(), `${Date.now()}-${Math.random().toString(36).slice(2, 7)}-${safe}`)
  await new File(uri).copy(dest)
  return { uri: dest.uri, name: safe, type: type || 'application/octet-stream', size }
}

// React Native's FormData sends a { uri, name, type } part as a file read from disk
export async function part(f: LocalFile): Promise<{ uri: string; name: string; type: string }> {
  return { uri: f.uri, name: f.name, type: f.type }
}

export async function discard(f: LocalFile): Promise<void> {
  try { const x = new File(f.uri); if (x.exists) x.delete() } catch { /* already gone */ }
}
