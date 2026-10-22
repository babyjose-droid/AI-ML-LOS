// Small UI kit: one look for every screen, large touch targets, and test ids / labels the recorded journeys use.
import React, { createContext, useContext, useState } from 'react'
import { ActivityIndicator, Modal, Pressable, ScrollView, StyleSheet, Text, TextInput, View, type TextInputProps, type ViewStyle } from 'react-native'

export const C = {
  bg: '#f4f6f8', card: '#ffffff', ink: '#14212b', muted: '#5f6f7a', line: '#dde3e8', brand: '#0d6e6e', brandInk: '#ffffff',
  ok: '#1b7a3d', okBg: '#e3f4e8', warn: '#8a5a00', warnBg: '#fff3d6', bad: '#b3261e', badBg: '#fde7e5', info: '#1d4f91', infoBg: '#e4eefb',
}

export const inr = (n?: number | null) => n == null ? '—' : '₹' + Math.round(n).toLocaleString('en-IN')
const ACRONYMS = new Set(['pan', 'gst', 'kyc', 'kfs', 'fd', 'cas', 'id', 'utr', 'emi', 'ltv', 'foir', 'apr', 'rc', 'hp', 'dp', 'ifsc', 'pdf', 'gps', 'cro', 'ops', 'msme', 'nach'])
export const label = (s?: string | null) => (s ?? '').replace(/_/g, ' ').toLowerCase().split(' ').map(w => ACRONYMS.has(w) ? w.toUpperCase() : w).join(' ').replace(/^./, c => c.toUpperCase())
export const when = (s?: string | null) => s ? new Date(s).toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : '—'

// ---------- navigation: a plain stack kept in React state ----------
export type Route = { name: string; params?: Record<string, any>; key: string }
type Nav = { toast: (m: string) => void; push: (name: string, params?: Record<string, any>) => void; back: () => void; replace: (name: string, params?: Record<string, any>) => void; reset: (name: string, params?: Record<string, any>) => void; depth: number }
export const NavContext = createContext<Nav>({ toast: () => {}, push: () => {}, back: () => {}, replace: () => {}, reset: () => {}, depth: 0 })
export const useNav = () => useContext(NavContext)

// ---------- building blocks ----------
export function Screen({ title, sub, children, right, scroll = true, testID }: { title: string; sub?: string; children: React.ReactNode; right?: React.ReactNode; scroll?: boolean; testID?: string }) {
  const nav = useNav()
  const head = (
    <View style={s.head}>
      {nav.depth > 0 && <Pressable accessibilityRole="button" accessibilityLabel="Back" testID="back" onPress={nav.back} style={s.backBtn}><Text style={s.backTxt}>‹</Text></Pressable>}
      <View style={{ flex: 1 }}>
        <Text style={[s.title, { color: '#fff' }]} accessibilityRole="header" numberOfLines={2}>{title}</Text>
        {sub ? <Text style={s.sub} numberOfLines={3}>{sub}</Text> : null}
      </View>
      {right}
    </View>
  )
  return (
    <View style={{ flex: 1, backgroundColor: C.bg }} testID={testID}>
      {head}
      {scroll ? <ScrollView contentContainerStyle={{ padding: 14, paddingBottom: 40 }} keyboardShouldPersistTaps="handled">{children}</ScrollView> : <View style={{ flex: 1, padding: 14 }}>{children}</View>}
    </View>
  )
}

export function Card({ children, title, style, testID }: { children?: React.ReactNode; title?: string; style?: ViewStyle; testID?: string }) {
  return <View style={[s.card, style]} testID={testID}>{title ? <Text style={s.cardTitle}>{title}</Text> : null}{children}</View>
}

export function Btn({ title, onPress, kind = 'plain', disabled, busy, testID, small, style }: { title: string; onPress: () => void; kind?: 'primary' | 'plain' | 'danger'; disabled?: boolean; busy?: boolean; testID?: string; small?: boolean; style?: ViewStyle }) {
  const bg = kind === 'primary' ? C.brand : kind === 'danger' ? C.bad : C.card
  const fg = kind === 'plain' ? C.ink : C.brandInk
  return (
    <Pressable accessibilityRole="button" accessibilityLabel={title} accessibilityState={{ disabled: !!(disabled || busy) }} testID={testID} disabled={disabled || busy} onPress={onPress}
      style={({ pressed }) => [s.btn, small && s.btnSm, { backgroundColor: bg, opacity: disabled ? 0.45 : pressed ? 0.8 : 1, borderColor: kind === 'plain' ? C.line : bg }, style]}>
      {busy ? <ActivityIndicator color={fg} /> : <Text style={[s.btnTxt, small && { fontSize: 14 }, { color: fg }]}>{title}</Text>}
    </Pressable>
  )
}

export function Input({ label: l, hint, error, ...p }: TextInputProps & { label: string; hint?: string; error?: string }) {
  return (
    <View style={{ marginBottom: 12 }}>
      <Text style={s.lbl}>{l}</Text>
      <TextInput accessibilityLabel={l} placeholderTextColor="#98a4ad" {...p} style={[s.input, p.multiline && { minHeight: 84, textAlignVertical: 'top' }, error ? { borderColor: C.bad } : null, p.style]} />
      {hint ? <Text style={s.hint}>{hint}</Text> : null}
      {error ? <Text style={[s.hint, { color: C.bad }]}>{error}</Text> : null}
    </View>
  )
}

/** A choice shown as a row of chips (short lists) — no native picker needed. */
export function Choice({ label: l, value, options, onChange, testID }: { label?: string; value: string; options: [string, string][]; onChange: (v: string) => void; testID?: string }) {
  return (
    <View style={{ marginBottom: 12 }} testID={testID} accessibilityRole="radiogroup" accessibilityLabel={l}>
      {l ? <Text style={s.lbl}>{l}</Text> : null}
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 6 }}>
        {options.map(([k, t]) => (
          <Pressable key={k} accessibilityRole="radio" accessibilityState={{ checked: value === k }} accessibilityLabel={t} onPress={() => onChange(k)}
            style={[s.chip, value === k && { backgroundColor: C.brand, borderColor: C.brand }]}>
            <Text style={{ color: value === k ? '#fff' : C.ink, fontSize: 14 }}>{t}</Text>
          </Pressable>
        ))}
      </View>
    </View>
  )
}

/** A long list (products, dealers): shows the choice, opens a searchable list. */
export function Select({ label: l, value, options, onChange, testID }: { label: string; value: string; options: [string, string][]; onChange: (v: string) => void; testID?: string }) {
  const [open, setOpen] = useState(false)
  const [q, setQ] = useState('')
  const cur = options.find(o => o[0] === value)
  const shown = options.filter(o => !q || (o[1] + ' ' + o[0]).toLowerCase().includes(q.toLowerCase()))
  return (
    <View style={{ marginBottom: 12 }}>
      <Text style={s.lbl}>{l}</Text>
      <Pressable accessibilityRole="button" accessibilityLabel={l + ': ' + (cur?.[1] ?? 'choose')} testID={testID} onPress={() => setOpen(true)} style={[s.input, { flexDirection: 'row', alignItems: 'center' }]}>
        <Text style={{ flex: 1, fontSize: 16, color: cur ? C.ink : C.muted }}>{cur?.[1] ?? 'Choose…'}</Text><Text style={{ color: C.muted }}>▾</Text>
      </Pressable>
      <Sheet visible={open} title={l} onClose={() => setOpen(false)}>
        <Input label="Search" value={q} onChangeText={setQ} autoCapitalize="none" testID={testID ? testID + '-search' : undefined} />
        {shown.map(([k, t]) => (
          <Pressable key={k} accessibilityRole="button" accessibilityLabel={t} onPress={() => { onChange(k); setOpen(false); setQ('') }}
            style={[s.item, k === value && { borderColor: C.brand }]}><Text style={{ fontSize: 15, color: C.ink, flex: 1 }}>{t}</Text>{k === value ? <Text style={{ color: C.brand }}>✓</Text> : null}</Pressable>
        ))}
      </Sheet>
    </View>
  )
}

export function Check({ label: l, value, onChange }: { label: string; value: boolean; onChange: (v: boolean) => void }) {
  return (
    <Pressable accessibilityRole="checkbox" accessibilityState={{ checked: value }} accessibilityLabel={l} onPress={() => onChange(!value)} style={{ flexDirection: 'row', alignItems: 'flex-start', gap: 10, marginBottom: 12 }}>
      <View style={[s.box, value && { backgroundColor: C.brand, borderColor: C.brand }]}>{value ? <Text style={{ color: '#fff', fontWeight: '700' }}>✓</Text> : null}</View>
      <Text style={{ flex: 1, color: C.ink, fontSize: 15 }}>{l}</Text>
    </Pressable>
  )
}

const PILL: Record<string, [string, string]> = {
  PASS: [C.ok, C.okBg], DONE: [C.ok, C.okBg], SUCCESS: [C.ok, C.okBg], APPROVE: [C.ok, C.okBg], SANCTIONED: [C.ok, C.okBg], DISBURSED: [C.ok, C.okBg], VERIFIED: [C.ok, C.okBg], MARKED: [C.ok, C.okBg], ACCEPTED: [C.ok, C.okBg], CLEAR: [C.ok, C.okBg], FUNDED: [C.ok, C.okBg], PAID: [C.ok, C.okBg], CURED: [C.ok, C.okBg],
  REFER: [C.warn, C.warnBg], PENDING: [C.muted, '#eef1f3'], WAITING: [C.warn, C.warnBg], IN_PROGRESS: [C.info, C.infoBg], SUBMITTED: [C.info, C.infoBg], UNDERWRITING: [C.info, C.infoBg], REVIEW: [C.warn, C.warnBg], OPEN: [C.warn, C.warnBg],
  FAIL: [C.bad, C.badBg], FAILED: [C.bad, C.badBg], DECLINE: [C.bad, C.badBg], REJECTED: [C.bad, C.badBg], DECLINED: [C.bad, C.badBg], OFFLINE: [C.warn, C.warnBg], ONLINE: [C.ok, C.okBg],
}
export function Pill({ s: st, text }: { s?: string | null; text?: string }) {
  const [fg, bg] = PILL[st ?? ''] ?? [C.muted, '#eef1f3']
  return <View style={[s.pill, { backgroundColor: bg }]}><Text style={{ color: fg, fontSize: 12, fontWeight: '600' }}>{text ?? label(st ?? '—')}</Text></View>
}

export function Row({ k, v, testID }: { k: string; v?: React.ReactNode; testID?: string }) {
  return (
    <View style={s.kv} testID={testID}>
      <Text style={s.k}>{k}</Text>
      {typeof v === 'string' || typeof v === 'number' || v == null ? <Text style={s.v}>{v ?? '—'}</Text> : <View style={{ flexShrink: 1, alignItems: 'flex-end' }}>{v}</View>}
    </View>
  )
}

export function Note({ kind = 'info', children, testID }: { kind?: 'info' | 'ok' | 'warn' | 'bad'; children: React.ReactNode; testID?: string }) {
  const [fg, bg] = kind === 'ok' ? [C.ok, C.okBg] : kind === 'warn' ? [C.warn, C.warnBg] : kind === 'bad' ? [C.bad, C.badBg] : [C.info, C.infoBg]
  return <View style={[s.note, { backgroundColor: bg }]} testID={testID} accessibilityRole={kind === 'bad' ? 'alert' : undefined}><Text style={{ color: fg, fontSize: 14 }}>{children}</Text></View>
}

export function ListItem({ title, sub, right, onPress, testID }: { title: string; sub?: string; right?: React.ReactNode; onPress?: () => void; testID?: string }) {
  return (
    <Pressable onPress={onPress} disabled={!onPress} testID={testID} accessibilityRole={onPress ? 'button' : undefined} accessibilityLabel={title}
      style={({ pressed }) => [s.item, pressed && { backgroundColor: '#eef3f3' }]}>
      <View style={{ flex: 1 }}>
        <Text style={{ fontSize: 16, fontWeight: '600', color: C.ink }}>{title}</Text>
        {sub ? <Text style={{ color: C.muted, marginTop: 2, fontSize: 13 }}>{sub}</Text> : null}
      </View>
      {right}
      {onPress ? <Text style={{ color: C.muted, fontSize: 22, marginLeft: 6 }}>›</Text> : null}
    </Pressable>
  )
}

export function Sheet({ visible, title, onClose, children }: { visible: boolean; title: string; onClose: () => void; children: React.ReactNode }) {
  return (
    <Modal visible={visible} transparent animationType="slide" onRequestClose={onClose}>
      <View style={s.scrim}>
        <View style={s.sheet} accessibilityViewIsModal testID="sheet">
          <View style={{ flexDirection: 'row', alignItems: 'center', marginBottom: 10 }}>
            <Text style={[s.title, { flex: 1 }]}>{title}</Text>
            <Pressable onPress={onClose} accessibilityRole="button" accessibilityLabel="Close" testID="sheet-close"><Text style={{ fontSize: 26, color: C.muted, paddingHorizontal: 8 }}>×</Text></Pressable>
          </View>
          <ScrollView keyboardShouldPersistTaps="handled">{children}</ScrollView>
        </View>
      </View>
    </Modal>
  )
}

export const Muted = ({ children }: { children: React.ReactNode }) => <Text style={{ color: C.muted, fontSize: 13, marginBottom: 8 }}>{children}</Text>
export const H = ({ children }: { children: React.ReactNode }) => <Text style={{ fontSize: 15, fontWeight: '700', color: C.ink, marginTop: 6, marginBottom: 8 }}>{children}</Text>
export const Body = ({ children, testID }: { children: React.ReactNode; testID?: string }) => <Text testID={testID} style={{ fontSize: 15, color: C.ink, lineHeight: 21 }}>{children}</Text>
export const Spinner = () => <View style={{ padding: 30 }}><ActivityIndicator color={C.brand} /></View>

/** Runs an async action with a busy flag, an error and a success message. */
export function useRun() {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string>()
  const [ok, setOk] = useState<string>()
  async function run(f: () => Promise<unknown>, done?: string | ((r: any) => string)) {
    setBusy(true); setError(undefined); setOk(undefined)
    try {
      const r = await f()
      if (done) setOk(typeof done === 'function' ? done(r) : done)
      return r === undefined || r === null ? true : r   // undefined means it failed
    } catch (e: any) {
      setError(e?.fields ? e.message + ': ' + Object.entries(e.fields).map(([k, v]) => `${k} ${v}`).join(', ') : e?.message ?? String(e))
      return undefined
    } finally { setBusy(false) }
  }
  return { busy, error, ok, run, setError, setOk }
}

export function Feedback({ error, ok }: { error?: string; ok?: string }) {
  return <>{error ? <Note kind="bad" testID="error">{error}</Note> : null}{ok ? <Note kind="ok" testID="ok">{ok}</Note> : null}</>
}

const s = StyleSheet.create({
  head: { backgroundColor: C.brand, paddingHorizontal: 14, paddingTop: 12, paddingBottom: 12, flexDirection: 'row', alignItems: 'center', gap: 8 },
  backBtn: { paddingRight: 8, paddingVertical: 2 },
  backTxt: { color: '#fff', fontSize: 32, lineHeight: 34 },
  title: { fontSize: 19, fontWeight: '700', color: C.ink },
  sub: { color: '#d6efef', fontSize: 13, marginTop: 2 },
  card: { backgroundColor: C.card, borderRadius: 12, padding: 14, marginBottom: 12, borderWidth: 1, borderColor: C.line },
  cardTitle: { fontSize: 16, fontWeight: '700', color: C.ink, marginBottom: 8 },
  btn: { minHeight: 46, borderRadius: 10, paddingHorizontal: 16, justifyContent: 'center', alignItems: 'center', borderWidth: 1, marginBottom: 8 },
  btnSm: { minHeight: 36, paddingHorizontal: 12 },
  btnTxt: { fontSize: 16, fontWeight: '600' },
  lbl: { fontSize: 13, color: C.muted, marginBottom: 4, fontWeight: '600' },
  hint: { fontSize: 12, color: C.muted, marginTop: 3 },
  input: { borderWidth: 1, borderColor: C.line, borderRadius: 10, paddingHorizontal: 12, paddingVertical: 10, fontSize: 16, backgroundColor: '#fff', color: C.ink },
  chip: { borderWidth: 1, borderColor: C.line, borderRadius: 18, paddingHorizontal: 12, paddingVertical: 7, backgroundColor: '#fff' },
  box: { width: 24, height: 24, borderRadius: 6, borderWidth: 2, borderColor: C.line, alignItems: 'center', justifyContent: 'center' },
  pill: { borderRadius: 10, paddingHorizontal: 8, paddingVertical: 3, alignSelf: 'flex-start' },
  kv: { flexDirection: 'row', justifyContent: 'space-between', gap: 10, paddingVertical: 6, borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: C.line },
  k: { color: C.muted, fontSize: 14, flexShrink: 0, maxWidth: '45%' },
  v: { color: C.ink, fontSize: 14, fontWeight: '600', flexShrink: 1, textAlign: 'right' },
  note: { borderRadius: 10, padding: 10, marginBottom: 10 },
  item: { backgroundColor: C.card, borderRadius: 12, padding: 14, marginBottom: 8, flexDirection: 'row', alignItems: 'center', borderWidth: 1, borderColor: C.line },
  scrim: { flex: 1, backgroundColor: 'rgba(0,0,0,0.35)', justifyContent: 'flex-end' },
  sheet: { backgroundColor: C.bg, borderTopLeftRadius: 16, borderTopRightRadius: 16, padding: 16, maxHeight: '88%' },
})
