import type { SeatEventMessage, ShowSeatMap } from '../../types/api'

export type LiveUpdate = { kind: 'apply'; map: ShowSeatMap } | { kind: 'ignore' } | { kind: 'resync' }

/**
 * Applies one live seat event to the cached seat map, or says to reload it.
 * The map's `version` = the last event already reflected in it (the backend reads it before the statuses).
 * - version null (Redis was down when the map loaded) → reload, to get a version to follow
 * - version ≤ map.version → already in the map → ignore
 * - version > map.version + 1 → a message was missed → reload
 * - otherwise apply (each message states the seats' new status; HELD by a hold carries its expiry)
 */
export function applySeatEvent(map: ShowSeatMap, event: SeatEventMessage): LiveUpdate {
  if (map.version === null) return { kind: 'resync' }
  if (event.version <= map.version) return { kind: 'ignore' }
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
