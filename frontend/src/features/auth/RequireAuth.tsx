import type { ReactNode } from 'react'
import { Link, Navigate, useLocation } from 'react-router'
import type { Role } from '../../types/api'
import { hasRole, ROLE_LABELS } from './roles'
import { useAuth } from './useAuth'

type Props = {
  children: ReactNode
  /** Minimum role (hierarchy applies). Omit for "any signed-in user". */
  role?: Role
}

/**
 * Gate for pages that need sign-in. Signed out → /login, remembering where you were going.
 * This is for UX only; every protected API call is checked again on the server.
 */
export function RequireAuth({ children, role }: Props) {
  const { status, user } = useAuth()
  const location = useLocation()

  if (status === 'loading') {
    return <p className="py-10 text-center text-sm text-slate-500">Checking your session…</p>
  }
  if (status === 'signedOut') {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />
  }
  if (role && !hasRole(user?.role, role)) {
    return (
      <section className="mx-auto mt-10 max-w-md rounded-xl border border-slate-200 bg-white p-8 text-center">
        <title>Not allowed · SeatLock</title>
        <h1 className="text-lg font-semibold">This page is for {ROLE_LABELS[role].toLowerCase()}s</h1>
        <p className="mt-2 text-sm text-slate-600">You’re signed in as a {ROLE_LABELS[user!.role].toLowerCase()}.</p>
        <Link to="/events" className="mt-4 inline-block text-sm text-indigo-600 hover:underline">
          Back to events
        </Link>
      </section>
    )
  }
  return children
}
