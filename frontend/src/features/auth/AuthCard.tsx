import type { ReactNode } from 'react'

export function AuthCard({ title, subtitle, children }: { title: string; subtitle?: ReactNode; children: ReactNode }) {
  return (
    <section className="mx-auto mt-6 max-w-md rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
      <title>{`${title} · SeatLock`}</title>
      <h1 className="text-xl font-semibold">{title}</h1>
      {subtitle && <p className="mt-1 text-sm text-slate-600">{subtitle}</p>}
      <div className="mt-6">{children}</div>
    </section>
  )
}
