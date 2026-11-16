import { useQuery } from '@tanstack/react-query'
import { apiGet } from '../api/client'
import type { ServerTime } from '../types/api'

/**
 * Countdowns must use the SERVER's clock: a laptop clock that is 2 minutes off would show a hold as
 * expired (or alive) when it isn't. One request estimates the offset, compensating half the round trip.
 */
async function measureOffset(signal?: AbortSignal): Promise<number> {
  const sent = Date.now()
  const { epochMillis } = await apiGet<ServerTime>('/api/v1/system/time', undefined, signal)
  const received = Date.now()
  return epochMillis - (sent + received) / 2
}

/** Server-minus-client offset in ms (0 until measured or if the call fails). Re-measured every 10 min. */
export function useServerClockOffset(): number {
  const { data } = useQuery({
    queryKey: ['system', 'clockOffset'],
    queryFn: ({ signal }) => measureOffset(signal),
    staleTime: 10 * 60_000,
    retry: 1,
  })
  return data ?? 0
}

/** Milliseconds from now until `isoUtc` on the server's clock (never negative). */
export function msUntil(isoUtc: string, offsetMs: number, clientNow: number = Date.now()): number {
  return Math.max(0, new Date(isoUtc).getTime() - (clientNow + offsetMs))
}

/** 125_000 ms → "2:05". */
export function formatCountdown(ms: number): string {
  const total = Math.ceil(ms / 1000)
  const m = Math.floor(total / 60)
  const s = total % 60
  return `${m}:${String(s).padStart(2, '0')}`
}
