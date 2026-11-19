import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router'
import { getMyBookings, holdKeys } from '../api/holds'
import { ErrorMessage } from '../components/ErrorMessage'
import { STATUS_TEXT } from '../features/booking/bookingStatus'
import { formatPaise, formatShowTime } from '../lib/format'
import type { MyBookingSummary } from '../types/api'

/** The signed-in user's bookings, newest first; the page number lives in the URL (?page=2). */
export function MyBookingsPage() {
  const [params, setParams] = useSearchParams()
  const raw = Number(params.get('page') ?? '1')
  const page = Number.isInteger(raw) && raw >= 1 ? raw : 1

  const query = useQuery({
    queryKey: holdKeys.myBookings(page - 1),
    queryFn: ({ signal }) => getMyBookings(page - 1, signal),
    placeholderData: keepPreviousData, // keep the old page visible while the next one loads
    staleTime: 0,
  })

  const goTo = (next: number) => setParams(next === 1 ? {} : { page: String(next) })

  return (
    <section className="space-y-4">
      <title>My bookings · SeatLock</title>
      <h1 className="text-2xl font-semibold">My bookings</h1>

      {query.isPending && <p className="text-sm text-slate-500">Loading your bookings…</p>}
      {query.isError && <ErrorMessage error={query.error} onRetry={() => query.refetch()} />}
      {query.isSuccess && query.data.totalItems === 0 && (
        <div className="rounded-xl border border-dashed border-slate-300 bg-white p-8 text-center text-slate-600">
          <p>You haven’t booked anything yet.</p>
          <Link to="/events" className="mt-3 inline-block text-sm text-indigo-600 hover:underline">
            Browse events
          </Link>
        </div>
      )}
      {query.isSuccess && query.data.items.length > 0 && (
        <>
          <ul className={`space-y-3 ${query.isPlaceholderData ? 'opacity-60' : ''}`}>
            {query.data.items.map((b) => (
              <BookingRow key={b.publicId} booking={b} />
            ))}
          </ul>
          {query.data.totalPages > 1 && (
            <nav className="flex items-center justify-between text-sm" aria-label="Pages">
              <button
                type="button"
                disabled={page <= 1}
                onClick={() => goTo(page - 1)}
                className="rounded-md border border-slate-300 bg-white px-3 py-1 font-medium disabled:opacity-40"
              >
                ← Newer
              </button>
              <span className="text-slate-500">
                Page {page} of {query.data.totalPages}
              </span>
              <button
                type="button"
                disabled={page >= query.data.totalPages}
                onClick={() => goTo(page + 1)}
                className="rounded-md border border-slate-300 bg-white px-3 py-1 font-medium disabled:opacity-40"
              >
                Older →
              </button>
            </nav>
          )}
        </>
      )}
    </section>
  )
}

function BookingRow({ booking: b }: { booking: MyBookingSummary }) {
  const status = STATUS_TEXT[b.status]
  return (
    <li>
      <Link
        to={`/bookings/${b.publicId}`}
        className="flex flex-col gap-1 rounded-xl border border-slate-200 bg-white p-4 hover:border-indigo-300 sm:flex-row sm:items-center sm:justify-between"
      >
        <div>
          <p className="font-semibold">{b.eventTitle ?? `Show ${b.showId}`}</p>
          <p className="text-sm text-slate-500">
            {b.showStartsAt ? formatShowTime(b.showStartsAt) : ''}
            {b.venueName ? ` · ${b.venueName}, ${b.city}` : ''}
          </p>
          <p className="text-sm text-slate-500">
            {b.seatCount} {b.seatCount === 1 ? 'seat' : 'seats'} · {formatPaise(b.totalPaise)}
          </p>
        </div>
        <span className={`self-start rounded-full px-2.5 py-1 text-xs font-medium sm:self-center ${status.tone}`}>
          {status.label}
        </span>
      </Link>
    </li>
  )
}
