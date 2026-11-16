import type { CSSProperties } from 'react'
import { formatPaise } from '../../lib/format'
import type { SeatView, ShowSeatMap } from '../../types/api'
import { groupRows, SEAT_STYLES, seatAriaLabel, sectionName } from './seats'

type Props = {
  seatMap: ShowSeatMap
  selected: ReadonlySet<number>
  onToggle: (seat: SeatView) => void
}

/**
 * The venue as a grid: one CSS-grid line per row, seats placed by their gridCol, so the aisle gap
 * (a skipped column) renders as empty space. A section heading appears where the section changes.
 * Seats are native <button>s: focusable, Enter/Space work, aria-pressed announces selection.
 */
export function SeatMap({ seatMap, selected, onToggle }: Props) {
  const rows = groupRows(seatMap.seats)
  const priceOf = new Map(seatMap.sections.map((s) => [s.section, s.pricePaise]))
  // One column per grid column, every cell the same size (--seat changes with the breakpoint).
  const lineStyle: CSSProperties = { gridTemplateColumns: `repeat(${seatMap.gridCols}, var(--seat))` }

  return (
    <div className="overflow-x-auto rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
      <div className="mx-auto w-max [--seat:1.75rem] sm:[--seat:2rem]">
        <div className="mb-6 flex flex-col items-center gap-1" aria-hidden="true">
          <div className="h-1.5 w-3/4 rounded-full bg-slate-300" />
          <span className="text-xs uppercase tracking-widest text-slate-400">Screen / stage this way</span>
        </div>

        {rows.map((row, i) => {
          const newSection = i === 0 || rows[i - 1].section !== row.section
          return (
            <div key={row.gridRow}>
              {newSection && (
                <h3 className="mb-2 mt-4 flex items-baseline justify-center gap-2 text-xs font-semibold uppercase tracking-wide text-slate-500 first:mt-0">
                  {sectionName(row.section)}
                  <span className="font-normal normal-case text-slate-400">
                    {formatPaise(priceOf.get(row.section) ?? row.seats[0].pricePaise)}
                  </span>
                </h3>
              )}
              <div role="group" aria-label={`Row ${row.rowLabel}`} className="mb-1.5 flex items-center gap-2">
                <RowLabel label={row.rowLabel} />
                <div className="grid gap-1" style={lineStyle}>
                  {row.seats.map((seat) => (
                    <SeatButton
                      key={seat.seatId}
                      seat={seat}
                      selected={selected.has(seat.seatId)}
                      onToggle={onToggle}
                    />
                  ))}
                </div>
                <RowLabel label={row.rowLabel} />
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}

function RowLabel({ label }: { label: string }) {
  return (
    <span aria-hidden="true" className="w-5 text-center text-xs font-medium text-slate-400">
      {label}
    </span>
  )
}

const BASE =
  'flex size-(--seat) items-center justify-center rounded-t-md rounded-b-sm border text-[10px] font-medium ' +
  'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-600 sm:text-xs'

function SeatButton({ seat, selected, onToggle }: { seat: SeatView; selected: boolean; onToggle: Props['onToggle'] }) {
  const available = seat.status === 'AVAILABLE'
  const style = selected
    ? SEAT_STYLES.selected
    : seat.status === 'HELD'
      ? SEAT_STYLES.held
      : seat.status === 'BOOKED'
        ? SEAT_STYLES.booked
        : SEAT_STYLES.available

  return (
    <button
      type="button"
      className={`${BASE} ${style} disabled:cursor-not-allowed`}
      style={{ gridColumnStart: seat.gridCol + 1 }}
      disabled={!available}
      aria-pressed={available ? selected : undefined}
      aria-label={seatAriaLabel(seat)}
      title={seatAriaLabel(seat)}
      onClick={() => onToggle(seat)}
    >
      {seat.status === 'BOOKED' ? '×' : seat.status === 'HELD' ? '–' : seat.seatNumber}
    </button>
  )
}
