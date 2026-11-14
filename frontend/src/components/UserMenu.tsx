import { Link, NavLink, useNavigate } from 'react-router'
import { hasRole, ROLE_LABELS } from '../features/auth/roles'
import { useAuth } from '../features/auth/useAuth'

const navLinkClass = ({ isActive }: { isActive: boolean }) =>
  `rounded-md px-2 py-1 text-sm ${isActive ? 'font-semibold text-indigo-700' : 'text-slate-600 hover:text-slate-900'}`

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
    <nav className="flex items-center gap-2" aria-label="Account">
      {hasRole(user.role, 'ORGANIZER') && (
        <NavLink to="/organizer/events" className={navLinkClass}>
          My events
        </NavLink>
      )}
      <NavLink to="/account" className={navLinkClass}>
        {user.displayName}
        <span className="ml-1 rounded bg-slate-100 px-1.5 py-0.5 text-[10px] font-medium uppercase text-slate-600">
          {ROLE_LABELS[user.role]}
        </span>
      </NavLink>
      <button
        type="button"
        onClick={() => {
          signOut()
          navigate('/events', { replace: true })
        }}
        className="rounded-md px-2 py-1 text-sm text-slate-600 hover:text-slate-900"
      >
        Sign out
      </button>
    </nav>
  )
}
