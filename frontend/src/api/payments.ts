import type { PaymentOrder } from '../types/api'
import { apiPostWithStatus } from './client'

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
