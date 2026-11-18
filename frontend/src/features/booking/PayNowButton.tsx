import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { ApiError, hasCode } from '../../api/client'
import { holdKeys } from '../../api/holds'
import { createPaymentOrder } from '../../api/payments'
import { formatPaise } from '../../lib/format'
import { openCheckout } from '../../lib/razorpayCheckout'
import type { Booking } from '../../types/api'
import { useAuth } from '../auth/useAuth'

type Phase =
  | { kind: 'idle' }
  | { kind: 'checkout' }
  | { kind: 'submitted'; paymentId: string }
  | { kind: 'failed'; message: string }
  | { kind: 'dismissed' }
  | { kind: 'mock' }

type Props = {
  booking: Booking
  /** Milliseconds left until the booking's deadline (server clock). */
  msLeft: number
  description: string
}

/**
 * "Pay now": asks the backend for the payment order (amount computed there), then opens Razorpay Checkout with
 * that order. Clicking again reuses the same open order (the endpoint is naturally idempotent). Success here is
 * only what the browser was told; the booking turns CONFIRMED once the backend verifies the payment (W4·D3).
 */
export function PayNowButton({ booking, msLeft, description }: Props) {
  const queryClient = useQueryClient()
  const { user } = useAuth()
  const [phase, setPhase] = useState<Phase>({ kind: 'idle' })

  const pay = useMutation({
    mutationFn: async () => {
      const { order } = await createPaymentOrder(booking.publicId)
      // The booking is now PAYMENT_PENDING; refresh it in the background.
      void queryClient.invalidateQueries({ queryKey: holdKeys.booking(booking.publicId) })
      if (order.gateway === 'MOCK' || !order.keyId) {
        setPhase({ kind: 'mock' })
        return
      }
      setPhase({ kind: 'checkout' })
      await openCheckout(
        {
          keyId: order.keyId,
          orderId: order.orderId,
          amountPaise: order.amountPaise,
          currency: order.currency,
          description,
          timeoutSeconds: msLeft / 1000,
          prefill: user ? { name: user.displayName, email: user.email } : undefined,
          bookingId: booking.publicId,
        },
        {
          onSuccess: (response) => {
            setPhase({ kind: 'submitted', paymentId: response.razorpay_payment_id })
            void queryClient.invalidateQueries({ queryKey: holdKeys.booking(booking.publicId) })
          },
          onFailure: (error) =>
            setPhase({ kind: 'failed', message: error.description ?? 'The payment did not go through.' }),
          onDismiss: () =>
            setPhase((current) => (current.kind === 'checkout' ? { kind: 'dismissed' } : current)),
        },
      )
    },
    onError: (error) => {
      setPhase({ kind: 'idle' })
      if (hasCode(error, 'BOOKING_EXPIRED', 'INVALID_BOOKING_TRANSITION', 'NOT_FOUND')) {
        void queryClient.invalidateQueries({ queryKey: holdKeys.booking(booking.publicId) })
      }
    },
  })

  const busy = pay.isPending || phase.kind === 'checkout'
  const submitted = phase.kind === 'submitted'

  return (
    <div className="flex flex-col items-stretch gap-2 sm:items-end">
      <button
        type="button"
        onClick={() => pay.mutate()}
        disabled={busy || submitted || msLeft <= 0}
        className="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700 disabled:cursor-not-allowed disabled:opacity-50"
      >
        {pay.isPending ? 'Preparing payment…' : phase.kind === 'checkout' ? 'Payment window open…' : `Pay ${formatPaise(booking.totalPaise)}`}
      </button>
      <PaymentMessage phase={phase} error={pay.error} />
    </div>
  )
}

function PaymentMessage({ phase, error }: { phase: Phase; error: Error | null }) {
  if (error) {
    const text = hasCode(error, 'BOOKING_EXPIRED')
      ? 'The time to pay is over. Please choose your seats again.'
      : hasCode(error, 'INVALID_BOOKING_TRANSITION')
        ? 'This booking can no longer be paid (it was cancelled or already paid).'
        : hasCode(error, 'PAYMENTS_UNAVAILABLE')
          ? 'Payments are temporarily unavailable. Please try again in a moment.'
          : error instanceof ApiError
            ? error.message
            : 'Could not open the payment window (offline, or blocked by a browser extension). Please try again.'
    return <p role="alert" className="text-sm text-red-700">{text}</p>
  }
  switch (phase.kind) {
    case 'submitted':
      return (
        <p role="status" className="text-sm text-emerald-700">
          Payment received by Razorpay ({phase.paymentId}). Confirming your booking…
        </p>
      )
    case 'failed':
      return <p role="alert" className="text-sm text-red-700">{phase.message} You can try again before the timer runs out.</p>
    case 'dismissed':
      return <p role="status" className="text-sm text-slate-600">Payment window closed. Your seats stay reserved until the timer runs out.</p>
    case 'mock':
      return (
        <p role="status" className="text-sm text-slate-600">
          Demo payment gateway: an order was created, but there is no real checkout in this environment.
        </p>
      )
    default:
      return null
  }
}
