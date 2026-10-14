import { useEffect, useMemo, useRef, useState } from 'react'
import { api, getToken } from '../api'
import { ErrorBox, Head } from '../components/ui'
import { inr } from '../format'
import { cacheGet, cacheSet, count, enqueue, queued, remove, send, type QueuedPhoto } from '../field/offline'

interface Item { key: string; label: string; type: string; required: boolean }
interface Assigned {
  applicationId: number; appNo: string; applicant: string; mobile: string; product: string; amount: number; business?: string; address: string
  addressLat?: number; addressLng?: number; checklist: { industry: string; items: Item[]; photos: string[] }
}

const PHOTO_LABEL: Record<string, string> = {
  front: 'Premises from the front', inside: 'Inside the premises', stock: 'Stock on the shelves', owner: 'Owner with you (selfie)',
  animals: 'Milch animals', vehicle: 'Vehicle with number plate', odometer: 'Odometer', residence: 'Residence', other: 'Other',
}

function metres(a: [number, number], b: [number, number]) {
  const R = 6371000, r = Math.PI / 180
  const dp = (b[0] - a[0]) * r, dl = (b[1] - a[1]) * r
  const h = Math.sin(dp / 2) ** 2 + Math.cos(a[0] * r) * Math.cos(b[0] * r) * Math.sin(dl / 2) ** 2
  return 2 * R * Math.asin(Math.sqrt(h))
}

function position(): Promise<GeolocationPosition | null> {
  return new Promise(res => {
    if (!navigator.geolocation) return res(null)
    navigator.geolocation.getCurrentPosition(p => res(p), () => res(null), { enableHighAccuracy: true, timeout: 15000, maximumAge: 10000 })
  })
}

/** Field officer app: works offline, camera photos with place and time, product checklist, sync when back online. */
export default function FieldApp() {
  const [list, setList] = useState<Assigned[] | null>(null)
  const [cachedAt, setCachedAt] = useState<number | null>(null)
  const [online, setOnline] = useState(navigator.onLine)
  const [pending, setPending] = useState(0)
  const [msg, setMsg] = useState<string>()
  const [error, setError] = useState<unknown>()
  const [open, setOpen] = useState<Assigned | null>(null)
  const syncing = useRef(false)

  async function load() {
    if (navigator.onLine) {
      try {
        const l = await api.get<Assigned[]>('/api/field/visits')
        setList(l); setCachedAt(null)
        await cacheSet('assigned', l).catch(() => undefined)
        return
      } catch (e) { setError(e) }
    }
    const c = await cacheGet<Assigned[]>('assigned').catch(() => null)
    setList(c?.value ?? []); setCachedAt(c?.at ?? null)
  }

  async function sync() {
    if (syncing.current || !navigator.onLine) return
    syncing.current = true
    try {
      for (const v of await queued()) {
        try {
          const r = await send(v, getToken())
          await remove(v.clientId)
          setMsg(`${v.appNo} ${v.applicant}: synced, visit ${String(r.outcome).toLowerCase()}${r.photoReuse ? ' (photo reuse flagged)' : ''}`)
        } catch (e) {
          const st = (e as { status?: number }).status
          if (st && st >= 400 && st < 500 && st !== 401 && st !== 408) {
            setError(new Error(`${v.appNo}: ${(e as Error).message}. Open the visit, fix it and save again.`))
            await remove(v.clientId)
          } else break   // network or server trouble: try again later
        }
      }
    } finally {
      syncing.current = false
      setPending(await count().catch(() => 0))
      load()
    }
  }

  useEffect(() => {
    load()
    count().then(setPending).catch(() => undefined)
    const on = () => { setOnline(true); sync() }
    const off = () => setOnline(false)
    window.addEventListener('online', on)
    window.addEventListener('offline', off)
    sync()
    return () => { window.removeEventListener('online', on); window.removeEventListener('offline', off) }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  if (open) return <VisitForm a={open} onClose={() => setOpen(null)} onSaved={async m => { setOpen(null); setMsg(m); setPending(await count()); sync() }} />
  return (
    <>
      <Head title="Field visits" sub="Works without signal: visits are kept on this phone (encrypted) and sent when you are back online.">
        <span className={'pill ' + (online ? 'ok' : 'warn')}>{online ? 'Online' : 'Offline'}</span>
      </Head>
      <ErrorBox error={error} />
      {msg && <div className="alert ok">{msg}</div>}
      {pending > 0 && <div className="alert warn" data-field-pending>{pending} visit(s) waiting to sync. {online && <button className="btn sm" onClick={sync}>Sync now</button>}</div>}
      {cachedAt && <p className="small muted">Showing the list saved {new Date(cachedAt).toLocaleString()} (offline).</p>}
      {list && list.length === 0 && <div className="panel empty">No field visits assigned to you.</div>}
      {(list ?? []).map(a => (
        <div key={a.applicationId} className="panel" data-field-visit={a.appNo}>
          <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8 }}>
            <div><b>{a.applicant}</b><div className="small muted">{a.appNo} · {a.product} · {inr(a.amount)}{a.business ? ' · ' + a.business : ''}</div>
              <div className="small">{a.address}</div>
              <div className="small"><a href={`tel:${a.mobile}`}>{a.mobile}</a>{a.addressLat && <> · <a href={`https://maps.google.com/?q=${a.addressLat},${a.addressLng}`} target="_blank" rel="noreferrer">Map</a></>}</div></div>
            <button className="btn primary" onClick={() => setOpen(a)}>Start visit</button>
          </div>
        </div>
      ))}
    </>
  )
}

interface Shot { kind: string; blob: Blob; url: string; takenAt: string; lat?: number; lng?: number; type: string }

function VisitForm({ a, onClose, onSaved }: { a: Assigned; onClose: () => void; onSaved: (msg: string) => void }) {
  const [checkin, setCheckin] = useState<{ at: string; lat?: number; lng?: number; acc?: number } | null>(null)
  const [answers, setAnswers] = useState<Record<string, unknown>>({})
  const [refs, setRefs] = useState([{ name: '', phone: '', says: '' }, { name: '', phone: '', says: '' }])
  const [remarks, setRemarks] = useState('')
  const [shots, setShots] = useState<Shot[]>([])
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const clientId = useMemo(() => crypto.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`, [])
  const fileRef = useRef<HTMLInputElement>(null)
  const kindRef = useRef('front')

  const dist = checkin?.lat != null && a.addressLat != null ? metres([checkin.lat, checkin.lng!], [a.addressLat, a.addressLng!]) : null

  async function doCheckin() {
    const p = await position()
    setCheckin({ at: new Date().toISOString(), lat: p?.coords.latitude, lng: p?.coords.longitude, acc: p?.coords.accuracy })
    if (!p) setError('No GPS position: turn on location. You can still continue; the visit will be flagged.')
  }

  function take(kind: string) { kindRef.current = kind; fileRef.current?.click() }

  async function onPhoto(f: File | undefined) {
    if (!f) return
    if (f.size > 10 * 1024 * 1024) { setError('That photo is over 10 MB; take it again at a lower resolution'); return }
    const p = await position()
    const kind = kindRef.current
    setShots(s => [...s.filter(x => !(x.kind === kind && kind !== 'other')), { kind, blob: f, url: URL.createObjectURL(f), takenAt: new Date().toISOString(),
      lat: p?.coords.latitude, lng: p?.coords.longitude, type: f.type || 'image/jpeg' }])
  }

  const missing = [
    ...(checkin ? [] : ['check in']),
    ...a.checklist.items.filter(i => i.required && (answers[i.key] === undefined || answers[i.key] === '')).map(i => i.label),
    ...a.checklist.photos.filter(k => !shots.some(s => s.kind === k)).map(k => 'photo: ' + PHOTO_LABEL[k]),
    ...(remarks.trim().length < 10 ? ['remarks (10+ characters)'] : []),
  ]

  async function save() {
    if (missing.length) { setError('Still needed: ' + missing.join(', ')); return }
    setBusy(true)
    try {
      const photos: QueuedPhoto[] = shots.map(s => ({ kind: s.kind, takenAt: s.takenAt, lat: s.lat, lng: s.lng, type: s.type, blob: s.blob }))
      await enqueue({ clientId, appId: a.applicationId, appNo: a.appNo, applicant: a.applicant, savedAt: Date.now(), photos,
        visit: { checkinAt: checkin!.at, checkinLat: checkin!.lat ?? null, checkinLng: checkin!.lng ?? null, checkinAccuracy: checkin!.acc ?? null,
          capturedOffline: !navigator.onLine, answers, references: refs.filter(r => r.name.trim()), remarks } })
      onSaved(navigator.onLine ? `${a.appNo}: saved, sending now…` : `${a.appNo}: saved on this phone; it will be sent when you are online.`)
    } catch (e) { setError((e as Error).message) } finally { setBusy(false) }
  }

  const input = (i: Item) => {
    const [t, opts] = i.type.split(':')
    if (t === 'YESNO') return (
      <div className="row" role="radiogroup" aria-label={i.label}>
        {[['Yes', true], ['No', false]].map(([l, v]) => <button key={String(l)} type="button" className={'btn sm' + (answers[i.key] === v ? ' primary' : '')}
          onClick={() => setAnswers({ ...answers, [i.key]: v })}>{String(l)}</button>)}
      </div>)
    if (t === 'NUMBER') return <input type="number" inputMode="decimal" min={0} name={i.key} value={answers[i.key] === undefined ? '' : String(answers[i.key])}
      onChange={e => setAnswers({ ...answers, [i.key]: e.target.value === '' ? undefined : Number(e.target.value) })} />
    return <select name={i.key} value={String(answers[i.key] ?? '')} onChange={e => setAnswers({ ...answers, [i.key]: e.target.value || undefined })}>
      <option value="">—</option>{opts.split('|').map(o => <option key={o} value={o}>{o.toLowerCase()}</option>)}</select>
  }

  return (
    <>
      <Head title={a.applicant} sub={`${a.appNo} · ${a.address}`}><button className="btn" onClick={onClose}>Back</button></Head>
      <input ref={fileRef} type="file" accept="image/*" capture="environment" style={{ display: 'none' }} onChange={e => { onPhoto(e.target.files?.[0]); e.target.value = '' }} />
      <div className="panel">
        <h3 style={{ marginTop: 0 }}>1. Check in at the premises</h3>
        {checkin ? <p className="small" data-checkin>Checked in {new Date(checkin.at).toLocaleTimeString()}{checkin.lat != null && ` · ±${Math.round(checkin.acc ?? 0)} m`}
          {dist != null && <> · <b className={dist > 150 ? 'bad-text' : 'ok-text'}>{Math.round(dist)} m from the business location</b>{dist > 150 && ' (more than 150 m: the visit will be flagged)'}</>}
          {a.addressLat == null && ' · no business location on file to compare'}</p>
          : <button className="btn primary" data-action="checkin" onClick={doCheckin}>Check in here</button>}
      </div>
      <div className="panel">
        <h3 style={{ marginTop: 0 }}>2. Checklist</h3>
        <div className="form" style={{ gridTemplateColumns: '1fr' }}>
          {a.checklist.items.map(i => <label key={i.key} className="fld">{i.label}{i.required ? ' *' : ''}{input(i)}</label>)}
        </div>
      </div>
      <div className="panel">
        <h3 style={{ marginTop: 0 }}>3. Photos (camera only)</h3>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(140px, 1fr))', gap: 8 }}>
          {[...a.checklist.photos, 'other'].map(k => {
            const s = shots.filter(x => x.kind === k)
            return (
              <div key={k} className="panel" style={{ margin: 0, padding: 8, textAlign: 'center' }}>
                <div className="small"><b>{PHOTO_LABEL[k]}</b>{k !== 'other' && ' *'}</div>
                {s.map((x, i) => <img key={i} src={x.url} alt={PHOTO_LABEL[k]} style={{ width: '100%', borderRadius: 6, marginTop: 4 }} />)}
                <button className="btn sm" style={{ marginTop: 6 }} data-photo={k} onClick={() => take(k)}>{s.length && k !== 'other' ? 'Retake' : 'Take photo'}</button>
              </div>
            )
          })}
        </div>
      </div>
      <div className="panel">
        <h3 style={{ marginTop: 0 }}>4. Neighbour or supplier references (optional)</h3>
        {refs.map((r, i) => (
          <div key={i} className="row" style={{ gap: 6, flexWrap: 'wrap', marginBottom: 6 }}>
            <input placeholder="Name" aria-label="Reference name" value={r.name} onChange={e => { const c = [...refs]; c[i] = { ...r, name: e.target.value }; setRefs(c) }} />
            <input placeholder="Phone" inputMode="tel" aria-label="Reference phone" value={r.phone} onChange={e => { const c = [...refs]; c[i] = { ...r, phone: e.target.value }; setRefs(c) }} />
            <input placeholder="What they said" aria-label="What they said" value={r.says} onChange={e => { const c = [...refs]; c[i] = { ...r, says: e.target.value }; setRefs(c) }} />
          </div>
        ))}
      </div>
      <div className="panel">
        <h3 style={{ marginTop: 0 }}>5. Your remarks *</h3>
        <textarea name="fieldRemarks" rows={4} style={{ width: '100%' }} value={remarks} onChange={e => setRemarks(e.target.value)} placeholder="What you saw: business running, stock, premises, family, anything unusual" />
        {error && <div className="alert bad" style={{ marginTop: 8 }}>{error}</div>}
        <button className="btn primary" style={{ marginTop: 8 }} disabled={busy} data-action="saveVisit" onClick={save}>{navigator.onLine ? 'Save and send' : 'Save on this phone'}</button>
      </div>
    </>
  )
}
