// All display formatting lives here. The API speaks UTC and paise; conversion to IST and
// rupees happens only in the UI.

const rupees = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 })
const rupeesWithPaise = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', minimumFractionDigits: 2 })

/** 150000 -> "₹1,500"; 37550 -> "₹375.50" (paise shown only when non-zero). */
export function formatPaise(paise: number): string {
  return paise % 100 === 0 ? rupees.format(paise / 100) : rupeesWithPaise.format(paise / 100)
}

const showTime = new Intl.DateTimeFormat('en-IN', {
  timeZone: 'Asia/Kolkata',
  weekday: 'short',
  day: 'numeric',
  month: 'short',
  hour: 'numeric',
  minute: '2-digit',
})

/** UTC ISO string -> IST, e.g. "Sat, 21 Nov, 7:30 pm". */
export function formatShowTime(isoUtc: string): string {
  return showTime.format(new Date(isoUtc))
}

/** 142 -> "2h 22m"; 75 -> "1h 15m"; 45 -> "45m". */
export function formatDuration(minutes: number): string {
  const h = Math.floor(minutes / 60)
  const m = minutes % 60
  if (h === 0) return `${m}m`
  return m === 0 ? `${h}h` : `${h}h ${m}m`
}
