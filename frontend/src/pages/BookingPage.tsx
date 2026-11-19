import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { catalogKeys, getSeatMap } from '../api/catalog'
import { ApiError, hasCode } from '../api/client'
import { cancelBooking, getBooking, holdKeys } from '../api/holds'
import { ErrorMessage } from '../components/ErrorMessage'
import { CancelPaidBooking } from '../features/booking/CancelPaidBooking'
import { PayNowButton } from '../features/booking/PayNowButton'
import { formatPaise, formatShowTime } from '../lib/format'
import { formatCountdown, msUntil, useServerClockOffset } from '../lib/serverClock'
import { useNow } from '../lib/useNow'
import type { Booking, BookingStatus, PaymentOutcome } from '../types/api'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

const STATUS_TEXT: Record<BookingStatus, { label: string; tone: string; text: string }> = {
  HELD: { label: 'Awaiting payment', tone: 'bg-indigo-50 text-indigo-700', text: 'Your seats are reserved until the timer runs out.' },
  PAYMENT_PENDING: { label: 'Payment in progress', tone: 'bg-indigo-50 text-indigo-700', text: 'We are waiting for the payment provider.' },
  CONFIRMED: { label: 'Confirmed', tone: 'bg-emerald-50 text-emerald-700', text: 'You are all set. Enjoy the show!' },
  EXPIRED: { label: 'Expired', tone: 'bg-slate-100 text-slate-600', text: 'The time ran out before payment, so the seats were released.' },
  CANCELLED: { label: 'Cancelled', tone: 'bg-slate-100 text-slate-600', text: 'You cancelled this booking; the seats were released.' },
  REFUNDED: { label: 'Cancelled · refunded', tone: 'bg-slate-100 text-slate-600', text: 'You cancelled this booking; the refund goes back to your original payment method.' },
}

const isUnpaid = (status: BookingStatus) => status === 'HELD' || status === 'PAYMENT_PENDING'

export function BookingPage() {
  const { publicId = '' } = useParams()
  const valid = UUID.test(publicId)
  const clockOffset = useServerClockOffset()

  const query = useQuery({
    queryKey: holdKeys.booking(publicId),
    queryFn: ({ signal }) => getBooking(publicId, signal),
    enabled: valid,
    staleTime: 0,
    // While unpaid, poll gently so EXPIRED (sweeper, ≤ 30 s) or CONFIRMED (W4) shows up by itself.
    refetchInterval: (q) => (q.state.data && isUnpaid(q.state.data.status) ? 10_000 : false),
  })

  const notFound = !valid || hasCode(query.error, 'NOT_FOUND')
  if (notFound) {
    return (
      <div className="rounded-xl border border-dashed border-slate-300 bg-white p-10 text-center">
        <title>Booking not found · SeatLock</title>
        <h1 className="text-xl font-semibold">Booking not found</h1>
        <p className="mt-2 text-slate-600">The link may be wrong, or this booking belongs to another account.</p>
        <Link to="/events" className="mt-4 inline-block text-sm text-indigo-600 hover:underline">
          Browse events
        </Link>
      </div>
    )
  }
  if (query.isPending) {
    return <div aria-hidden="true" className="h-64 animate-pulse rounded-xl bg-slate-200" />
  }
  if (query.isError) return <ErrorMessage error={query.error} onRetry={() => query.refetch()} />

  return <BookingView booking={query.data} clockOffset={clockOffset} />
}

function BookingView({ booking, clockOffset }: { booking: Booking; clockOffset: number }) {
  const queryClient = useQueryClient()
  const [paymentOutcome, setPaymentOutcome] = useState<PaymentOutcome | null>(null)
  const unpaid = isUnpaid(booking.status)
  const now = useNow(1000, unpaid)
  const left = msUntil(booking.expiresAt, clockOffset, now)
  // Past expiresAt but the sweeper hasn't run yet (≤ 30 s): show it as expired already.
  const status: BookingStatus = unpaid && left === 0 ? 'EXPIRED' : booking.status
  const info = STATUS_TEXT[status]

  const show = useQuery({
    queryKey: catalogKeys.seatMap(booking.showId),
    queryFn: ({ signal }) => getSeatMap(booking.showId, signal),
    staleTime: 60_000,
    retry: false, // header extras only; the booking page works without them
  })

  const cancel = useMutation({
    mutationFn: () => cancelBooking(booking.publicId),
    onSuccess: (updated) => {
      queryClient.setQueryData(holdKeys.booking(updated.publicId), updated)
      queryClient.invalidateQueries({ queryKey: catalogKeys.seatMap(updated.showId) })
    },
    onError: () => queryClient.invalidateQueries({ queryKey: holdKeys.booking(booking.publicId) }),
  })

  return (
    <article className="mx-auto max-w-2xl space-y-5">
      <title>{`Booking · ${show.data?.eventTitle ?? 'SeatLock'}`}</title>

      {show.data && (
        <Link to={`/shows/${booking.showId}`} className="inline-block text-sm text-indigo-600 hover:underline">
          ← Back to the seat map
        </Link>
      )}

      <header className="space-y-1">
        <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${info.tone}`}>{info.label}</span>
        <h1 className="text-2xl font-bold tracking-tight">{show.data?.eventTitle ?? 'Your booking'}</h1>
        {show.data && (
          <p className="text-sm text-slate-500">
            {formatShowTime(show.data.startsAt)} · {show.data.venue.name}, {show.data.venue.city}
          </p>
        )}
      </header>

      <p className="text-slate-700">{info.text}</p>

      <section className="rounded-xl border border-slate-200 bg-white shadow-sm">
        <ul className="divide-y divide-slate-100">
          {booking.seats.map((seat) => (
            <li key={seat.seatId} className="flex justify-between px-4 py-2 text-sm">
              <span>
                <span className="font-medium">
                  {seat.rowLabel}
                  {seat.seatNumber}
                </span>{' '}
                <span className="text-slate-500">· {seat.section.charAt(0) + seat.section.slice(1).toLowerCase()}</span>
              </span>
              <span>{formatPaise(seat.pricePaise)}</span>
            </li>
          ))}
        </ul>
        <div className="flex justify-between border-t border-slate-200 px-4 py-3 font-semibold">
          <span>Total</span>
          <span>{formatPaise(booking.totalPaise)}</span>
        </div>
      </section>

      {status !== booking.status && (
        <p role="status" className="text-sm text-slate-600">
          Updating… (the seats are released within half a minute)
        </p>
      )}

      {unpaid && left > 0 && (
        <div className="flex flex-col gap-3 rounded-xl border border-indigo-200 bg-indigo-50 p-4 sm:flex-row sm:items-start sm:justify-between">
          <p className={`text-sm ${left < 60_000 ? 'font-semibold text-red-700' : 'text-indigo-900'}`}>
            <span aria-hidden="true">Time left to pay {formatCountdown(left)}</span>
            <span className="sr-only">Pay before {new Date(booking.expiresAt).toLocaleTimeString('en-IN')}</span>
          </p>
          <div className="flex flex-col gap-2 sm:flex-row sm:items-start">
            <button
              type="button"
              onClick={() => cancel.mutate()}
              disabled={cancel.isPending}
              className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50"
            >
              {cancel.isPending ? 'Cancelling…' : 'Cancel booking'}
            </button>
            <PayNowButton
              booking={booking}
              msLeft={left}
              description={show.data ? `${show.data.eventTitle} · ${booking.seats.length} seat(s)` : 'Your booking'}
              onVerified={setPaymentOutcome}
            />
          </div>
        </div>
      )}

      {booking.status === 'CONFIRMED' && <CancelPaidBooking booking={booking} />}

      {paymentOutcome === 'CAPTURED_BOOKING_LOST' && (
        <p role="alert" className="rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
          Your payment arrived after the time to pay had run out, so these seats were released. The full amount will
          be refunded to your original payment method.
        </p>
      )}
      {paymentOutcome === 'NEEDS_REVIEW' && (
        <p role="alert" className="rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
          We received a payment that doesn't match this booking and are checking it. If it isn't confirmed shortly,
          it will be refunded.
        </p>
      )}

      {cancel.isError && (
        <p role="alert" className="text-sm text-red-700">
          {hasCode(cancel.error, 'INVALID_BOOKING_TRANSITION')
            ? 'This booking can no longer be cancelled (it has expired or been paid).'
            : cancel.error instanceof ApiError
              ? cancel.error.message
              : 'Could not cancel. Please try again.'}
        </p>
      )}

      {!unpaid && (
        <Link
          to={`/shows/${booking.showId}`}
          className="inline-block rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
        >
          Choose seats again
        </Link>
      )}
    </article>
  )
}
