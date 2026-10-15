import { useEffect, useRef, useState } from 'react'
import { api } from '../api'
import { ErrorBox, Pill, Table, useLoad } from '../components/ui'
import { pct, when } from '../format'
import { Poller, grab, mixForRecording, peer, recorder, sleep, type Signal } from './rtc'

// The official's side of video KYC (V-CIP): create the customer link, run the live call with challenges,
// let the AI check the captured frames, and record the decision.

export interface VkycSession {
  id: number; applicationId: number; status: string; createdBy: string; createdAt: string; expiresAt: string; joinedAt?: string
  customerLat?: number; customerLng?: number; customerAccuracyM?: number; inIndia?: boolean; ipCountry?: string; slotAt?: string; bookedBy?: string; customerAgent?: string; officer?: string
  callStartedAt?: string; callEndedAt?: string; recordingSha256?: string; recordingBytes?: number; hasRecording?: boolean
  aiStatus?: string; decision?: string; decisionNote?: string; codeHeard?: boolean; aiOverride?: boolean; decidedBy?: string; decidedAt?: string
  partyId?: number; personName?: string; questionsJson?: string
}
export interface VkycPolicy {
  parties: 'NONE' | 'ON_KYC_REVIEW' | 'ALL'; minFaceMatch: number; requireLiveness: boolean; requirePanOnCamera: boolean; linkHours: number
  callFrom: string; callTo: string; maxAttempts: number; officerRoles: string[]; questions: string[]; languages: string[]
}
interface PartyLite { party: { id: number; name: string; role: string; status: string; consentAt?: string; kycState: string }; vkyc?: string | null }
interface Detail { session: VkycSession; frames: { index: string; step: string }[]; analysis: string; challengeCode: string; questions: string[] }
interface Analysis {
  status: string; reasons: string[]
  liveness?: { passed: boolean; checks: { check: string; pass: boolean; detail: string }[] }
  faceMatch?: { matched: boolean; score: number; threshold: number; against?: string; results?: { document: string; faceFound: boolean; score: number | null }[]; reason?: string }
  panCheck?: { found?: string; expected?: string; matches: boolean } | null
}

const STEP_LABEL: Record<string, string> = { front: 'Face', left: 'Turn left', right: 'Turn right', code: 'Read code', pan: 'PAN card' }

async function authBlob(url: string): Promise<string> {
  let token: string | null = null
  try { token = localStorage.getItem('rhythm_token') } catch { /* ignore */ }
  const r = await fetch(url, { headers: token ? { Authorization: 'Bearer ' + token } : {} })
  return URL.createObjectURL(await r.blob())
}

export default function VideoKycTab({ id, rev, canRun, onChange }: { id: string; rev: number; canRun: boolean; onChange: () => void }) {
  const [tick, setTick] = useState(0)
  const list = useLoad(() => api.get<VkycSession[]>(`/api/applications/${id}/vkyc`), [id, rev, tick])
  const policy = useLoad(() => api.get<VkycPolicy>(`/api/applications/${id}/vkyc/policy`), [id])
  const people = useLoad(() => api.get<{ parties: PartyLite[] }>(`/api/applications/${id}/parties`).then(x => x.parties).catch(() => [] as PartyLite[]), [id, rev, tick])
  const [who, setWho] = useState('')
  const [link, setLink] = useState('')
  const [error, setError] = useState<unknown>()
  const [open, setOpen] = useState<number | null>(null)
  const sessions = list.data ?? []
  const active = sessions.find(s => ['WAITING', 'JOINED', 'IN_CALL', 'COMPLETED'].includes(s.status))

  // refresh while waiting for the customer
  useEffect(() => {
    if (!active || !['WAITING', 'JOINED'].includes(active.status)) return
    const t = window.setInterval(() => setTick(x => x + 1), 3000)
    return () => clearInterval(t)
  }, [active?.id, active?.status])

  async function create() {
    try {
      const r = await api.post<{ session: VkycSession; link: string }>(`/api/applications/${id}/vkyc`, who ? { partyId: Number(who) } : {})
      setLink(window.location.origin + r.link)
      setError(undefined)
      setTick(x => x + 1)
    } catch (e) { setError(e) }
  }

  return (
    <>
      <ErrorBox error={error} />
      {canRun && (
        <div className="panel">
          <h3 style={{ marginTop: 0 }}>Video KYC (V-CIP)</h3>
          <p className="small muted">Create a one-time link for the customer. They consent, share their location and join from their phone or computer;
            you run the live call. The AI checks liveness, face match with the ID photo and the PAN card shown on camera. You decide.</p>
          {policy.data && <PolicySummary p={policy.data} />}
          <div className="row" style={{ marginTop: 8 }}>
            {policy.data && policy.data.parties !== 'NONE' && (people.data ?? []).some(x => x.party.status !== 'REMOVED') && (
              <label className="fld" style={{ minWidth: 260 }}>Person on the call
                <select name="vkycPerson" value={who} onChange={e => setWho(e.target.value)}>
                  <option value="">Applicant</option>
                  {(people.data ?? []).filter(x => x.party.status !== 'REMOVED').map(x => <option key={x.party.id} value={x.party.id} disabled={!x.party.consentAt}>
                    {x.party.name} ({x.party.role === 'GUARANTOR' ? 'guarantor' : 'co-applicant'}){!x.party.consentAt ? ' – consent first' : x.vkyc ? ' – ' + x.vkyc.toLowerCase() : ''}</option>)}
                </select></label>
            )}
            <button className="btn primary" data-vkyc="create" onClick={create} style={{ alignSelf: 'flex-end' }}>{active ? 'Create a new link (replaces the current one for that person)' : 'Create video KYC link'}</button>
          </div>
          {link && (
            <div className="alert ok" style={{ marginTop: 12 }}>
              <div className="small">Send this link to the customer (SMS and WhatsApp delivery come with the integrations phase). It works once and expires in {policy.data?.linkHours ?? 24} hours{policy.data && policy.data.callFrom !== '00:00' ? `; calls are taken ${policy.data.callFrom}–${policy.data.callTo} India time` : ''}.</div>
              <div className="row" style={{ marginTop: 6 }}>
                <input className="mono" readOnly value={link} data-vkyc-link style={{ flex: 1 }} onFocus={e => e.target.select()} />
                <button className="btn sm" onClick={() => navigator.clipboard?.writeText(link)}>Copy</button>
              </div>
            </div>
          )}
        </div>
      )}
      {active && active.status !== 'COMPLETED' && (
        <CallPanel key={active.id} s={active} canRun={canRun} onDone={() => { setOpen(active.id); setTick(x => x + 1); onChange() }} />
      )}
      {sessions.length === 0 && <div className="panel empty">No video KYC sessions yet.</div>}
      {sessions.length > 0 && (
        <div className="panel">
          <h3 style={{ marginTop: 0 }}>Sessions</h3>
          <Table head={['Created', 'Person', 'Status', 'Customer location', 'Official', 'AI', 'Decision']} onRow={i => setOpen(sessions[i].id)} rows={sessions.map(s => [
            when(s.createdAt), <span>{s.personName ?? 'Applicant'}{s.partyId ? <div className="small muted">co-applicant / guarantor</div> : null}</span>, <Pill s={s.status} />,
            s.customerLat != null ? <span className="small">{s.customerLat}, {s.customerLng} {s.inIndia ? '· India' : <b className="bad-text">· outside India</b>}{s.ipCountry && !['IN', 'PRIVATE', 'UNKNOWN'].includes(s.ipCountry) && <b className="bad-text"> · IP in {s.ipCountry}</b>}</span> : s.slotAt ? <span className="small">Booked for {new Date(s.slotAt).toLocaleString('en-IN', { weekday: 'short', day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' })}</span> : '—',
            s.officer ?? '—', s.aiStatus ? <Pill s={s.aiStatus} /> : '—', s.decision ? <Pill s={s.decision} /> : '—',
          ])} />
        </div>
      )}
      {open && <SessionDetail key={open + ':' + tick} sid={open} canRun={canRun} onChange={() => { setTick(x => x + 1); onChange() }} />}
    </>
  )
}

function CallPanel({ s, canRun, onDone }: { s: VkycSession; canRun: boolean; onDone: () => void }) {
  const remote = useRef<HTMLVideoElement>(null)
  const local = useRef<HTMLVideoElement>(null)
  const state = useRef<{ pc?: RTCPeerConnection; poller?: Poller; media?: MediaStream; rec?: MediaRecorder | null; chunks: Blob[] }>({ chunks: [] })
  const [code, setCode] = useState('')
  const [connected, setConnected] = useState(false)
  const [busy, setBusy] = useState('')
  const [done, setDone] = useState<Record<string, number>>({})
  const [error, setError] = useState<unknown>()
  const base = `/api/vkyc/${s.id}`

  useEffect(() => () => {
    const st = state.current
    st.poller?.stop(); st.pc?.close(); st.media?.getTracks().forEach(t => t.stop())
  }, [])

  async function startCall() {
    setError(undefined)
    try {
      const r = await api.post<{ challengeCode: string; iceServers: RTCIceServer[] }>(base + '/start')
      setCode(r.challengeCode)
      const cfg = r.iceServers ?? []
      const media = await navigator.mediaDevices.getUserMedia({ video: { width: 640, height: 480 }, audio: true })
      state.current.media = media
      if (local.current) local.current.srcObject = media
      const send = (kind: string, payload: string) => api.post(base + '/signal', { kind, payload })
      const p = peer(cfg, send, stream => {
        if (remote.current) remote.current.srcObject = stream
        setConnected(true)
        if (state.current.rec !== undefined || stream.getVideoTracks().length === 0) return
        state.current.rec = null
        // wait a moment so the customer's audio track has joined the stream before recording starts
        window.setTimeout(() => {
          const rec = recorder(mixForRecording(stream, media))
          if (rec) {
            rec.ondataavailable = e => { if (e.data.size) state.current.chunks.push(e.data) }
            rec.start(1000)
            state.current.rec = rec
          }
        }, 600)
      })
      state.current.pc = p.pc
      media.getTracks().forEach(t => p.pc.addTrack(t, media))
      const offer = await p.pc.createOffer()
      await p.pc.setLocalDescription(offer)
      await send('offer', JSON.stringify(offer))
      const poller = new Poller(after => api.get<Signal[]>(`${base}/signal?after=${after}`), async sig => {
        if (sig.kind === 'answer') { await p.pc.setRemoteDescription(JSON.parse(sig.payload)); await p.flush() }
        else if (sig.kind === 'ice') await p.addIce(sig.payload)
      })
      state.current.poller = poller
      poller.start()
    } catch (e) { setError(e) }
  }

  async function challenge(step: string, text: string, waitMs: number, shots: number, gapMs: number) {
    setBusy(step)
    setError(undefined)
    try {
      await api.post(base + '/signal', { kind: 'instruction', payload: JSON.stringify({ text }) })
      await sleep(waitMs)
      const fd = new FormData()
      let n = 0
      for (let i = 0; i < shots; i++) {
        const b = remote.current ? await grab(remote.current) : null
        if (b) fd.append('frames', b, `${step}-${i + 1}.jpg`), n++
        await sleep(gapMs)
      }
      if (!n) throw new Error('No video from the customer yet')
      await api.upload(base + '/frames', fd)
      setDone(d => ({ ...d, [step]: (d[step] ?? 0) + n }))
    } catch (e) { setError(e) } finally { setBusy('') }
  }

  async function endCall() {
    setBusy('end')
    try {
      const st = state.current
      if (st.rec && st.rec.state !== 'inactive') {
        await new Promise<void>(res => { st.rec!.onstop = () => res(); st.rec!.stop() })
      }
      if (st.chunks.length) {
        const fd = new FormData()
        fd.append('file', new Blob(st.chunks, { type: 'video/webm' }), 'recording.webm')
        await api.upload(base + '/recording', fd)
      }
      await api.post(base + '/finish')
      st.poller?.stop(); st.pc?.close(); st.media?.getTracks().forEach(t => t.stop())
      onDone()
    } catch (e) { setError(e) } finally { setBusy('') }
  }

  const steps: [string, string, string, number, number, number][] = [
    ['front', 'Face the camera', 'Please look straight at the camera and keep still', 1500, 3, 400],
    ['left', 'Turn head left', 'Please turn your head slowly to your LEFT, then back', 1200, 5, 350],
    ['right', 'Turn head right', 'Please turn your head slowly to your RIGHT, then back', 1200, 5, 350],
    ['code', 'Read the code', `Please read this code aloud: ${code}`, 1500, 2, 500],
    ['pan', 'Show PAN card', 'Hold your original PAN card close to the camera, front side, until it is sharp', 3000, 3, 700],
  ]

  return (
    <div className="panel" data-vkyc-call={s.status}>
      <div className="head" style={{ marginBottom: 10 }}>
        <h3 style={{ margin: 0 }}>Live call <Pill s={s.status} /></h3>
        {s.customerLat != null && <span className="small">Customer at {s.customerLat}, {s.customerLng} (±{s.customerAccuracyM ?? '?'} m) {s.inIndia ? '· India' : '· OUTSIDE INDIA'}{s.ipCountry ? ` · IP country ${s.ipCountry}` : ''}</span>}
      </div>
      <ErrorBox error={error} />
      {s.status === 'WAITING' && <p className="muted">Waiting for the customer to open the link and give consent…</p>}
      {canRun && s.status !== 'WAITING' && !code && <button className="btn primary" data-vkyc="start" onClick={startCall}>Start call</button>}
      {code && (
        <>
          <div className="vkyc-videos">
            <video ref={remote} autoPlay playsInline className="vkyc-remote" aria-label="Customer video" />
            <video ref={local} autoPlay playsInline muted className="vkyc-self" aria-label="Your video" />
          </div>
          <p className="small">{connected ? 'Connected. Recording.' : 'Connecting…'} Random code for this call: <b className="mono" data-vkyc-code>{code}</b></p>
          <div className="row">
            {steps.map(([k, label, text, w, n, g]) => (
              <button key={k} className={'btn sm' + (done[k] ? ' ok' : '')} data-vkyc-step={k} disabled={!connected || !!busy}
                onClick={() => challenge(k, text, w, n, g)}>{done[k] ? '✓ ' : ''}{label}{busy === k ? '…' : ''}</button>
            ))}
            <button className="btn danger sm" data-vkyc="end" disabled={!!busy} onClick={endCall}>End call and check</button>
          </div>
          <p className="small muted">Ask each step in turn. Frames are captured from the customer's video for the AI checks. Confirm by ear that the customer reads the code correctly.</p>
        </>
      )}
    </div>
  )
}

function SessionDetail({ sid, canRun, onChange }: { sid: number; canRun: boolean; onChange: () => void }) {
  const d = useLoad(() => api.get<Detail>(`/api/vkyc/${sid}`), [sid])
  const [thumbs, setThumbs] = useState<Record<string, string>>({})
  const [video, setVideo] = useState('')
  const [codeHeard, setCodeHeard] = useState(false)
  const [answers, setAnswers] = useState<boolean[]>([])
  const [note, setNote] = useState('')
  const [error, setError] = useState<unknown>()

  useEffect(() => {
    const frames = d.data?.frames ?? []
    let cancelled = false
    Promise.all(frames.map(async f => [f.index, await authBlob(`/api/vkyc/${sid}/frames/${f.index}`)] as const))
      .then(list => { if (!cancelled) setThumbs(Object.fromEntries(list)) })
    return () => { cancelled = true }
  }, [d.data, sid])

  if (!d.data) return <ErrorBox error={d.error} />
  const s = d.data.session
  let a: Analysis | null = null
  try { a = d.data.analysis ? JSON.parse(d.data.analysis) : null } catch { a = null }

  async function decide(approve: boolean) {
    try {
      await api.post(`/api/vkyc/${sid}/decision`, { approve, codeHeard, note, answers: (d.data?.questions ?? []).map((_, i) => !!answers[i]) })
      onChange()
    } catch (e) { setError(e) }
  }

  return (
    <div className="panel" data-vkyc-detail={s.status}>
      <h3 style={{ marginTop: 0 }}>Session {s.id} · {s.personName ?? 'Applicant'} <Pill s={s.status} /> {s.aiStatus && <>· AI <Pill s={s.aiStatus} /></>}</h3>
      <div className="grid2">
        <Table head={['', '']} rows={[
          ['Link created', `${when(s.createdAt)} by ${s.createdBy}`],
          ['Customer joined', s.joinedAt ? when(s.joinedAt) : '—'],
          ['Location', s.customerLat != null ? `${s.customerLat}, ${s.customerLng} (±${s.customerAccuracyM ?? '?'} m) · ${s.inIndia ? 'India' : 'OUTSIDE INDIA'}` : '—'],
          ['IP country', s.ipCountry ?? '—'],
          ['Device', <span className="small">{s.customerAgent ?? '—'}</span>],
          ['Official', s.officer ?? '—'],
          ['Call', s.callStartedAt ? `${when(s.callStartedAt)} – ${s.callEndedAt ? when(s.callEndedAt) : 'in progress'}` : '—'],
          ['Recording', s.hasRecording ? <a href="#" onClick={async e => { e.preventDefault(); setVideo(await authBlob(`/api/vkyc/${sid}/recording`)) }}>Play ({Math.round((s.recordingBytes ?? 0) / 1024)} KB)</a> : 'none'],
          ['Decision', s.decision ? `${s.decision} by ${s.decidedBy} ${when(s.decidedAt)}${s.aiOverride ? ' · AI overridden' : ''}: ${s.decisionNote}` : '—'],
          ['Questions', s.questionsJson ? <span className="small">{(JSON.parse(s.questionsJson) as { question: string; answered: boolean }[]).map(q => `${q.answered ? '✓' : '✗'} ${q.question}`).join(' · ')}</span> : '—'],
        ]} />
        <div>
          {a ? (
            <>
              {a.faceMatch && (
                <div className="kpi" data-vkyc-face style={{ marginBottom: 10 }}>
                  <span>Face match with ID photo{a.faceMatch.against ? ` (${a.faceMatch.against})` : ''}</span>
                  <b>{a.faceMatch.score ? pct(a.faceMatch.score, 0) : '—'}</b> <Pill s={a.faceMatch.matched ? 'PASS' : 'FAIL'} />
                  {a.faceMatch.reason && <span>{a.faceMatch.reason}</span>}
                </div>
              )}
              {a.liveness && (
                <Table head={['Liveness check', 'Result', '']} rows={a.liveness.checks.map(c => [c.check, <Pill s={c.pass ? 'PASS' : 'FAIL'} />, <span className="small">{c.detail}</span>])} />
              )}
              {a.panCheck && <p className="small">PAN on camera: <b className="mono">{a.panCheck.found ?? 'not readable'}</b> (application {a.panCheck.expected}) <Pill s={a.panCheck.matches ? 'PASS' : 'FAIL'} /></p>}
              {a.reasons?.length > 0 && <div className="alert warn small" data-vkyc-reasons>{a.reasons.join('; ')}</div>}
            </>
          ) : <p className="muted">No AI result yet.</p>}
        </div>
      </div>
      {video && <video src={video} controls style={{ width: '100%', maxWidth: 480, marginTop: 10 }} />}
      {d.data.frames.length > 0 && (
        <div className="vkyc-frames">
          {d.data.frames.map(f => (
            <figure key={f.index}>{thumbs[f.index] ? <img src={thumbs[f.index]} alt={STEP_LABEL[f.step]} /> : <div className="ph" />}<figcaption>{STEP_LABEL[f.step] ?? f.step}</figcaption></figure>
          ))}
        </div>
      )}
      {canRun && s.status === 'COMPLETED' && (
        <div className="form" style={{ gridTemplateColumns: '1fr', marginTop: 12 }}>
          <ErrorBox error={error} />
          {d.data.questions.length > 0 && <div data-vkyc-questions>
            <b className="small">Questions this product asks on every call</b>
            {d.data.questions.map((q, i) => <label key={i} className="small" style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
              <input type="checkbox" style={{ width: 'auto' }} data-vkyc-question={i} checked={!!answers[i]} onChange={e => setAnswers(a => { const n = [...a]; n[i] = e.target.checked; return n })} /> {q}: answered satisfactorily</label>)}
          </div>}
          <label className="small" style={{ display: 'flex', gap: 8, alignItems: 'center' }}><input type="checkbox" style={{ width: 'auto' }} checked={codeHeard} onChange={e => setCodeHeard(e.target.checked)} name="codeHeard" />
            {' '}The customer read out the random code {d.data.challengeCode && <b className="mono">{d.data.challengeCode}</b>} correctly</label>
          <label className="fld">Your decision note (required{s.aiStatus !== 'PASS' ? '; at least 30 characters to approve when AI checks did not pass' : ''})
            <textarea name="vkycNote" rows={3} value={note} onChange={e => setNote(e.target.value)} />
          </label>
          <div className="row">
            <button className="btn primary" data-vkyc="approve" onClick={() => decide(true)}>Approve video KYC</button>
            <button className="btn danger" data-vkyc="reject" onClick={() => decide(false)}>Reject</button>
          </div>
        </div>
      )}
    </div>
  )
}

function PolicySummary({ p }: { p: VkycPolicy }) {
  const who = p.parties === 'ALL' ? 'the applicant and every co-applicant and guarantor' : p.parties === 'ON_KYC_REVIEW' ? 'the applicant, and co-applicants or guarantors whose KYC needs review' : 'the applicant only'
  return (
    <ul className="small" style={{ margin: '6px 0', paddingLeft: 18 }} data-vkyc-policy>
      <li>For this product: {who}.</li>
      <li>Face match at least {p.minFaceMatch}{p.requireLiveness ? ', liveness required' : ''}{p.requirePanOnCamera ? ', PAN shown on camera' : ''}; random code, India location and recording always.</li>
      <li>Link valid {p.linkHours} h{p.callFrom !== '00:00' || p.callTo !== '23:59' ? `, calls ${p.callFrom}–${p.callTo} IST` : ''}; up to {p.maxAttempts} calls per person; run by {p.officerRoles.map(r => r.toLowerCase().replace('_', ' ')).join(' or ')}.</li>
      {p.questions.length > 0 && <li>{p.questions.length} question(s) the official must ask.</li>}
    </ul>
  )
}
