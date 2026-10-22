// Video KYC (V-CIP) from the phone: create the customer's one-time link, share it, run the live call with the
// challenges, let the AI check the frames and record the decision. The call screen is the same as on the web console.
import React from 'react'
import { View } from 'react-native'
import WebPage from '../lib/WebPage'
import { useSession } from '../session'
import { Note, Screen } from '../ui'

export default function VideoKyc({ id }: { id: number }) {
  const { online } = useSession()
  return (
    <Screen title="Video KYC" sub="Live call with the customer · AI checks · your decision" scroll={false} testID="vkyc">
      {!online ? <Note kind="warn">Video KYC needs a connection.</Note>
        : <View style={{ flex: 1, margin: -14 }}><WebPage path={`/embed/vkyc/${id}`} testID="vkyc-frame" /></View>}
    </Screen>
  )
}
