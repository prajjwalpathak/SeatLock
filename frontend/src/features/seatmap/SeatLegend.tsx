import { SEAT_STYLES } from './seats'

const ITEMS = [
  { style: SEAT_STYLES.available, symbol: '5', label: 'Available' },
  { style: SEAT_STYLES.selected, symbol: '5', label: 'Your selection' },
  { style: SEAT_STYLES.mine, symbol: '✓', label: 'Your hold' },
  { style: SEAT_STYLES.held, symbol: '–', label: 'On hold' },
  { style: SEAT_STYLES.booked, symbol: '×', label: 'Booked' },
]

export function SeatLegend() {
  return (
    <ul className="flex flex-wrap gap-x-5 gap-y-2 text-sm text-slate-600" aria-label="Seat legend">
      {ITEMS.map((item) => (
        <li key={item.label} className="flex items-center gap-2">
          <span
            aria-hidden="true"
            className={`flex size-6 items-center justify-center rounded-t-md rounded-b-sm border text-[10px] font-medium ${item.style}`}
          >
            {item.symbol}
          </span>
          {item.label}
        </li>
      ))}
    </ul>
  )
}
