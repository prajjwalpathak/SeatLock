import type { Booking, Hold, MyBookingSummary, PageResponse } from '../types/api'
import { USER_SCOPED_KEY } from './auth'
import { apiDelete, apiGet, apiPostWithStatus } from './client'

/** Holds and bookings are the signed-in user's data → under ['me', …] (dropped on sign-out). */
export const holdKeys = {
  hold: (holdId: string) => [...USER_SCOPED_KEY, 'hold', holdId] as const,
  booking: (publicId: string) => [...USER_SCOPED_KEY, 'booking', publicId] as const,
  myBookings: (page: number) => [...USER_SCOPED_KEY, 'bookings', page] as const,
}

/** GET /me/bookings: my bookings, newest first. */
export function getMyBookings(page: number, signal?: AbortSignal): Promise<PageResponse<MyBookingSummary>> {
  return apiGet<PageResponse<MyBookingSummary>>('/api/v1/me/bookings', { page, size: PAGE_SIZE }, signal)
}

export const PAGE_SIZE = 10

/** A fresh Idempotency-Key for one user attempt. Retries of the SAME attempt must reuse it. */
export function newIdempotencyKey(): string {
  return crypto.randomUUID()
}

/**
 * POST /shows/{id}/holds. Errors to expect (ApiError.code): SEATS_UNAVAILABLE (details.seatIds),
 * HOLD_EXISTS (details.holdId), SHOW_STARTED, TOO_MANY_SEATS, RATE_LIMITED (retryAfterSeconds),
 * HOLDS_UNAVAILABLE (503, retryable), IDEMPOTENCY_IN_PROGRESS (retry with the same key).
 */
export async function createHold(showId: number, seatIds: number[], idempotencyKey: string): Promise<Hold> {
  const { data } = await apiPostWithStatus<Hold>(`/api/v1/shows/${showId}/holds`, { seatIds }, {
    'Idempotency-Key': idempotencyKey,
  })
  return data
}

export function getHold(holdId: string, signal?: AbortSignal): Promise<Hold> {
  return apiGet<Hold>(`/api/v1/holds/${encodeURIComponent(holdId)}`, undefined, signal)
}

export function releaseHold(holdId: string): Promise<void> {
  return apiDelete(`/api/v1/holds/${encodeURIComponent(holdId)}`)
}

/** "Proceed to pay". Naturally idempotent: repeating returns the same booking (created = false). */
export async function bookHold(holdId: string): Promise<{ booking: Booking; created: boolean }> {
  const { status, data } = await apiPostWithStatus<Booking>(`/api/v1/holds/${encodeURIComponent(holdId)}/booking`)
  return { booking: data, created: status === 201 }
}

export function getBooking(publicId: string, signal?: AbortSignal): Promise<Booking> {
  return apiGet<Booking>(`/api/v1/bookings/${encodeURIComponent(publicId)}`, undefined, signal)
}

export async function cancelBooking(publicId: string): Promise<Booking> {
  const { data } = await apiPostWithStatus<Booking>(`/api/v1/bookings/${encodeURIComponent(publicId)}/cancel`)
  return data
}
