import { useQuery } from '@tanstack/react-query'
import { Link, useLocation, useParams } from 'react-router'
import { catalogKeys, getEvent } from '../api/catalog'
import { ApiError } from '../api/client'
import { ErrorMessage } from '../components/ErrorMessage'
import { EventDetailSkeleton } from '../features/events/EventDetailSkeleton'
import { CATEGORY_LABELS } from '../features/events/filters'
import { eventListPathFrom } from '../features/events/navigation'
import { ShowList } from '../features/events/ShowList'
import { formatDuration } from '../lib/format'
import type { EventDetail } from '../types/api'

export function EventDetailPage() {
  const { eventId: rawId } = useParams()
  const location = useLocation()
  const eventId = Number(rawId)
  const validId = Number.isInteger(eventId) && eventId > 0
  const backTo = eventListPathFrom(location.state)

  const query = useQuery({
    queryKey: catalogKeys.event(eventId),
    queryFn: ({ signal }) => getEvent(eventId, signal),
    enabled: validId, // never call the API for /events/abc
  })

  // Unknown and draft events are both 404 on the backend (drafts are hidden on purpose).
  const notFound = !validId || (query.error instanceof ApiError && query.error.code === 'NOT_FOUND')

  return (
    <section className="space-y-6">
      <Link to={backTo} className="inline-block text-sm text-indigo-600 hover:underline">
        ← All events
      </Link>

      {notFound ? (
        <div className="rounded-xl border border-dashed border-slate-300 bg-white p-10 text-center">
          <title>Event not found · SeatLock</title>
          <h1 className="text-xl font-semibold">Event not found</h1>
          <p className="mt-2 text-slate-600">It may have ended, or the link is wrong.</p>
        </div>
      ) : query.isPending ? (
        <EventDetailSkeleton />
      ) : query.isError ? (
        <ErrorMessage error={query.error} onRetry={() => query.refetch()} />
      ) : (
        <EventDetailView event={query.data} />
      )}
    </section>
  )
}

function EventDetailView({ event }: { event: EventDetail }) {
  return (
    <article className="space-y-6">
      {/* React 19 hoists <title> into <head>. */}
      <title>{`${event.title} · SeatLock`}</title>

      <header>
        <span className="rounded-full bg-indigo-50 px-2 py-0.5 text-xs font-medium text-indigo-700">
          {CATEGORY_LABELS[event.category]}
        </span>
        <h1 className="mt-3 text-3xl font-bold tracking-tight">{event.title}</h1>
        <p className="mt-1 text-sm text-slate-500">
          {event.language} · {formatDuration(event.durationMinutes)}
        </p>
      </header>

      <p className="max-w-3xl whitespace-pre-line leading-relaxed text-slate-700">{event.description}</p>

      <div>
        <h2 className="mb-3 text-lg font-semibold">Upcoming shows</h2>
        <ShowList shows={event.upcomingShows} />
      </div>
    </article>
  )
}
