import type { LiveStatus } from '../../lib/stompClient'

const LOOK: Record<LiveStatus, { dot: string; text: string; label: string }> = {
  live: { dot: 'bg-emerald-500', text: 'text-emerald-700', label: 'Live' },
  connecting: { dot: 'bg-slate-400', text: 'text-slate-500', label: 'Connecting…' },
  reconnecting: { dot: 'bg-amber-500 animate-pulse', text: 'text-amber-700', label: 'Reconnecting…' },
  idle: { dot: 'bg-slate-300', text: 'text-slate-500', label: 'Not live' },
}

/** "● Live" while seat changes arrive by push; anything else means the map may lag until it reconnects. */
export function LiveBadge({ status, paused }: { status: LiveStatus; paused: boolean }) {
  const look = paused ? { dot: 'bg-amber-500', text: 'text-amber-700', label: 'Live updates paused' } : LOOK[status]
  return (
    <span role="status" aria-live="polite" className={`inline-flex items-center gap-1.5 font-medium ${look.text}`}>
      <span aria-hidden="true" className={`h-2 w-2 rounded-full ${look.dot}`} />
      {look.label}
    </span>
  )
}
