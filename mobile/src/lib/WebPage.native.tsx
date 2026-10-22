// Phone build: a page of the web console (the video KYC call) inside the app, signed in with the officer's session.
// The camera and microphone are shared with the page for the call.
import React from 'react'
import { WebView } from 'react-native-webview'
import { getTenant, getToken, url } from './api'

export default function WebPage({ path, testID }: { path: string; testID?: string }) {
  const js = `try { localStorage.setItem('rhythm_token', ${JSON.stringify(getToken() ?? '')}); ${getTenant() ? `localStorage.setItem('rhythm_tenant', ${JSON.stringify(getTenant())});` : "localStorage.removeItem('rhythm_tenant');"} } catch (e) {} true;`
  return (
    <WebView testID={testID} source={{ uri: url(path) }} injectedJavaScriptBeforeContentLoaded={js} originWhitelist={['*']}
      mediaCapturePermissionGrantType="grant" allowsInlineMediaPlayback mediaPlaybackRequiresUserAction={false} javaScriptEnabled domStorageEnabled
      style={{ flex: 1 }} />
  )
}
