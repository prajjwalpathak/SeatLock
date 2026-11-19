import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useSyncExternalStore } from 'react'
import { catalogKeys } from '../../api/catalog'
import { getLiveStatus, subscribeLiveStatus, watchTopic, type LiveStatus } from '../../lib/stompClient'
import type { SeatEventMessage, ShowSeatMap } from '../../types/api'
import { applySeatEvent } from './liveSeats'

/**
 * Keeps the cached seat map of `showId` up to date from /topic/shows/{id}/seats.
 * Every (re)subscription reloads the map: subscribe first, then load, so nothing published in between
 * is missed (events the map already reflects are ignored by version).
 */
export function useLiveSeatMap(showId: number, enabled: boolean): LiveStatus {
  const queryClient = useQueryClient()

  useEffect(() => {
    if (!enabled) return
    const key = catalogKeys.seatMap(showId)
    const resync = () => void queryClient.invalidateQueries({ queryKey: key })

    return watchTopic(
      `/topic/shows/${showId}/seats`,
      (body) => {
        let event: SeatEventMessage
        try {
          event = JSON.parse(body) as SeatEventMessage
        } catch {
          return // not ours; a missed message is caught by the next version anyway
        }
        if (event.showId !== showId) return
        const map = queryClient.getQueryData<ShowSeatMap>(key)
        if (!map) return // first load still in flight: it reflects this event or the next one shows a gap
        const update = applySeatEvent(map, event)
        if (update.kind === 'apply') queryClient.setQueryData(key, update.map)
        else if (update.kind === 'resync') resync()
      },
      resync,
    )
  }, [showId, enabled, queryClient])

  return useSyncExternalStore(subscribeLiveStatus, getLiveStatus)
}
