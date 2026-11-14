export function EventCardSkeleton() {
  return (
    <div aria-hidden="true" className="animate-pulse rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
      <div className="flex justify-between">
        <div className="h-4 w-16 rounded-full bg-slate-200" />
        <div className="h-4 w-24 rounded bg-slate-200" />
      </div>
      <div className="mt-4 h-5 w-3/4 rounded bg-slate-200" />
      <div className="mt-3 h-4 w-1/2 rounded bg-slate-200" />
      <div className="mt-2 h-4 w-2/3 rounded bg-slate-200" />
      <div className="mt-4 h-4 w-20 rounded bg-slate-200" />
    </div>
  )
}
