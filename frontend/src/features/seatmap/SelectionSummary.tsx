import { formatPaise } from '../../lib/format'
import type { SeatView } from '../../types/api'
import { MAX_SELECTION, seatCode } from './seats'

type Props = {
  seats: SeatView[]
  onClear: () => void
}

/** Selected seats + total. Holding them (and the countdown) arrives with the hold API. */
export function SelectionSummary({ seats, onClear }: Props) {
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
            className="rounded-lg border border-slate-300 px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
          >
            Clear
          </button>
        )}
        <button
          type="button"
          disabled
          title="Holding seats is coming soon"
          className="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white disabled:cursor-not-allowed disabled:opacity-50"
        >
          Hold seats
        </button>
      </div>
    </aside>
  )
}
