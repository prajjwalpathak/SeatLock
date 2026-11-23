import type { SeatAskResult, SeatFinderFilters, SeatFinderResult } from '../types/api'
import { apiPost } from './client'

/** Manual filters → up to 3 options (public, holds nothing). */
export function findSeats(showId: number, filters: SeatFinderFilters): Promise<SeatFinderResult> {
  return apiPost<SeatFinderResult>(`/api/v1/shows/${showId}/seat-finder`, filters)
}

/**
 * Free text → AI filters → options (signed in; spends AI quota). Never fails because the AI is unavailable:
 * that comes back as understood = false + fallbackReason. Errors: RATE_LIMITED (429), TOO_MANY_SEATS (400).
 */
export function askSeatFinder(showId: number, text: string): Promise<SeatAskResult> {
  return apiPost<SeatAskResult>(`/api/v1/shows/${showId}/seat-finder/ask`, { text })
}
