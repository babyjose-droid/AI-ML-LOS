// Camera, file picker and GPS. Photos are taken with the camera (not the gallery) and stamped with the time and place.
import * as DocumentPicker from 'expo-document-picker'
import * as ImagePicker from 'expo-image-picker'
import * as Location from 'expo-location'
import { keep, type LocalFile } from './files'

export interface Pos { lat: number; lng: number; accuracy?: number }

export async function position(): Promise<Pos | null> {
  try {
    const p = await Location.requestForegroundPermissionsAsync()
    if (!p.granted) return null
    const r = await Promise.race([
      Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.High }),
      new Promise<null>(res => setTimeout(() => res(null), 15000)),
    ])
    return r ? { lat: r.coords.latitude, lng: r.coords.longitude, accuracy: r.coords.accuracy ?? undefined } : null
  } catch {
    return null
  }
}

export interface Shot { file: LocalFile; takenAt: string; pos: Pos | null }

/** Opens the camera; returns null if the officer cancels. */
export async function takePhoto(name: string): Promise<Shot | null> {
  const perm = await ImagePicker.requestCameraPermissionsAsync()
  if (!perm.granted) throw new Error('Allow camera access in the phone settings to take photos')
  const [r, pos] = await Promise.all([ImagePicker.launchCameraAsync({ mediaTypes: ['images'], quality: 0.6, exif: false }), position()])
  if (r.canceled || !r.assets?.length) return null
  const a = r.assets[0]
  const type = a.mimeType ?? 'image/jpeg'
  const file = await keep(a.uri, `${name}.${type === 'image/png' ? 'png' : 'jpg'}`, type, a.fileSize)
  if ((file.size ?? 0) > 10 * 1024 * 1024) throw new Error('That photo is over 10 MB; take it again')
  return { file, takenAt: new Date().toISOString(), pos }
}

/** Picks a PDF or image from the phone (statements, receipts, deeds). */
export async function pickFile(): Promise<LocalFile | null> {
  const r = await DocumentPicker.getDocumentAsync({ type: ['application/pdf', 'image/*'], copyToCacheDirectory: true, multiple: false })
  if (r.canceled || !r.assets?.length) return null
  const a = r.assets[0]
  if ((a.size ?? 0) > 10 * 1024 * 1024) throw new Error('The file is larger than 10 MB')
  return keep(a.uri, a.name, a.mimeType ?? 'application/octet-stream', a.size)
}

export function metres(a: [number, number], b: [number, number]) {
  const R = 6371000, r = Math.PI / 180
  const dp = (b[0] - a[0]) * r, dl = (b[1] - a[1]) * r
  const h = Math.sin(dp / 2) ** 2 + Math.cos(a[0] * r) * Math.cos(b[0] * r) * Math.sin(dl / 2) ** 2
  return 2 * R * Math.asin(Math.sqrt(h))
}
