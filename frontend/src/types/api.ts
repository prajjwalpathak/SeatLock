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
  /** Client-only: set from a live HELD-by-hold event; the seat is AVAILABLE again from this instant (server clock). */
  holdExpiresAt?: string | null
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
  /** false = Redis holds couldn't be read; statuses are the DB view only (held seats may look free). */
  holdsLive: boolean
  /** Last live seat event already in this map (0 = none); apply only messages with a greater version. null = Redis down. */
  version: number | null
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
  /** Endpoint-specific extras, e.g. seatIds, holdId, retryAfterSeconds. */
  [extra: string]: unknown
}

// ---------------------------------------------------------------- auth (com.seatlock.identity.api)

export type Role = 'USER' | 'ORGANIZER' | 'ADMIN'

/** Our own RS256 access token. expiresIn is in seconds (8 h). No refresh token: sign in again. */
export type TokenResponse = {
  accessToken: string
  tokenType: 'Bearer'
  expiresIn: number
}

export type User = {
  id: number
  email: string
  displayName: string
  role: Role
}

export type EventStatus = 'DRAFT' | 'PUBLISHED' | 'CANCELLED'

/** GET /api/v1/organizer/events: the organizer's own events, drafts included. */
export type OrganizerEvent = {
  id: number
  title: string
  category: EventCategory
  language: string
  status: EventStatus
  showCount: number
  upcomingShowCount: number
  nextShowAt: string | null
}

// ---------------------------------------------------------------- holds + bookings (com.seatlock.booking.api)

export type HeldSeat = {
  seatId: number
  section: string
  rowLabel: string
  seatNumber: number
  pricePaise: number
}

/** A live hold: seats with server prices; expiresAt is UTC. One hold per user per show. */
export type Hold = {
  holdId: string
  showId: number
  seats: HeldSeat[]
  totalPaise: number
  expiresAt: string
}

export type BookingStatus = 'HELD' | 'PAYMENT_PENDING' | 'CONFIRMED' | 'EXPIRED' | 'CANCELLED' | 'REFUNDED'

export type Booking = {
  publicId: string
  showId: number
  status: BookingStatus
  seats: HeldSeat[]
  totalPaise: number
  currency: string
  expiresAt: string
  confirmedAt: string | null
  cancelledAt: string | null
}

export type PaymentGatewayName = 'MOCK' | 'RAZORPAY'
export type PaymentStatus = 'CREATED' | 'CAPTURED' | 'FAILED' | 'REFUNDED'

/** POST /bookings/{publicId}/payment-order. The amount was computed by the server. */
export type PaymentOrder = {
  bookingId: string
  attempt: number
  gateway: PaymentGatewayName
  orderId: string
  amountPaise: number
  currency: string
  status: PaymentStatus
  /** The booking's deadline; Checkout closes itself then. */
  payBy: string
  /** Razorpay key ID for Checkout.js (public); null for the mock gateway. */
  keyId: string | null
}

export type PaymentOutcome = 'CONFIRMED' | 'ALREADY_DONE' | 'CAPTURED_BOOKING_LOST' | 'NEEDS_REVIEW'

/** POST /payments/razorpay/verify: what happened to the booking after the server checked the payment. */
export type PaymentVerification = {
  outcome: PaymentOutcome
  booking: Booking
}

/** GET /bookings/{publicId}/refund-quote: what cancelling now would give back (refund policy). */
export type RefundQuote = {
  cancellable: boolean
  percent: number
  refundPaise: number
  paidPaise: number
  /** When the percentage drops next (null if it won't). */
  nextChangeAt: string | null
  rule: string
}

/** POST /bookings/{publicId}/refund. */
export type PaidCancellation = {
  booking: Booking
  percent: number
  refundPaise: number
}

export type ServerTime = {
  serverTime: string
  epochMillis: number
}

/** STOMP message on /topic/shows/{showId}/seats (backend SeatEventMessage). */
export type SeatEventMessage = {
  showId: number
  /** Per show, +1 per message. A gap or a smaller number than seen before means: reload the seat map. */
  version: number
  status: ShowSeatStatus
  seatIds: number[]
  /** Only for HELD by a hold: AVAILABLE again from this instant unless a later message says otherwise. */
  holdExpiresAt: string | null
  occurredAt: string
}

/** Offset page (backend PageResponse): admin lists and My bookings. */
export type PageResponse<T> = {
  items: T[]
  page: number
  size: number
  totalItems: number
  totalPages: number
}

/** One row of GET /api/v1/me/bookings. */
export type MyBookingSummary = {
  publicId: string
  status: BookingStatus
  showId: number
  eventId: number | null
  eventTitle: string | null
  showStartsAt: string | null
  venueName: string | null
  city: string | null
  seatCount: number
  totalPaise: number
  currency: string
  expiresAt: string
  createdAt: string
}

// ---------- Seat Finder (W7·D2–D3) ----------

export type RowPreference = 'ANY' | 'FRONT' | 'MIDDLE' | 'BACK'

/** Manual filters; also what the AI understood (SeatAskResult.filters). maxPricePaise is per seat. */
export type SeatFinderFilters = {
  count: number
  maxPricePaise?: number | null
  sections?: string[]
  aisle?: boolean
  rows?: RowPreference
}

export type SeatFinderSeat = {
  seatId: number
  section: string
  rowLabel: string
  seatNumber: number
  pricePaise: number
  aisle: boolean
}

export type SeatFinderOption = {
  rank: number
  score: number
  rowLabel: string
  sections: string[]
  seatIds: number[]
  seats: SeatFinderSeat[]
  totalPricePaise: number
  hasAisleSeat: boolean
}

export type SeatFinderResult = {
  showId: number
  holdsLive: boolean
  options: SeatFinderOption[]
}

/** Why the AI couldn't be used; the UI falls back to the manual filters. */
export type AiFallbackReason =
  | 'DISABLED' | 'USER_QUOTA' | 'QUOTA' | 'CIRCUIT_OPEN' | 'TIMEOUT' | 'BUSY' | 'ERROR' | 'INVALID_OUTPUT'

export type SeatAskResult = {
  understood: boolean
  fallbackReason: AiFallbackReason | null
  cached: boolean
  filters: SeatFinderFilters | null
  assumptions: string[]
  result: SeatFinderResult | null
}

// ---------- Refund Assistant (W7·D5) ----------

export type RefundSituation = 'UNPAID' | 'PAID_CANCELLABLE' | 'PAID_CLOSED' | 'EXPIRED' | 'CANCELLED' | 'REFUNDED'

export type PolicySource = { doc: string; heading: string; text: string }

/** source AI = model explanation that passed the amount check; TEMPLATE = fixed answer (fallbackReason says why). */
export type RefundAnswer = {
  source: 'AI' | 'TEMPLATE'
  answer: string
  fallbackReason: AiFallbackReason | null
  situation: RefundSituation
  sources: PolicySource[]
}
