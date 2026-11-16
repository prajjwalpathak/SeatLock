import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { catalogKeys, getSeatMap } from '../api/catalog'
import { ApiError } from '../api/client'
import { ErrorMessage } from '../components/ErrorMessage'
import { SeatLegend } from '../features/seatmap/SeatLegend'
import { SeatMap } from '../features/seatmap/SeatMap'
import { SeatMapSkeleton } from '../features/seatmap/SeatMapSkeleton'
import { MAX_SELECTION } from '../features/seatmap/seats'
import { SelectionSummary } from '../features/seatmap/SelectionSummary'
import { formatClockTime, formatShowTime } from '../lib/format'
import type { SeatView, ShowSeatMap } from '../types/api'

export function ShowSeatsPage() {
  const { showId: rawId } = useParams()
  const showId = Number(rawId)
  const validId = Number.isInteger(showId) && showId > 0

  const query = useQuery({
    queryKey: catalogKeys.seatMap(showId),
    queryFn: ({ signal }) => getSeatMap(showId, signal),
    enabled: validId,
    // Live statuses: never serve a cached map; refetch when the tab regains focus.
    // Push updates over WebSockets replace this on W5.
    staleTime: 0,
    refetchOnWindowFocus: true,
  })

  // Unknown, cancelled and unpublished shows are all 404 on the backend.
  const notFound = !validId || (query.error instanceof ApiError && query.error.code === 'NOT_FOUND')

  if (notFound) {
    return (
      <div className="rounded-xl border border-dashed border-slate-300 bg-white p-10 text-center">
        <title>Show not found · SeatLock</title>
        <h1 className="text-xl font-semibold">Show not found</h1>
        <p className="mt-2 text-slate-600">It may have been cancelled, or the link is wrong.</p>
        <Link to="/events" className="mt-4 inline-block text-sm text-indigo-600 hover:underline">
          Browse events
        </Link>
      </div>
    )
  }
  if (query.isPending) return <SeatMapSkeleton />
  if (query.isError) return <ErrorMessage error={query.error} onRetry={() => query.refetch()} />

  return (
    <ShowSeatsView
      seatMap={query.data}
      updatedAt={query.dataUpdatedAt}
      refreshing={query.isFetching}
      onRefresh={() => query.refetch()}
    />
  )
}

type ViewProps = {
  seatMap: ShowSeatMap
  updatedAt: number
  refreshing: boolean
  onRefresh: () => void
}

function ShowSeatsView({ seatMap, updatedAt, refreshing, onRefresh }: ViewProps) {
  const [selectedIds, setSelectedIds] = useState<number[]>([])
  const [notice, setNotice] = useState<string | null>(null)

  // Selection is derived against the latest map: a seat someone else took since the last
  // refresh simply drops out (no effect + setState, so it can never lag a render behind).
  const byId = new Map(seatMap.seats.map((s) => [s.seatId, s]))
  const selectedSeats = selectedIds
    .map((id) => byId.get(id))
    .filter((s): s is SeatView => s !== undefined && s.status === 'AVAILABLE')
  const lostCount = selectedIds.length - selectedSeats.length
  const selectedSet = new Set(selectedSeats.map((s) => s.seatId))

  function toggle(seat: SeatView) {
    const current = selectedSeats.map((s) => s.seatId)
    if (selectedSet.has(seat.seatId)) {
      setSelectedIds(current.filter((id) => id !== seat.seatId))
      setNotice(null)
    } else if (current.length >= MAX_SELECTION) {
      setNotice(`You can select up to ${MAX_SELECTION} seats at a time.`)
    } else {
      setSelectedIds([...current, seat.seatId])
      setNotice(null)
    }
  }

  return (
    <section className="space-y-5">
      <title>{`Seats · ${seatMap.eventTitle} · SeatLock`}</title>

      <Link to={`/events/${seatMap.eventId}`} className="inline-block text-sm text-indigo-600 hover:underline">
        ← {seatMap.eventTitle}
      </Link>

      <header className="flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Choose your seats</h1>
          <p className="mt-1 text-sm text-slate-500">
            {formatShowTime(seatMap.startsAt)} · {seatMap.venue.name}, {seatMap.venue.city}
          </p>
        </div>
        <div className="flex items-center gap-3 text-xs text-slate-500">
          <span>Updated {formatClockTime(updatedAt)}</span>
          <button
            type="button"
            onClick={onRefresh}
            disabled={refreshing}
            className="rounded-md border border-slate-300 bg-white px-3 py-1 font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50"
          >
            {refreshing ? 'Refreshing…' : 'Refresh'}
          </button>
        </div>
      </header>

      {!seatMap.holdsLive && (
        <p role="status" className="rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800">
          Live availability is temporarily unavailable, so some seats shown as available may already be on hold.
          You can still pick seats; we'll confirm them when you hold.
        </p>
      )}

      <SeatLegend />
      <SeatMap seatMap={seatMap} selected={selectedSet} onToggle={toggle} />

      {lostCount > 0 && (
        <p role="alert" className="flex items-center justify-between gap-3 rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800">
          {lostCount === 1 ? 'A seat you picked was' : `${lostCount} seats you picked were`} just taken by someone
          else and removed from your selection.
          <button
            type="button"
            onClick={() => setSelectedIds(selectedSeats.map((s) => s.seatId))}
            className="rounded-md border border-amber-300 bg-white px-3 py-1 text-xs font-medium"
          >
            OK
          </button>
        </p>
      )}
      {notice && (
        <p role="status" className="text-sm text-slate-600">
          {notice}
        </p>
      )}

      <SelectionSummary
        seats={selectedSeats}
        onClear={() => {
          setSelectedIds([])
          setNotice(null)
        }}
      />
    </section>
  )
}
