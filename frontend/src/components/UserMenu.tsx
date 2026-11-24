import { Link, NavLink, useNavigate } from 'react-router'
import { hasRole, ROLE_LABELS } from '../features/auth/roles'
import { useAuth } from '../features/auth/useAuth'

const navLinkClass = ({ isActive }: { isActive: boolean }) =>
  `whitespace-nowrap rounded-md px-2 py-1 text-sm ${isActive ? 'font-semibold text-indigo-700' : 'text-slate-600 hover:text-slate-900'}`

/** Right side of the header: sign-in link, or who you are + your pages + sign out. */
export function UserMenu() {
  const { status, user, signOut } = useAuth()
  const navigate = useNavigate()

  if (status === 'loading') {
    return <span className="h-5 w-24 animate-pulse rounded bg-slate-100" aria-hidden="true" />
  }
  if (status === 'signedOut' || !user) {
    return (
      <Link to="/login" className="rounded-lg bg-indigo-600 px-3 py-1.5 text-sm font-semibold text-white hover:bg-indigo-700">
        Sign in
      </Link>
    )
  }
  return (
    <nav className="flex items-center gap-0.5 sm:gap-2" aria-label="Account">
      {hasRole(user.role, 'ORGANIZER') && (
        <NavLink to="/organizer/events" className={navLinkClass}>
          <span className="sm:hidden">Events</span>
          <span className="hidden sm:inline">My events</span>
        </NavLink>
      )}
      <NavLink to="/bookings" end className={navLinkClass}>
        <span className="sm:hidden">Bookings</span>
        <span className="hidden sm:inline">My bookings</span>
      </NavLink>
      <NavLink to="/account" className={navLinkClass} aria-label={`Account: ${user.displayName}`}>
        {/* Phones (W8·D4): an initial avatar instead of the full name + role, so the header fits on one line. */}
        <span
          className="inline-flex h-8 w-8 items-center justify-center rounded-full bg-indigo-600 text-sm font-bold text-white sm:hidden"
          aria-hidden="true"
        >
          {user.displayName.trim().charAt(0).toUpperCase() || '?'}
        </span>
        <span className="hidden sm:inline">{user.displayName}</span>
        <span className="ml-1 hidden rounded bg-slate-100 px-1.5 py-0.5 text-[10px] font-medium uppercase text-slate-600 sm:inline">
          {ROLE_LABELS[user.role]}
        </span>
      </NavLink>
      <button
        type="button"
        onClick={() => {
          signOut()
          navigate('/events', { replace: true })
        }}
        aria-label="Sign out"
        title="Sign out"
        className="whitespace-nowrap rounded-md px-2 py-1 text-sm text-slate-600 hover:text-slate-900"
      >
        <svg viewBox="0 0 24 24" className="h-5 w-5 sm:hidden" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
          <path strokeLinecap="round" strokeLinejoin="round" d="M9 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h3M14 16l4-4-4-4M18 12H8" />
        </svg>
        <span className="hidden sm:inline">Sign out</span>
      </button>
    </nav>
  )
}
