import { Fragment, useCallback, useEffect, useState } from 'react'
import { Account } from './Account'
import { ActivityDetail } from './ActivityDetail'
import { ActivityList } from './ActivityList'
import { ImportFile } from './ImportFile'
import { Records } from './Records'
import { getProfile } from './api'
import type { UserProfile } from './api'
import { signIn, signOut, start } from './auth'
import type { Session } from './auth'
import { setUnits } from './format'
import type { Units } from './format'

type State = { status: 'loading' } | { status: 'error' } | { status: 'ready'; session: Session }

export function App() {
  const [state, setState] = useState<State>({ status: 'loading' })
  const [path, setPath] = useState(() => (typeof window === 'undefined' ? '/' : window.location.pathname))
  // The unit system of the user (TDD-0012): the content is remounted when it changes, so every formatter reads it.
  const [units, setUnitsState] = useState<Units>('metric')
  const signedIn = state.status === 'ready' && state.session.signedIn

  useEffect(() => {
    start().then(
      (session) => {
        setState({ status: 'ready', session })
        setPath(window.location.pathname) // the sign-in callback rewrote it to /
      },
      () => setState({ status: 'error' }),
    )
    const onPop = () => setPath(window.location.pathname)
    window.addEventListener('popstate', onPop)
    return () => window.removeEventListener('popstate', onPop)
  }, [])

  const applyProfile = useCallback((profile: UserProfile) => {
    setUnits(profile.units)
    setUnitsState(profile.units)
  }, [])

  useEffect(() => {
    if (!signedIn) return
    // Without a profile the app stays metric: the units are a convenience, never a reason to block the page.
    getProfile().then(applyProfile, () => undefined)
  }, [signedIn, applyProfile])

  const navigate = useCallback((to: string) => {
    window.history.pushState(null, '', to)
    setPath(to)
  }, [])
  const expired = useCallback(
    () => setState((s) => (s.status === 'ready' ? { status: 'ready', session: { ...s.session, signedIn: false } } : s)),
    [],
  )

  const detail = /^\/activities\/([0-9a-fA-F-]{36})$/.exec(path)

  return (
    <main>
      <header>
        <h1>
          <a
            href="/"
            onClick={(e) => {
              e.preventDefault()
              navigate('/')
            }}
          >
            Averyn
          </a>
        </h1>
        {state.status === 'ready' && state.session.signedIn && (
          <nav>
            <a
              href="/import"
              onClick={(e) => {
                e.preventDefault()
                navigate('/import')
              }}
            >
              Import
            </a>{' '}
            <a
              href="/records"
              onClick={(e) => {
                e.preventDefault()
                navigate('/records')
              }}
            >
              Records
            </a>{' '}
            <a
              href="/account"
              onClick={(e) => {
                e.preventDefault()
                navigate('/account')
              }}
            >
              Account
            </a>{' '}
            <button onClick={() => void signOut()}>Sign out</button>
          </nav>
        )}
      </header>
      {state.status === 'loading' && <p>Loading…</p>}
      {state.status === 'error' && <p role="alert">Could not reach the server or the identity provider.</p>}
      {state.status === 'ready' && (
        <Fragment key={units}>
          {!state.session.signedIn ? (
            <p>
              <button onClick={() => void signIn()}>Sign in</button>
            </p>
          ) : path === '/account' ? (
            <Account onUnauthorized={expired} onProfileSaved={applyProfile} />
          ) : path === '/import' ? (
            <ImportFile
              onUnauthorized={expired}
              onImported={(id) => navigate(`/activities/${id}`)}
            />
          ) : path === '/records' ? (
            <Records
              onUnauthorized={expired}
              open={(id) => navigate(`/activities/${id}`)}
            />
          ) : detail ? (
            <ActivityDetail
              id={detail[1]}
              mapStyleUrl={state.session.config.mapStyleUrl}
              onUnauthorized={expired}
              onDeleted={() => navigate('/')}
            />
          ) : (
            <ActivityList
              open={(id) => navigate(`/activities/${id}`)}
              onUnauthorized={expired}
              onImport={() => navigate('/import')}
            />
          )}
        </Fragment>
      )}
    </main>
  )
}
