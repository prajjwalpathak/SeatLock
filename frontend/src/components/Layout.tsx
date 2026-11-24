import { Link, Outlet } from 'react-router'
import { SourceLink, ThemeToggle } from './HeaderIcons'
import { UserMenu } from './UserMenu'

export function Layout() {
  return (
    <div className="min-h-screen bg-slate-50 text-slate-900">
      {/* Sticky, translucent header (W8·D4): content scrolls under it; icons sit next to the account menu. */}
      <header className="sticky top-0 z-20 border-b border-slate-200/80 bg-white/80 backdrop-blur supports-[backdrop-filter]:bg-white/70 dark:bg-slate-50/80">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-3 px-4 py-3">
          <Link to="/events" className="text-xl font-extrabold tracking-tight">
            Seat<span className="text-indigo-600">Lock</span>
          </Link>
          <div className="flex items-center gap-1 sm:gap-2">
            <SourceLink />
            <ThemeToggle />
            <UserMenu />
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-4 py-6 sm:py-8">
        <Outlet />
      </main>
    </div>
  )
}
