import { useNavigate } from 'react-router'
import { ROLE_LABELS } from '../features/auth/roles'
import { useAuth } from '../features/auth/useAuth'

export function AccountPage() {
  const { user, signOut } = useAuth()
  const navigate = useNavigate()
  if (!user) return null // RequireAuth guarantees a user

  return (
    <section className="mx-auto max-w-md rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
      <title>My account · SeatLock</title>
      <h1 className="text-xl font-semibold">My account</h1>
      <dl className="mt-4 space-y-3 text-sm">
        <div>
          <dt className="text-xs font-medium text-slate-500">Name</dt>
          <dd>{user.displayName}</dd>
        </div>
        <div>
          <dt className="text-xs font-medium text-slate-500">E-mail</dt>
          <dd>{user.email}</dd>
        </div>
        <div>
          <dt className="text-xs font-medium text-slate-500">Role</dt>
          <dd>{ROLE_LABELS[user.role]}</dd>
        </div>
      </dl>
      <button
        type="button"
        onClick={() => {
          signOut()
          navigate('/events', { replace: true })
        }}
        className="mt-6 rounded-lg border border-slate-300 px-4 py-2 text-sm font-medium hover:bg-slate-50"
      >
        Sign out
      </button>
    </section>
  )
}
