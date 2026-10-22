// Web build: a picked or photographed file is kept as a data URL (so it survives a reload while queued) and sent as a Blob.
export interface LocalFile { uri: string; name: string; type: string; size?: number }

export async function keep(uri: string, name: string, type: string, size?: number): Promise<LocalFile> {
  if (uri.startsWith('data:')) return { uri, name, type, size }
  const blob = await (await fetch(uri)).blob()
  const data = await new Promise<string>((res, rej) => { const r = new FileReader(); r.onload = () => res(String(r.result)); r.onerror = () => rej(r.error); r.readAsDataURL(blob) })
  return { uri: data, name, type: type || blob.type || 'application/octet-stream', size: size ?? blob.size }
}

export async function part(f: LocalFile): Promise<Blob> {
  return (await fetch(f.uri)).blob()
}

export async function discard(_f: LocalFile): Promise<void> { /* nothing on disk */ }
