import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { authKeys, getMe, USER_SCOPED_KEY } from '../../api/auth'
import { setTokenRejectedListener } from '../../api/client'
import {
  clearToken,
  msUntilExpiry,
  readToken,
  TOKEN_STORAGE_KEY,
  writeToken,
  type StoredToken,
} from '../../lib/tokenStorage'
import type { TokenResponse, User } from '../../types/api'
import { AuthContext, type AuthContextValue, type AuthStatus } from './AuthContext'

/** Holds the token and the current user for the whole app. */
export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const [token, setToken] = useState<StoredToken | null>(() => readToken())

  // Who am I? Only asked when we have a token; cached for the session.
  const me = useQuery({
    queryKey: authKeys.me,
    queryFn: ({ signal }) => getMe(signal),
    enabled: token !== null,
    staleTime: Infinity,
    retry: false,
  })

  const signOut = useCallback(() => {
    clearToken()
    setToken(null)
    queryClient.removeQueries({ queryKey: USER_SCOPED_KEY })
  }, [queryClient])

  const signIn = useCallback(
    async (response: TokenResponse): Promise<User> => {
      const stored = { accessToken: response.accessToken, expiresAt: Date.now() + response.expiresIn * 1000 }
      writeToken(stored)
      setToken(stored)
      queryClient.removeQueries({ queryKey: USER_SCOPED_KEY }) // never show the previous user's data
      return queryClient.fetchQuery({ queryKey: authKeys.me, queryFn: ({ signal }) => getMe(signal), staleTime: Infinity })
    },
    [queryClient],
  )

  // The API client tells us when the server rejected our token anywhere in the app.
  useEffect(() => {
    setTokenRejectedListener(signOut)
    return () => setTokenRejectedListener(null)
  }, [signOut])

  // Sign out on this device when the 8 h token expires.
  useEffect(() => {
    if (token === null) return
    const timer = window.setTimeout(signOut, msUntilExpiry(token))
    return () => window.clearTimeout(timer)
  }, [token, signOut])

  // Keep tabs in sync: signing in or out in one tab updates the others.
  useEffect(() => {
    function onStorage(event: StorageEvent) {
      if (event.key !== TOKEN_STORAGE_KEY && event.key !== null) return
      setToken(readToken())
      queryClient.removeQueries({ queryKey: USER_SCOPED_KEY })
    }
    window.addEventListener('storage', onStorage)
    return () => window.removeEventListener('storage', onStorage)
  }, [queryClient])

  const status: AuthStatus = token === null || me.isError ? 'signedOut' : me.data ? 'signedIn' : 'loading'

  const value = useMemo<AuthContextValue>(
    () => ({ status, user: status === 'signedIn' ? (me.data ?? null) : null, signIn, signOut }),
    [status, me.data, signIn, signOut],
  )

  return <AuthContext value={value}>{children}</AuthContext>
}
