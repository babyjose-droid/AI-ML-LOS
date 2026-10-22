// Web build: a page of the web console shown in a frame of the same origin (the mobile web server proxies it), signed
// in with the officer's session.
import React, { useMemo } from 'react'
import { getTenant, getToken } from './api'

export default function WebPage({ path, testID }: { path: string; testID?: string }) {
  const ready = useMemo(() => {
    try {
      const t = getToken()
      if (t) localStorage.setItem('rhythm_token', t)
      const org = getTenant()
      if (org) localStorage.setItem('rhythm_tenant', org); else localStorage.removeItem('rhythm_tenant')
      return true
    } catch { return false }
  }, [])
  if (!ready) return null
  return React.createElement('iframe', {
    src: path, 'data-testid': testID, title: 'Video KYC', allow: 'camera; microphone; autoplay; fullscreen',
    style: { flex: 1, width: '100%', height: '100%', border: 0, background: '#fff' },
  })
}
