import { useEffect } from 'react'
import { formatPaise } from '../../lib/format'
import { formatCountdown, msUntil } from '../../lib/serverClock'
import { useNow } from '../../lib/useNow'
import type { Hold } from '../../types/api'

type Props = {
  hold: Hold
  clockOffsetMs: number
  busy: boolean
  onRelease: () => void
  onProceed: () => void
  onExpired: () => void
}

/** The user's live hold: seats, total, a countdown on the server's clock, Release / Proceed to pay. */
export function HoldPanel({ hold, clockOffsetMs, busy, onRelease, onProceed, onExpired }: Props) {
  const now = useNow(1000)
  const left = msUntil(hold.expiresAt, clockOffsetMs, now)
  const urgent = left < 60_000

  useEffect(() => {
    if (left === 0) onExpired()
  }, [left, onExpired])

  return (
    <aside
      aria-label="Your hold"
      className="flex flex-col gap-3 rounded-xl border border-emerald-200 bg-emerald-50 p-4 shadow-sm sm:flex-row sm:items-center sm:justify-between"
    >
      <div>
        <p className="text-sm text-emerald-900">
          Held for you: <span className="font-medium">{hold.seats.map((s) => `${s.rowLabel}${s.seatNumber}`).join(', ')}</span>
        </p>
        <p className="text-lg font-semibold">{formatPaise(hold.totalPaise)}</p>
        <p className={`text-sm ${urgent ? 'font-semibold text-red-700' : 'text-emerald-800'}`}>
          {/* Announce rarely (aria-live off on the ticking number), so screen readers aren't flooded. */}
          <span aria-hidden="true">Time left {formatCountdown(left)}</span>
          <span className="sr-only">Seats held until {new Date(hold.expiresAt).toLocaleTimeString('en-IN')}</span>
        </p>
      </div>
      <div className="flex gap-2">
        <button
          type="button"
          onClick={onRelease}
          disabled={busy}
          className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50"
        >
          Release
        </button>
        <button
          type="button"
          onClick={onProceed}
          disabled={busy || left === 0}
          className="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700 disabled:opacity-50"
        >
          {busy ? 'Please wait…' : 'Proceed to pay'}
        </button>
      </div>
    </aside>
  )
}
