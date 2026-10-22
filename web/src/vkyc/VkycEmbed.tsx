import { useEffect, useState } from 'react'
import { api, hasToken } from '../api'
import { ErrorBox } from '../components/ui'
import type { AppView } from '../types'
import VideoKycTab from './VideoKycTab'

/**
 * The official's video KYC screen on its own, for the mobile app (shown inside the app, which signs it in with the
 * officer's session). Same call, checks and decision as the Video KYC tab of Application 360.
 */
export default function VkycEmbed() {
  const id = window.location.pathname.split('/').filter(Boolean)[2] ?? ''
  const [v, setV] = useState<AppView>()
  const [error, setError] = useState<unknown>()
  const [rev, setRev] = useState(0)
  useEffect(() => { api.get<AppView>('/api/applications/' + id).then(setV).catch(setError) }, [id, rev])
  if (!hasToken()) return <div className="panel empty">Sign in from the mobile app first.</div>
  return (
    <div className="vkyc-embed" data-testid="vkyc-embed">
      <ErrorBox error={error} />
      {v && <>
        <p className="small muted" style={{ margin: '0 0 8px' }}>{v.application.applicantName} · {v.application.appNo}</p>
        <VideoKycTab id={id} rev={rev} canRun={v.actions.includes('videoKyc')} onChange={() => setRev(x => x + 1)} />
      </>}
    </div>
  )
}
