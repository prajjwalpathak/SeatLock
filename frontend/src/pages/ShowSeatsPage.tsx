import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { catalogKeys, getSeatMap } from '../api/catalog'
import { ApiError, hasCode } from '../api/client'
import { bookHold, createHold, getHold, holdKeys, newIdempotencyKey, releaseHold } from '../api/holds'
import { ErrorMessage } from '../components/ErrorMessage'
import { useAuth } from '../features/auth/useAuth'
import { HoldPanel } from '../features/seatmap/HoldPanel'
import { forgetHold, recallHold, rememberHold } from '../features/seatmap/holdStorage'
import { SeatLegend } from '../features/seatmap/SeatLegend'
import { SeatMap } from '../features/seatmap/SeatMap'
import { SeatMapSkeleton } from '../features/seatmap/SeatMapSkeleton'
import { MAX_SELECTION, seatCode } from '../features/seatmap/seats'
import { SelectionSummary } from '../features/seatmap/SelectionSummary'
import { formatClockTime, formatShowTime } from '../lib/format'
import { useServerClockOffset } from '../lib/serverClock'
import { useNow } from '../lib/useNow'
import type { Hold, SeatView, ShowSeatMap } from '../types/api'

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

/** Retry only what is safe and likely to succeed with the SAME Idempotency-Key. */
function retryHold(failures: number, error: unknown): boolean {
  if (failures >= 2) return false
  return (error instanceof ApiError && error.status === 0) || hasCode(error, 'HOLDS_UNAVAILABLE', 'IDEMPOTENCY_IN_PROGRESS')
}

function ShowSeatsView({ seatMap, updatedAt, refreshing, onRefresh }: ViewProps) {
  const showId = seatMap.showId
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { status: authStatus } = useAuth()
  const signedIn = authStatus === 'signedIn'
  const clockOffset = useServerClockOffset()
  const now = useNow(15_000)
  const started = new Date(seatMap.startsAt).getTime() <= now + clockOffset

  const [selectedIds, setSelectedIds] = useState<number[]>([])
  const [notice, setNotice] = useState<string | null>(null)
  const [hold, setHold] = useState<Hold | null>(null)
  const [blockedUntil, setBlockedUntil] = useState(0) // 429: no new attempts before this client time

  // Resume a hold remembered for this tab (F5). 404 = it expired or was released: forget it.
  const rememberedHoldId = signedIn && hold === null ? recallHold(showId) : null
  const resumed = useQuery({
    queryKey: holdKeys.hold(rememberedHoldId ?? 'none'),
    queryFn: async ({ signal }) => {
      try {
        return await getHold(rememberedHoldId!, signal)
      } catch (error) {
        if (hasCode(error, 'NOT_FOUND')) forgetHold(showId) // expired or released meanwhile
        throw error
      }
    },
    enabled: rememberedHoldId !== null,
    retry: false,
    staleTime: 0,
  })
  const activeHold = hold ?? (resumed.data && resumed.data.showId === showId ? resumed.data : null)

  const refreshMap = () => queryClient.invalidateQueries({ queryKey: catalogKeys.seatMap(showId) })
  const endHold = (message: string | null) => {
    forgetHold(showId)
    setHold(null)
    if (resumed.data) queryClient.removeQueries({ queryKey: holdKeys.hold(resumed.data.holdId) })
    setNotice(message)
    refreshMap()
  }

  // Selection is derived against the latest map: a seat someone else took since the last
  // refresh simply drops out (no effect + setState, so it can never lag a render behind).
  const byId = new Map(seatMap.seats.map((s) => [s.seatId, s]))
  const selectedSeats = selectedIds
    .map((id) => byId.get(id))
    .filter((s): s is SeatView => s !== undefined && s.status === 'AVAILABLE')
  const lostCount = selectedIds.length - selectedSeats.length
  const selectedSet = new Set(selectedSeats.map((s) => s.seatId))
  const mine = new Set(activeHold?.seats.map((s) => s.seatId) ?? [])

  const holdMutation = useMutation({
    mutationFn: ({ seatIds, key }: { seatIds: number[]; key: string }) => createHold(showId, seatIds, key),
    retry: retryHold, // re-runs with the same variables → the same Idempotency-Key
    retryDelay: 1000,
    onSuccess: (created) => {
      rememberHold(showId, created.holdId)
      setHold(created)
      setSelectedIds([])
      setNotice(null)
      refreshMap()
    },
    onError: async (error) => {
      if (hasCode(error, 'SEATS_UNAVAILABLE')) {
        const taken = (error.details.seatIds as number[] | undefined) ?? []
        const labels = taken.map((id) => byId.get(id)).filter(Boolean).map((s) => seatCode(s!))
        setNotice(`${labels.length ? labels.join(', ') : 'Some seats'} ${taken.length === 1 ? 'was' : 'were'} just taken. Pick other seats.`)
        refreshMap()
      } else if (hasCode(error, 'HOLD_EXISTS')) {
        const existing = await getHold(String(error.details.holdId)).catch(() => null)
        if (existing) {
          rememberHold(showId, existing.holdId)
          setHold(existing)
          setSelectedIds([])
          setNotice('You already hold seats for this show. Here they are.')
        } else {
          setNotice('You already hold seats for this show. Release them first.')
        }
      } else if (hasCode(error, 'RATE_LIMITED')) {
        const wait = error.retryAfterSeconds ?? 30
        setBlockedUntil(Date.now() + wait * 1000)
        setNotice(`Too many attempts. Please wait ${wait} seconds and try again.`)
      } else if (hasCode(error, 'SHOW_STARTED')) {
        setNotice('This show has already started.')
      } else if (error instanceof ApiError && error.status !== 401) {
        setNotice(error.message)
      }
    },
  })

  const releaseMutation = useMutation({
    mutationFn: (holdId: string) => releaseHold(holdId),
    onSettled: () => endHold('Seats released.'), // 404 (already expired) ends it just the same
  })

  const bookMutation = useMutation({
    mutationFn: (holdId: string) => bookHold(holdId),
    onSuccess: ({ booking }) => {
      forgetHold(showId)
      queryClient.setQueryData(holdKeys.booking(booking.publicId), booking)
      navigate(`/bookings/${booking.publicId}`)
    },
    onError: (error) => {
      if (hasCode(error, 'NOT_FOUND')) endHold('Your hold expired before checkout. Please pick seats again.')
      else if (hasCode(error, 'SEATS_UNAVAILABLE')) endHold('Some of your seats are no longer available. Please pick again.')
      else setNotice(error instanceof ApiError ? error.message : 'Could not start checkout. Please try again.')
    },
  })

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

  const rateLimited = blockedUntil > now
  const blockedReason = started
    ? 'This show has already started'
    : rateLimited
      ? 'Too many attempts, please wait'
      : undefined

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

      {started && (
        <p role="status" className="rounded-lg border border-slate-300 bg-slate-100 p-3 text-sm text-slate-700">
          This show has already started, so seats can no longer be held.
        </p>
      )}
      {!seatMap.holdsLive && (
        <p role="status" className="rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800">
          Live availability is temporarily unavailable, so some seats shown as available may already be on hold.
          You can still pick seats; we'll confirm them when you hold.
        </p>
      )}

      <SeatLegend />
      <SeatMap
        seatMap={seatMap}
        selected={selectedSet}
        onToggle={toggle}
        mine={mine}
        readOnly={started || activeHold !== null || holdMutation.isPending}
      />

      {lostCount > 0 && activeHold === null && (
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
        <p role="status" className="text-sm text-slate-700">
          {notice}
        </p>
      )}

      {activeHold ? (
        <HoldPanel
          hold={activeHold}
          clockOffsetMs={clockOffset}
          busy={releaseMutation.isPending || bookMutation.isPending}
          onRelease={() => releaseMutation.mutate(activeHold.holdId)}
          onProceed={() => bookMutation.mutate(activeHold.holdId)}
          onExpired={() => endHold('Your hold expired. The seats are available again.')}
        />
      ) : (
        <SelectionSummary
          seats={selectedSeats}
          signedIn={signedIn}
          holding={holdMutation.isPending}
          blockedReason={blockedReason}
          onHold={() =>
            holdMutation.mutate({ seatIds: selectedSeats.map((s) => s.seatId), key: newIdempotencyKey() })
          }
          onClear={() => {
            setSelectedIds([])
            setNotice(null)
          }}
        />
      )}
    </section>
  )
}
