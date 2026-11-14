import { Link, Outlet } from 'react-router'

export function Layout() {
  return (
    <div className="min-h-screen bg-slate-50 text-slate-900">
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex max-w-6xl items-center justify-between px-4 py-3">
          <Link to="/events" className="text-xl font-bold tracking-tight">
            Seat<span className="text-indigo-600">Lock</span>
          </Link>
          <span className="text-sm text-slate-500">Flash-sale tickets, zero oversells</span>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-4 py-6">
        <Outlet />
      </main>
    </div>
  )
}