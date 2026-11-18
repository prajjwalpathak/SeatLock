import type { PaymentOrder, PaymentVerification } from '../types/api'
import { apiPost, apiPostWithStatus } from './client'

/**
 * "Pay now": creates the booking's payment order, or returns the open one (created = false). No body: the server
 * computes the amount. Errors (ApiError.code): BOOKING_EXPIRED, INVALID_BOOKING_TRANSITION, NOT_FOUND,
 * PAYMENTS_UNAVAILABLE (503, try again shortly).
 */
export async function createPaymentOrder(bookingPublicId: string): Promise<{ order: PaymentOrder; created: boolean }> {
  const { status, data } = await apiPostWithStatus<PaymentOrder>(
    `/api/v1/bookings/${encodeURIComponent(bookingPublicId)}/payment-order`,
  )
  return { order: data, created: status === 201 }
}

/**
 * Forwards Razorpay Checkout's success payload; the server verifies the signature and confirms the booking.
 * Safe to repeat (ALREADY_DONE). If it never arrives, Razorpay's webhook confirms the booking anyway.
 * Errors: INVALID_PAYMENT_SIGNATURE (400), NOT_FOUND.
 */
export function verifyPayment(orderId: string, paymentId: string, signature: string): Promise<PaymentVerification> {
  return apiPost<PaymentVerification>('/api/v1/payments/razorpay/verify', { orderId, paymentId, signature })
}
