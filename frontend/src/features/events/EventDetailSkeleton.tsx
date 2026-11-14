export function EventDetailSkeleton() {
  return (
    <div aria-hidden="true" className="animate-pulse space-y-4">
      <div className="h-4 w-20 rounded-full bg-slate-200" />
      <div className="h-8 w-2/3 rounded bg-slate-200" />
      <div className="h-4 w-40 rounded bg-slate-200" />
      <div className="space-y-2 pt-2">
        <div className="h-4 w-full rounded bg-slate-200" />
        <div className="h-4 w-11/12 rounded bg-slate-200" />
        <div className="h-4 w-3/4 rounded bg-slate-200" />
      </div>
      <div className="h-40 rounded-xl bg-slate-200" />
    </div>
  )
}
