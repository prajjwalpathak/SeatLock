/**
 * Razorpay Checkout.js (Standard Checkout): loaded on demand from Razorpay's CDN the first time a user pays,
 * so pages that never pay don't download it. Only the public key ID and the server-created order are passed in;
 * the amount comes from the order. Docs: razorpay.com/docs/payments/payment-gateway/web-integration/standard/
 */

const CHECKOUT_SRC = 'https://checkout.razorpay.com/v1/checkout.js'

/** What Razorpay hands the success handler. Verified by the backend (signature check, W4·D3); never trusted here. */
export type CheckoutSuccess = {
  razorpay_payment_id: string
  razorpay_order_id: string
  razorpay_signature: string
}

export type CheckoutFailure = {
  code?: string
  description?: string
  reason?: string
  metadata?: { order_id?: string; payment_id?: string }
}

type CheckoutOptions = {
  key: string
  order_id: string
  amount: number
  currency: string
  name: string
  description?: string
  prefill?: { name?: string; email?: string }
  notes?: Record<string, string>
  theme?: { color?: string }
  timeout?: number
  handler: (response: CheckoutSuccess) => void
  modal?: { ondismiss?: () => void; confirm_close?: boolean }
}

type RazorpayInstance = {
  open: () => void
  close: () => void
  on: (event: 'payment.failed', handler: (response: { error: CheckoutFailure }) => void) => void
}

declare global {
  interface Window {
    Razorpay?: new (options: CheckoutOptions) => RazorpayInstance
  }
}

let loading: Promise<void> | null = null

/** Loads checkout.js once; rejects if it can't be fetched (offline, blocked by an extension). Retried on next call. */
export function loadCheckout(): Promise<void> {
  if (window.Razorpay) return Promise.resolve()
  if (!loading) {
    loading = new Promise<void>((resolve, reject) => {
      const script = document.createElement('script')
      script.src = CHECKOUT_SRC
      script.async = true
      script.onload = () => (window.Razorpay ? resolve() : reject(new Error('Razorpay Checkout did not initialise')))
      script.onerror = () => {
        script.remove()
        reject(new Error('Could not load Razorpay Checkout'))
      }
      document.head.appendChild(script)
    }).catch((e: unknown) => {
      loading = null
      throw e
    })
  }
  return loading
}

export type CheckoutCallbacks = {
  onSuccess: (response: CheckoutSuccess) => void
  /** A payment attempt failed inside the modal; Razorpay lets the user retry there. */
  onFailure: (error: CheckoutFailure) => void
  /** The user closed the modal (or it timed out) without a successful payment. */
  onDismiss: () => void
}

export type CheckoutRequest = {
  keyId: string
  orderId: string
  amountPaise: number
  currency: string
  description: string
  /** Seconds until the booking deadline; Checkout closes itself after this. */
  timeoutSeconds: number
  prefill?: { name?: string; email?: string }
  bookingId: string
}

export async function openCheckout(request: CheckoutRequest, callbacks: CheckoutCallbacks): Promise<void> {
  await loadCheckout()
  const Razorpay = window.Razorpay
  if (!Razorpay) throw new Error('Razorpay Checkout is not available')

  const rzp = new Razorpay({
    key: request.keyId,
    order_id: request.orderId,
    amount: request.amountPaise,
    currency: request.currency,
    name: 'SeatLock',
    description: request.description,
    prefill: request.prefill,
    notes: { booking: request.bookingId },
    theme: { color: '#4f46e5' },
    timeout: Math.max(30, Math.floor(request.timeoutSeconds)),
    handler: callbacks.onSuccess,
    modal: { ondismiss: callbacks.onDismiss, confirm_close: true },
  })
  rzp.on('payment.failed', (response) => callbacks.onFailure(response.error))
  rzp.open()
}
