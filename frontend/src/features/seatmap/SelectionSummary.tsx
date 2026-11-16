import { Link, useLocation } from 'react-router'
import { formatPaise } from '../../lib/format'
import type { SeatView } from '../../types/api'
import { MAX_SELECTION, seatCode } from './seats'

type Props = {
  seats: SeatView[]
  onClear: () => void
  onHold: () => void
  signedIn: boolean
  /** True while the hold request (and its automatic retries) is running. */
  holding: boolean
  /** When set, holding is not possible right now; shown as the button's tooltip. */
  blockedReason?: string
}

/** Selected seats + total + "Hold seats" (or "Sign in to hold" for anonymous visitors). */
export function SelectionSummary({ seats, onClear, onHold, signedIn, holding, blockedReason }: Props) {
  const location = useLocation()
  const total = seats.reduce((sum, s) => sum + s.pricePaise, 0)

  return (
    <aside
      aria-live="polite"
      className="flex flex-col gap-3 rounded-xl border border-slate-200 bg-white p-4 shadow-sm sm:flex-row sm:items-center sm:justify-between"
    >
      {seats.length === 0 ? (
        <p className="text-sm text-slate-600">Pick up to {MAX_SELECTION} seats on the map.</p>
      ) : (
        <div>
          <p className="text-sm text-slate-600">
            {seats.length} {seats.length === 1 ? 'seat' : 'seats'}:{' '}
            <span className="font-medium text-slate-900">{seats.map(seatCode).join(', ')}</span>
          </p>
          <p className="text-lg font-semibold">{formatPaise(total)}</p>
        </div>
      )}
      <div className="flex gap-2">
        {seats.length > 0 && (
          <button
            type="button"
            onClick={onClear}
            disabled={holding}
            className="rounded-lg border border-slate-300 px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50"
          >
            Clear
          </button>
        )}
        {signedIn ? (
          <button
            type="button"
            onClick={onHold}
            disabled={seats.length === 0 || holding || blockedReason !== undefined}
            title={blockedReason}
            className="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {holding ? 'Holding…' : 'Hold seats'}
          </button>
        ) : (
          <Link
            to="/login"
            state={{ from: location.pathname }}
            className="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700"
          >
            Sign in to hold seats
          </Link>
        )}
      </div>
    </aside>
  )
}
