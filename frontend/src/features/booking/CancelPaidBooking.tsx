import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { catalogKeys } from '../../api/catalog'
import { ApiError, hasCode } from '../../api/client'
import { holdKeys } from '../../api/holds'
import { cancelPaidBooking, getRefundQuote } from '../../api/payments'
import { formatPaise, formatShowTime } from '../../lib/format'
import type { Booking } from '../../types/api'

/**
 * For a CONFIRMED booking: shows what cancelling now refunds (server-side policy), and cancels after a second,
 * explicit click. The refund itself is sent to Razorpay in the background; the seats go back on sale at once.
 */
export function CancelPaidBooking({ booking }: { booking: Booking }) {
  const queryClient = useQueryClient()
  const [confirming, setConfirming] = useState(false)
  const [done, setDone] = useState<{ refundPaise: number; percent: number } | null>(null)

  const quote = useQuery({
    queryKey: [...holdKeys.booking(booking.publicId), 'refund-quote'],
    queryFn: ({ signal }) => getRefundQuote(booking.publicId, signal),
    staleTime: 30_000,
  })

  const cancel = useMutation({
    mutationFn: () => cancelPaidBooking(booking.publicId),
    onSuccess: (result) => {
      setDone({ refundPaise: result.refundPaise, percent: result.percent })
      queryClient.setQueryData(holdKeys.booking(booking.publicId), result.booking)
      void queryClient.invalidateQueries({ queryKey: catalogKeys.seatMap(result.booking.showId) })
    },
    onError: () => {
      setConfirming(false)
      void queryClient.invalidateQueries({ queryKey: [...holdKeys.booking(booking.publicId), 'refund-quote'] })
    },
  })

  if (done) {
    return (
      <p role="status" className="rounded-lg border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-900">
        Booking cancelled. {done.refundPaise > 0
          ? `${formatPaise(done.refundPaise)} (${done.percent}%) will be refunded to your original payment method within 5–7 working days.`
          : 'No refund is due under the cancellation policy.'}
      </p>
    )
  }
  if (quote.isPending) return <div aria-hidden="true" className="h-14 animate-pulse rounded-lg bg-slate-100" />
  if (quote.isError || !quote.data) return null

  const q = quote.data
  if (!q.cancellable) {
    return <p className="text-sm text-slate-500">Cancellation isn't available: {q.rule}.</p>
  }
  return (
    <div className="flex flex-col gap-2 rounded-xl border border-slate-200 bg-white p-4 sm:flex-row sm:items-center sm:justify-between">
      <p className="text-sm text-slate-700">
        Cancel now and get <span className="font-semibold">{formatPaise(q.refundPaise)}</span> back ({q.percent}% of{' '}
        {formatPaise(q.paidPaise)}).
        {q.nextChangeAt && <span className="text-slate-500"> Until {formatShowTime(q.nextChangeAt)}.</span>}
      </p>
      {confirming ? (
        <div className="flex gap-2">
          <button
            type="button"
            onClick={() => setConfirming(false)}
            className="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
          >
            Keep booking
          </button>
          <button
            type="button"
            onClick={() => cancel.mutate()}
            disabled={cancel.isPending}
            className="rounded-lg bg-red-600 px-3 py-2 text-sm font-medium text-white hover:bg-red-700 disabled:opacity-50"
          >
            {cancel.isPending ? 'Cancelling…' : `Yes, cancel and refund ${formatPaise(q.refundPaise)}`}
          </button>
        </div>
      ) : (
        <button
          type="button"
          onClick={() => setConfirming(true)}
          className="rounded-lg border border-red-300 bg-white px-3 py-2 text-sm font-medium text-red-700 hover:bg-red-50"
        >
          Cancel booking
        </button>
      )}
      {cancel.isError && (
        <p role="alert" className="text-sm text-red-700">
          {hasCode(cancel.error, 'CANCELLATION_CLOSED')
            ? 'Cancellation has just closed for this show.'
            : cancel.error instanceof ApiError
              ? cancel.error.message
              : 'Could not cancel. Please try again.'}
        </p>
      )}
    </div>
  )
}
