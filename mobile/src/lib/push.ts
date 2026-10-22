// Web build: no push notifications (alerts still show in the app, refreshed every minute).
export async function registerPush(): Promise<string | null> { return null }
export async function unregisterPush(): Promise<void> {}
export async function showLocal(_title: string, _body: string, _applicationId?: number): Promise<void> {}
export function onOpen(_f: (applicationId?: number) => void): () => void { return () => {} }
