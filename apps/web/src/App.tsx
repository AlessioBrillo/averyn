import { useCallback, useEffect, useState } from 'react'
import { Account } from './Account'
import { ActivityDetail } from './ActivityDetail'
import { ActivityList } from './ActivityList'
import { signIn, signOut, start } from './auth'
import type { Session } from './auth'

type State = { status: 'loading' } | { status: 'error' } | { status: 'ready'; session: Session }

export function App() {
  const [state, setState] = useState<State>({ status: 'loading' })
  const [path, setPath] = useState(() => (typeof window === 'undefined' ? '/' : window.location.pathname))

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
      {state.status === 'ready' &&
        (!state.session.signedIn ? (
          <p>
            <button onClick={() => void signIn()}>Sign in</button>
          </p>
        ) : path === '/account' ? (
          <Account onUnauthorized={expired} />
        ) : detail ? (
          <ActivityDetail
            id={detail[1]}
            mapStyleUrl={state.session.config.mapStyleUrl}
            onUnauthorized={expired}
            onDeleted={() => navigate('/')}
          />
        ) : (
          <ActivityList open={(id) => navigate(`/activities/${id}`)} onUnauthorized={expired} />
        ))}
    </main>
  )
}
