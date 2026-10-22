// Phone build: connection state from the OS. Internet reachability is not checked, because the Rhythm server may be
// on the office network; a request that cannot reach the server is simply kept in the outbox and retried.
import NetInfo from '@react-native-community/netinfo'

NetInfo.configure({ reachabilityShouldRun: () => false })

export function watchNet(cb: (online: boolean) => void): () => void {
  return NetInfo.addEventListener(st => cb(st.isConnected !== false))
}
