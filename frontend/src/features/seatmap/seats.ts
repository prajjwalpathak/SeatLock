import { formatPaise } from '../../lib/format'
import type { SeatView, ShowSeatStatus } from '../../types/api'

/** Client-side cap per selection. The server enforces its own limit when holds arrive (W3·D2). */
export const MAX_SELECTION = 6

/** "A5" */
export function seatCode(seat: SeatView): string {
  return `${seat.rowLabel}${seat.seatNumber}`
}

/** "PLATINUM" -> "Platinum" */
export function sectionName(section: string): string {
  return section.charAt(0) + section.slice(1).toLowerCase()
}

const STATUS_WORDS: Record<ShowSeatStatus, string> = {
  AVAILABLE: 'available',
  HELD: 'on hold by someone else',
  BOOKED: 'booked',
}

/** Full screen-reader label, e.g. "A5, Platinum, aisle, ₹1,500, available". */
export function seatAriaLabel(seat: SeatView): string {
  const parts = [seatCode(seat), sectionName(seat.section)]
  if (seat.aisle) parts.push('aisle')
  parts.push(formatPaise(seat.pricePaise), STATUS_WORDS[seat.status])
  return parts.join(', ')
}

/** Seats grouped by grid row, in rendering order (the API already sorts by row, then column). */
export type SeatRow = { gridRow: number; rowLabel: string; section: string; seats: SeatView[] }

export function groupRows(seats: SeatView[]): SeatRow[] {
  const rows: SeatRow[] = []
  for (const seat of seats) {
    const last = rows.at(-1)
    if (last && last.gridRow === seat.gridRow) {
      last.seats.push(seat)
    } else {
      rows.push({ gridRow: seat.gridRow, rowLabel: seat.rowLabel, section: seat.section, seats: [seat] })
    }
  }
  return rows
}

/** Colour + symbol per state, so the map also works without colour vision (legend matches). */
export const SEAT_STYLES = {
  available: 'border-slate-300 bg-white text-slate-600 hover:border-indigo-500 hover:bg-indigo-50',
  selected: 'border-indigo-600 bg-indigo-600 text-white',
  held: 'border-amber-300 bg-amber-100 text-amber-700',
  mine: 'border-emerald-600 bg-emerald-600 text-white',
  booked: 'border-slate-200 bg-slate-200 text-slate-400',
} as const

/** Extra ring on available seats the Seat Finder suggests (added on top of the available style). */
export const SUGGESTED_RING = 'ring-2 ring-emerald-500 ring-offset-1'
