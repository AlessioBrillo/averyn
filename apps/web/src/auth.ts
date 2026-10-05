import { UserManager, WebStorageStateStore } from 'oidc-client-ts'
import type { ClientConfig } from './api'

// Authorization code + PKCE against the IdP named by /v1/client-config (TDD-0003 §5.3). The session lives in
// sessionStorage only (W5). ponytail: no silent renew, an expired token sends the user through sign-in again;
// add refresh tokens (offline_access) if that proves annoying.

let manager: UserManager | undefined

export interface Session {
  config: ClientConfig
  signedIn: boolean
}

let started: Promise<Session> | undefined

/** Idempotent (React StrictMode runs effects twice, and the code exchange must happen once). */
export function start(): Promise<Session> {
  started ??= begin()
  return started
}

async function begin(): Promise<Session> {
  const response = await fetch('/v1/client-config')
  if (!response.ok) throw new Error(`client-config: HTTP ${response.status}`)
  const config = (await response.json()) as ClientConfig
  const store = new WebStorageStateStore({ store: window.sessionStorage })
  manager = new UserManager({
    authority: config.issuer,
    client_id: config.webClientId,
    redirect_uri: `${window.location.origin}/callback`,
    post_logout_redirect_uri: `${window.location.origin}/`,
    response_type: 'code',
    scope: 'openid',
    userStore: store,
    stateStore: store,
  })
  if (window.location.pathname === '/callback') {
    await manager.signinRedirectCallback()
    window.history.replaceState(null, '', '/')
  }
  return { config, signedIn: (await currentToken()) !== null }
}

/** The access token, or null when signed out or expired. */
export async function currentToken(): Promise<string | null> {
  const user = await manager?.getUser()
  return user && !user.expired ? user.access_token : null
}

export async function signIn(): Promise<void> {
  await manager?.signinRedirect()
}

export async function signOut(): Promise<void> {
  await manager?.removeUser()
  window.location.assign('/')
}
