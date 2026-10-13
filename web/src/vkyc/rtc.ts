// WebRTC helpers for video KYC. Signalling goes through the LOS API (polled), so no extra server is needed.
// ICE servers come from the backend (VKYC_ICE_SERVERS); with none, calls work on the same machine or LAN.

export type Signal = { id: number; kind: string; payload: string }

export class Poller {
  private after = 0
  private timer: number | undefined
  private stopped = false
  constructor(private fetchSignals: (after: number) => Promise<Signal[]>, private onSignal: (s: Signal) => Promise<void> | void, private everyMs = 700) {}
  start() {
    const tick = async () => {
      if (this.stopped) return
      try {
        const list = await this.fetchSignals(this.after)
        for (const s of list) {
          this.after = Math.max(this.after, s.id)
          await this.onSignal(s)
        }
      } catch { /* network blip: try again */ }
      if (!this.stopped) this.timer = window.setTimeout(tick, this.everyMs)
    }
    tick()
  }
  stop() { this.stopped = true; if (this.timer) clearTimeout(this.timer) }
}

/** Creates a peer connection that sends ICE candidates through `send` and queues remote candidates until ready. */
export function peer(iceServers: RTCIceServer[], send: (kind: string, payload: string) => Promise<unknown>, onTrack: (s: MediaStream) => void) {
  const pc = new RTCPeerConnection({ iceServers })
  const pending: RTCIceCandidateInit[] = []
  pc.onicecandidate = e => { if (e.candidate) send('ice', JSON.stringify(e.candidate.toJSON())) }
  pc.ontrack = e => onTrack(e.streams[0] ?? new MediaStream([e.track]))
  return {
    pc,
    async addIce(payload: string) {
      const c = JSON.parse(payload) as RTCIceCandidateInit
      if (pc.remoteDescription) await pc.addIceCandidate(c).catch(() => {})
      else pending.push(c)
    },
    async flush() {
      for (const c of pending.splice(0)) await pc.addIceCandidate(c).catch(() => {})
    },
  }
}

/** Grabs the current video frame as a JPEG. */
export function grab(video: HTMLVideoElement, quality = 0.85): Promise<Blob | null> {
  const w = video.videoWidth, h = video.videoHeight
  if (!w || !h) return Promise.resolve(null)
  const c = document.createElement('canvas')
  c.width = w
  c.height = h
  c.getContext('2d')!.drawImage(video, 0, 0, w, h)
  return new Promise(res => c.toBlob(b => res(b), 'image/jpeg', quality))
}

/** One stream with the customer's video and both voices, for the recording. */
export function mixForRecording(remote: MediaStream, local: MediaStream | null): MediaStream {
  try {
    const ctx = new AudioContext()
    const dest = ctx.createMediaStreamDestination()
    for (const s of [remote, local]) {
      if (s && s.getAudioTracks().length) ctx.createMediaStreamSource(s).connect(dest)
    }
    return new MediaStream([...remote.getVideoTracks(), ...dest.stream.getAudioTracks()])
  } catch {
    return remote
  }
}

export function recorder(stream: MediaStream): MediaRecorder | null {
  const types = ['video/webm;codecs=vp8,opus', 'video/webm;codecs=vp9,opus', 'video/webm', 'video/mp4']
  const mimeType = types.find(t => typeof MediaRecorder !== 'undefined' && MediaRecorder.isTypeSupported(t))
  if (!mimeType) return null
  return new MediaRecorder(stream, { mimeType, videoBitsPerSecond: 600_000 })
}

export const sleep = (ms: number) => new Promise(r => setTimeout(r, ms))
