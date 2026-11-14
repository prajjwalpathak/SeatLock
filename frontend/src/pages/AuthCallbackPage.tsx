import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, Navigate, useSearchParams } from 'react-router'
import { exchangeOAuthCode } from '../api/auth'
import { ErrorMessage } from '../components/ErrorMessage'
import { clearPostLoginRedirect, peekPostLoginRedirect } from '../features/auth/redirect'
import { useAuth } from '../features/auth/useAuth'

/**
 * Google sign-in lands here with ?code=<one-time code>. We swap it for our JWT (POST), then
 * replace this history entry so the code isn't left in the back-button history.
 */
export function AuthCallbackPage() {
  const [searchParams] = useSearchParams()
  const code = searchParams.get('code')
  const { signIn } = useAuth()
  const [redirectTo] = useState(peekPostLoginRedirect)

  // A query (not an effect) so React's dev double-mount still sends ONE request:
  // the code works only once, and a second POST would fail.
  const exchange = useQuery({
    queryKey: ['oauthExchange', code],
    queryFn: async () => {
      const user = await signIn(await exchangeOAuthCode(code!))
      clearPostLoginRedirect()
      return user
    },
    enabled: code !== null,
    retry: false,
    staleTime: Infinity,
    gcTime: 0,
  })

  if (exchange.isSuccess) {
    return <Navigate to={redirectTo} replace />
  }

  return (
    <section className="mx-auto mt-10 max-w-md rounded-xl border border-slate-200 bg-white p-8 text-center">
      <title>Signing in · SeatLock</title>
      <meta name="referrer" content="no-referrer" />
      {code === null || exchange.isError ? (
        <>
          <h1 className="text-lg font-semibold">Couldn’t finish signing in</h1>
          <div className="mt-4 text-left">
            {exchange.isError ? (
              <ErrorMessage error={exchange.error} />
            ) : (
              <p className="text-sm text-slate-600">This sign-in link is missing its code.</p>
            )}
          </div>
          <Link to="/login" replace className="mt-4 inline-block text-sm text-indigo-600 hover:underline">
            Back to sign in
          </Link>
        </>
      ) : (
        <p className="text-sm text-slate-600">Signing you in with Google…</p>
      )}
    </section>
  )
}
