import { useEffect, useRef, useState } from 'react'
import { Poller, peer, type Signal } from './rtc'
import { getTenant } from '../api'

// The customer's side of video KYC. Opened from the one-time link; no login. The link token is the credential.

type View = { status: string; firstName: string; lender: string; appNo: string; expiresAt: string; iceServers: RTCIceServer[] }

async function call<T>(method: string, url: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = body ? { 'Content-Type': 'application/json' } : {}
  const org = getTenant()
  if (org) headers['X-Tenant'] = org
  const r = await fetch(url, { method, headers, body: body ? JSON.stringify(body) : undefined })
  const text = await r.text()
  const data = text ? JSON.parse(text) : undefined
  if (!r.ok) throw new Error(data?.message ?? 'Something went wrong. Please try again.')
  return data as T
}

const CLOSED: Record<string, string> = {
  EXPIRED: 'This link has expired. Please ask your loan officer for a new one.',
  CANCELLED: 'This link was replaced by a newer one. Please use the latest link you received.',
  COMPLETED: 'Your video KYC call has ended. Thank you.',
  APPROVED: 'Your video KYC call has ended. Thank you.',
  REJECTED: 'Your video KYC call has ended. Thank you.',
}

export default function VideoKycCustomer() {
  const token = window.location.pathname.split('/')[2] ?? ''
  const base = `/api/vkyc/c/${encodeURIComponent(token)}`
  const [view, setView] = useState<View | null>(null)
  const [error, setError] = useState('')
  const [consent, setConsent] = useState(false)
  const [stage, setStage] = useState<'intro' | 'starting' | 'waiting' | 'call' | 'ended'>('intro')
  const [instruction, setInstruction] = useState('')
  const self = useRef<HTMLVideoElement>(null)
  const officer = useRef<HTMLVideoElement>(null)
  const media = useRef<MediaStream | null>(null)
  const poller = useRef<Poller | null>(null)

  useEffect(() => {
    call<View>('GET', base).then(v => { setView(v); if (CLOSED[v.status]) setStage('ended') }).catch(e => setError(e.message))
    return () => { poller.current?.stop(); media.current?.getTracks().forEach(t => t.stop()) }
  }, [base])

  async function begin() {
    setError('')
    setStage('starting')
    try {
      const pos = await new Promise<GeolocationPosition>((res, rej) =>
        navigator.geolocation.getCurrentPosition(res, () => rej(new Error('Please allow location access. It is required for video KYC.')), { enableHighAccuracy: true, timeout: 15000 }))
      const stream = await navigator.mediaDevices.getUserMedia({ video: { width: 640, height: 480, facingMode: 'user' }, audio: true })
        .catch(() => { throw new Error('Please allow camera and microphone access to continue.') })
      media.current = stream
      const v = await call<View>('POST', base + '/join', { consent: true, lat: pos.coords.latitude, lng: pos.coords.longitude, accuracy: pos.coords.accuracy })
      setView(v)
      setStage('waiting')
      if (self.current) self.current.srcObject = stream
      listen(v.iceServers ?? [], stream)
    } catch (e) {
      setError((e as Error).message)
      setStage('intro')
    }
  }

  function listen(iceServers: RTCIceServer[], stream: MediaStream) {
    const send = (kind: string, payload: string) => call('POST', base + '/signal', { kind, payload })
    let p: ReturnType<typeof peer> | null = null
    const onSignal = async (s: Signal) => {
      if (s.kind === 'offer') {
        p?.pc.close()
        p = peer(iceServers, send, remote => { if (officer.current) officer.current.srcObject = remote })
        stream.getTracks().forEach(t => p!.pc.addTrack(t, stream))
        await p.pc.setRemoteDescription(JSON.parse(s.payload))
        await p.flush()
        const answer = await p.pc.createAnswer()
        await p.pc.setLocalDescription(answer)
        await send('answer', JSON.stringify(answer))
        setStage('call')
      } else if (s.kind === 'ice') {
        await p?.addIce(s.payload)
      } else if (s.kind === 'instruction') {
        setInstruction(JSON.parse(s.payload).text ?? '')
      } else if (s.kind === 'hangup') {
        p?.pc.close()
        poller.current?.stop()
        stream.getTracks().forEach(t => t.stop())
        setStage('ended')
      }
    }
    poller.current = new Poller(async after => {
      const r = await call<{ status: string; signals: Signal[] }>('GET', `${base}/signal?after=${after}`)
      if (CLOSED[r.status] && r.signals.length === 0) { setStage('ended'); poller.current?.stop() }
      return r.signals
    }, onSignal)
    poller.current.start()
    send('ready', '{}').catch(() => {})
  }

  useEffect(() => { if (self.current && media.current) self.current.srcObject = media.current }, [stage])

  return (
    <div className="vkyc-customer">
      <div className="panel" style={{ maxWidth: 720, margin: '24px auto' }}>
        <div className="brand" style={{ marginBottom: 12 }}><i /> <div>{view?.lender ?? 'Rhythm'}<small>Video KYC</small></div></div>
        {error && <div className="alert bad" role="alert">{error}</div>}
        {!view && !error && <p>Loading…</p>}
        {view && stage === 'ended' && <h2 data-vkyc-stage="ended">{CLOSED[view.status] ?? 'Your video KYC call has ended. Thank you.'}</h2>}
        {view && stage === 'intro' && (
          <div data-vkyc-stage="intro">
            <h2 style={{ marginTop: 0 }}>Hello {view.firstName}, let us complete your video KYC</h2>
            <p>Application {view.appNo}. The call takes about 3 minutes with an official of {view.lender}. Please keep ready:</p>
            <ul>
              <li>Your <b>original PAN card</b></li>
              <li>A quiet, well-lit place, and stay in India during the call</li>
            </ul>
            <label className="small" style={{ display: 'flex', gap: 8, alignItems: 'flex-start' }}>
              <input type="checkbox" checked={consent} onChange={e => setConsent(e.target.checked)} aria-label="I consent" />
              <span>I consent to a video-based customer identification process (V-CIP). I agree that the call is recorded, that my location,
                face and PAN card are captured and compared with my KYC documents, and that these are stored by {view.lender} as required by RBI.</span>
            </label>
            <div style={{ marginTop: 14 }}>
              <button className="btn primary" disabled={!consent} onClick={begin}>Allow location and camera, and join</button>
            </div>
          </div>
        )}
        {view && stage === 'starting' && <p>Asking for location, camera and microphone…</p>}
        {(stage === 'waiting' || stage === 'call') && (
          <div data-vkyc-stage={stage}>
            {stage === 'waiting' && <div className="alert warn">Please wait. An official will join shortly. Keep this page open.</div>}
            {instruction && <div className="vkyc-instruction" data-vkyc-instruction>{instruction}</div>}
            <div className="vkyc-videos">
              <video ref={officer} autoPlay playsInline className="vkyc-remote" aria-label="Official" />
              <video ref={self} autoPlay playsInline muted className="vkyc-self" aria-label="You" />
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
