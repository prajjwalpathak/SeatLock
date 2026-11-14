import type { CursorPage, EventDetail, EventSearchParams, EventSummary, ShowSeatMap } from '../types/api'
import { apiGet } from './client'

/** TanStack Query keys for catalog data, defined once so caching/invalidation stay consistent. */
export const catalogKeys = {
  all: ['catalog'] as const,
  eventList: (params: EventSearchParams) => ['catalog', 'events', params] as const,
  event: (eventId: number) => ['catalog', 'event', eventId] as const,
  seatMap: (showId: number) => ['catalog', 'seatMap', showId] as const,
}

export function searchEvents(params: EventSearchParams, cursor?: string, signal?: AbortSignal) {
  return apiGet<CursorPage<EventSummary>>('/api/v1/events', { ...params, cursor }, signal)
}

export function getEvent(eventId: number, signal?: AbortSignal) {
  return apiGet<EventDetail>(`/api/v1/events/${eventId}`, undefined, signal)
}

export function getSeatMap(showId: number, signal?: AbortSignal) {
  return apiGet<ShowSeatMap>(`/api/v1/shows/${showId}/seats`, undefined, signal)
}
