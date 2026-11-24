import { useEffect, useRef, useState, type CSSProperties } from 'react'
import { formatPaise } from '../../lib/format'
import type { SeatView, ShowSeatMap } from '../../types/api'
import { groupRows, SEAT_STYLES, seatAriaLabel, sectionName, SUGGESTED_RING } from './seats'

type Props = {
  seatMap: ShowSeatMap
  selected: ReadonlySet<number>
  onToggle: (seat: SeatView) => void
  /** Seats in the user's own live hold: shown as "your hold", not clickable. */
  mine?: ReadonlySet<number>
  /** Nothing can be selected (e.g. while holding, or the show has started). */
  readOnly?: boolean
  /** Seats the Seat Finder suggests: ringed while still available and not selected. */
  suggested?: ReadonlySet<number>
}

/**
 * The venue as a grid: one CSS-grid line per row, seats placed by their gridCol, so the aisle gap
 * (a skipped column) renders as empty space. A section heading appears where the section changes.
 * Seats are native <button>s: focusable, Enter/Space work, aria-pressed announces selection.
 */
export function SeatMap({ seatMap, selected, onToggle, mine = NONE, readOnly = false, suggested = NONE }: Props) {
  const rows = groupRows(seatMap.seats)
  const priceOf = new Map(seatMap.sections.map((s) => [s.section, s.pricePaise]))
  // One column per grid column, every cell the same size (--seat changes with the breakpoint).
  const lineStyle: CSSProperties = { gridTemplateColumns: `repeat(${seatMap.gridCols}, var(--seat))` }

  const scroller = useRef<HTMLDivElement>(null)
  const [overflowing, setOverflowing] = useState(false)
  // Phones (W8·D4 audit): the grid is wider than the screen. Start centred (stage label and middle seats visible)
  // and say that it scrolls; re-checked on resize/rotation.
  useEffect(() => {
    const el = scroller.current
    if (!el) return
    const check = () => setOverflowing(el.scrollWidth > el.clientWidth + 1)
    el.scrollLeft = Math.max(0, (el.scrollWidth - el.clientWidth) / 2)
    const frame = window.requestAnimationFrame(check) // after layout; also keeps setState out of the effect body
    window.addEventListener('resize', check)
    return () => {
      window.cancelAnimationFrame(frame)
      window.removeEventListener('resize', check)
    }
  }, [seatMap.gridCols])

  return (
    <div>
      {overflowing && (
        <p className="mb-2 text-center text-xs text-slate-500" aria-hidden="true">
          ← Swipe sideways to see all seats →
        </p>
      )}
      <div ref={scroller} className="overflow-x-auto rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
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
                        mine={mine.has(seat.seatId)}
                        suggested={suggested.has(seat.seatId)}
                        readOnly={readOnly}
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

const NONE: ReadonlySet<number> = new Set()

type SeatButtonProps = {
  seat: SeatView
  selected: boolean
  mine: boolean
  suggested: boolean
  readOnly: boolean
  onToggle: Props['onToggle']
}

function SeatButton({ seat, selected, mine, suggested, readOnly, onToggle }: SeatButtonProps) {
  const available = seat.status === 'AVAILABLE' && !mine
  const style = mine
    ? SEAT_STYLES.mine
    : selected
      ? SEAT_STYLES.selected
      : seat.status === 'HELD'
        ? SEAT_STYLES.held
        : seat.status === 'BOOKED'
          ? SEAT_STYLES.booked
          : SEAT_STYLES.available
  const ringed = suggested && available && !selected
  const baseLabel = mine ? `${seatAriaLabel({ ...seat, status: 'AVAILABLE' }).replace(/, available$/, '')}, your hold` : seatAriaLabel(seat)
  const label = ringed ? `${baseLabel}, suggested` : baseLabel

  return (
    <button
      type="button"
      className={`${BASE} ${style} ${ringed ? SUGGESTED_RING : ''} disabled:cursor-not-allowed`}
      style={{ gridColumnStart: seat.gridCol + 1 }}
      disabled={!available || readOnly}
      aria-pressed={available && !readOnly ? selected : undefined}
      aria-label={label}
      title={label}
      onClick={() => onToggle(seat)}
    >
      {mine ? '✓' : seat.status === 'BOOKED' ? '×' : seat.status === 'HELD' ? '–' : seat.seatNumber}
    </button>
  )
}
