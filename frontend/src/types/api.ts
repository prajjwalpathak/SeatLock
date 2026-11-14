// Mirrors the backend DTO records (com.seatlock.*.api).
// Money: integer paise (Java long -> number). Times: ISO-8601 UTC strings (Java Instant).

export type EventCategory = 'MOVIE' | 'CONCERT' | 'COMEDY' | 'THEATRE' | 'SPORTS' | 'WORKSHOP'

export const EVENT_CATEGORIES: readonly EventCategory[] = [
  'MOVIE',
  'CONCERT',
  'COMEDY',
  'THEATRE',
  'SPORTS',
  'WORKSHOP',
]

export type ShowSeatStatus = 'AVAILABLE' | 'HELD' | 'BOOKED'

/** One page of a keyset-paginated list; pass nextCursor back unchanged. Null on the last page. */
export type CursorPage<T> = {
  items: T[]
  nextCursor: string | null
}

export type EventSummary = {
  id: number
  title: string
  category: EventCategory
  language: string
  durationMinutes: number
  nextShowAt: string
  nextShowVenue: string
  nextShowCity: string
  fromPricePaise: number
}

export type ShowSummary = {
  id: number
  startsAt: string
  venueId: number
  venueName: string
  city: string
  fromPricePaise: number
}

export type EventDetail = {
  id: number
  title: string
  description: string
  category: EventCategory
  language: string
  durationMinutes: number
  upcomingShows: ShowSummary[]
}

export type VenueRef = {
  id: number
  name: string
  city: string
}

export type SectionPrice = {
  section: string
  pricePaise: number
  seatCount: number
}

export type SeatView = {
  seatId: number
  section: string
  rowLabel: string
  seatNumber: number
  gridRow: number
  gridCol: number
  aisle: boolean
  pricePaise: number
  status: ShowSeatStatus
}

export type ShowSeatMap = {
  showId: number
  eventId: number
  eventTitle: string
  startsAt: string
  venue: VenueRef
  gridRows: number
  gridCols: number
  sections: SectionPrice[]
  seats: SeatView[]
}

/** Query parameters of GET /api/v1/events (all optional; cursor is passed separately). */
export type EventSearchParams = {
  q?: string
  city?: string
  category?: EventCategory
  language?: string
  startsAfter?: string
  startsBefore?: string
  limit?: number
}

export type FieldViolation = {
  field: string
  message: string
}

/** RFC 7807 problem+json as produced by GlobalExceptionHandler. */
export type ProblemDetail = {
  type?: string
  title?: string
  status?: number
  detail?: string
  instance?: string
  code?: string
  requestId?: string
  errors?: FieldViolation[]
}
