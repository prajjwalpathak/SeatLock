import type { SeatEventMessage, ShowSeatMap } from '../../types/api'

export type LiveUpdate = { kind: 'apply'; map: ShowSeatMap } | { kind: 'ignore' } | { kind: 'resync' }

/** What the caller knows besides the map itself. */
export type LiveContext = {
  /** Highest version received on this subscription so far (0 = none). */
  lastSeenVersion: number
  /** Server-clock time (ms) when the cached map was last loaded or updated. */
  mapUpdatedAtServerMs: number
}

/** Clock-offset estimates and delivery delays are well under this. */
const CLOCK_MARGIN_MS = 5_000

/**
 * Applies one live seat event to the cached seat map, or says to reload it.
 * The map's `version` = the last event already reflected in it (the backend reads it before the statuses).
 * - version null (Redis was down when the map loaded) → reload, to get a version to follow
 * - smaller than one already received → the counter restarted (Redis restart, no persistence) → reload
 * - ≤ map.version but it happened clearly after the map was loaded → same restart, seen before any other message → reload
 * - ≤ map.version otherwise → already in the map → ignore
 * - > map.version + 1 → a message was missed → reload
 * - otherwise apply (each message states the seats' new status; HELD by a hold carries its expiry)
 */
export function applySeatEvent(map: ShowSeatMap, event: SeatEventMessage, ctx: LiveContext): LiveUpdate {
  if (map.version === null) return { kind: 'resync' }
  if (event.version < ctx.lastSeenVersion) return { kind: 'resync' }
  if (event.version <= map.version) {
    const occurred = new Date(event.occurredAt).getTime()
    return occurred > ctx.mapUpdatedAtServerMs + CLOCK_MARGIN_MS ? { kind: 'resync' } : { kind: 'ignore' }
  }
  if (event.version > map.version + 1) return { kind: 'resync' }

  const changed = new Set(event.seatIds)
  return {
    kind: 'apply',
    map: {
      ...map,
      version: event.version,
      seats: map.seats.map((seat) =>
        changed.has(seat.seatId)
          ? { ...seat, status: event.status, holdExpiresAt: event.status === 'HELD' ? event.holdExpiresAt : null }
          : seat,
      ),
    },
  }
}

/** Earliest pending hold expiry in the map (server-clock ms), or null if no seat is held with a known expiry. */
export function nextHoldExpiry(map: ShowSeatMap): number | null {
  let next: number | null = null
  for (const seat of map.seats) {
    if (seat.status === 'HELD' && seat.holdExpiresAt) {
      const at = new Date(seat.holdExpiresAt).getTime()
      if (next === null || at < next) next = at
    }
  }
  return next
}

/**
 * Holds expire in Redis without any message, so a seat HELD with a known expiry shows as AVAILABLE once that
 * instant has passed on the server clock (unless a later message changed it). Returns the same object when
 * nothing expired, so React sees no change.
 */
export function withExpiredHolds(map: ShowSeatMap, serverNowMs: number): ShowSeatMap {
  const expired = (s: ShowSeatMap['seats'][number]) =>
    s.status === 'HELD' && !!s.holdExpiresAt && new Date(s.holdExpiresAt).getTime() <= serverNowMs
  if (!map.seats.some(expired)) return map
  return {
    ...map,
    seats: map.seats.map((s) => (expired(s) ? { ...s, status: 'AVAILABLE', holdExpiresAt: null } : s)),
  }
}
