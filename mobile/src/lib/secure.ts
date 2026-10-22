// Web: the token is kept in AsyncStorage (localStorage). The phone build uses secure.native.ts (Keychain / Keystore).
import AsyncStorage from '@react-native-async-storage/async-storage'

export const secureGet = (k: string) => AsyncStorage.getItem('secure:' + k)
export const secureSet = (k: string, v: string) => AsyncStorage.setItem('secure:' + k, v)
export const secureDel = (k: string) => AsyncStorage.removeItem('secure:' + k)
