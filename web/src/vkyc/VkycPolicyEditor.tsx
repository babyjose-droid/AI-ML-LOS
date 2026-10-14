import type { VkycPolicy } from './VideoKycTab'

// Products → Video KYC: when the applicant needs a call (the product's video KYC rule), who else does, how strict the AI
// checks are, when customers can join, attempts, who runs the call and the questions the official must ask.

export const DEFAULT_VKYC: VkycPolicy = {
  parties: 'ON_KYC_REVIEW', minFaceMatch: 0.363, requireLiveness: true, requirePanOnCamera: true, linkHours: 24, callFrom: '00:00', callTo: '23:59',
  maxAttempts: 3, officerRoles: ['OPERATIONS'], questions: ['Confirm your full name and date of birth', 'What is the loan for?'], languages: ['en', 'hi'],
}
const LANGS: [string, string][] = [['en', 'English'], ['hi', 'Hindi'], ['mr', 'Marathi'], ['ta', 'Tamil'], ['ml', 'Malayalam'], ['kn', 'Kannada'], ['te', 'Telugu'], ['bn', 'Bengali'], ['gu', 'Gujarati']]
const ROLES: [string, string][] = [['OPERATIONS', 'Operations'], ['CREDIT_OFFICER', 'Credit officer'], ['CREDIT_MANAGER', 'Credit manager']]

export function parseVkyc(v: unknown): VkycPolicy {
  if (!v) return DEFAULT_VKYC
  try { return { ...DEFAULT_VKYC, ...(typeof v === 'string' ? JSON.parse(v) : v) as Partial<VkycPolicy> } } catch { return DEFAULT_VKYC }
}

type Rule = { videoKycRule?: string; videoKycThreshold?: number | null }

export function VkycPolicyEditor({ value, rule, editable, onChange, onRule }: {
  value: unknown; rule: Rule; editable: boolean; onChange: (p: VkycPolicy) => void; onRule: (r: Rule) => void
}) {
  const p = parseVkyc(value)
  const set = (patch: Partial<VkycPolicy>) => onChange({ ...p, ...patch })
  const toggle = (list: string[], x: string) => list.includes(x) ? list.filter(y => y !== x) : [...list, x]
  return (
    <>
      <div className="form-grid">
        <label className="fld">Applicant needs video KYC<select name="p_videoKycRule" disabled={!editable} value={rule.videoKycRule ?? 'ON_KYC_REVIEW'} onChange={e => onRule({ ...rule, videoKycRule: e.target.value })}>
          <option value="ON_KYC_REVIEW">Only when KYC needs review</option><option value="ALWAYS">Always</option><option value="ABOVE_AMOUNT">Above an amount</option></select></label>
        {rule.videoKycRule === 'ABOVE_AMOUNT' && <label className="fld">Video KYC above (₹)<input name="p_videoKycThreshold" type="number" step={50000} disabled={!editable}
          value={rule.videoKycThreshold ?? ''} onChange={e => onRule({ ...rule, videoKycThreshold: e.target.value === '' ? null : Number(e.target.value) })} /></label>}
        <label className="fld">Co-applicants and guarantors<select name="vk_parties" disabled={!editable} value={p.parties} onChange={e => set({ parties: e.target.value as VkycPolicy['parties'] })}>
          <option value="NONE">Never</option><option value="ON_KYC_REVIEW">When their KYC needs review</option><option value="ALL">Every one, before the decision</option></select></label>
        <label className="fld">Minimum face match (0.30–0.80; AI model's own 0.363)<input name="vk_face" type="number" step={0.01} min={0.3} max={0.8} disabled={!editable} value={p.minFaceMatch}
          onChange={e => set({ minFaceMatch: Number(e.target.value) })} /></label>
        <label className="fld">Link valid for (hours)<input name="vk_link" type="number" min={1} max={168} disabled={!editable} value={p.linkHours} onChange={e => set({ linkHours: Number(e.target.value) })} /></label>
        <label className="fld">Calls per person at most<input name="vk_attempts" type="number" min={1} max={10} disabled={!editable} value={p.maxAttempts} onChange={e => set({ maxAttempts: Number(e.target.value) })} /></label>
        <label className="fld">Customers can join from (India time)<input name="vk_from" type="time" disabled={!editable} value={p.callFrom} onChange={e => set({ callFrom: e.target.value })} /></label>
        <label className="fld">until<input name="vk_to" type="time" disabled={!editable} value={p.callTo} onChange={e => set({ callTo: e.target.value })} /></label>
      </div>
      <div className="row" style={{ marginTop: 10 }}>
        <label className="check"><input type="checkbox" name="vk_live" disabled={!editable} checked={p.requireLiveness} onChange={e => set({ requireLiveness: e.target.checked })} /> Liveness must pass (face, head turns, movement)</label>
        <label className="check"><input type="checkbox" name="vk_pan" disabled={!editable} checked={p.requirePanOnCamera} onChange={e => set({ requirePanOnCamera: e.target.checked })} /> PAN card shown and read on camera</label>
      </div>
      <h4>Who may run the call</h4>
      <div className="row">{ROLES.map(([v, l]) => <label key={v} className="check"><input type="checkbox" disabled={!editable} checked={p.officerRoles.includes(v)}
        onChange={() => set({ officerRoles: toggle(p.officerRoles, v) })} /> {l}</label>)}<span className="small muted">Admins always can.</span></div>
      <h4>Languages offered</h4>
      <div className="row">{LANGS.map(([v, l]) => <label key={v} className="check"><input type="checkbox" disabled={!editable} checked={p.languages.includes(v)}
        onChange={() => set({ languages: toggle(p.languages, v) })} /> {l}</label>)}</div>
      <label className="fld" style={{ marginTop: 12 }}>Questions the official must ask and confirm (one per line, up to 10)
        <textarea name="vk_questions" rows={4} disabled={!editable} value={p.questions.join('\n')} onChange={e => set({ questions: e.target.value.split('\n') })} /></label>
      <p className="small muted">Always on, whatever the product (RBI V-CIP): a live call with a trained official of the lender, the customer's consent, location in India, a random code read out, and a recording kept with the file. The AI result is advice; the official decides, and approving against it needs a reason.</p>
    </>
  )
}
