export function SeatMapSkeleton() {
  return (
    <div aria-hidden="true" className="animate-pulse space-y-4">
      <div className="h-8 w-2/3 rounded bg-slate-200" />
      <div className="h-4 w-60 rounded bg-slate-200" />
      <div className="h-96 rounded-xl bg-slate-200" />
      <div className="h-16 rounded-xl bg-slate-200" />
    </div>
  )
}
