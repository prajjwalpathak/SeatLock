import type { RefundAnswer } from '../types/api'
import { apiPost } from './client'

/** Ask about cancelling my booking. Never changes it. Errors: NOT_FOUND (not mine), RATE_LIMITED (429). */
export function askRefundAssistant(bookingId: string, question: string): Promise<RefundAnswer> {
  return apiPost<RefundAnswer>(`/api/v1/bookings/${encodeURIComponent(bookingId)}/assistant`, { question })
}
