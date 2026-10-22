// Web build: online / offline from the browser.
export function watchNet(cb: (online: boolean) => void): () => void {
  const on = () => cb(true), off = () => cb(false)
  window.addEventListener('online', on)
  window.addEventListener('offline', off)
  cb(navigator.onLine)
  return () => { window.removeEventListener('online', on); window.removeEventListener('offline', off) }
}
