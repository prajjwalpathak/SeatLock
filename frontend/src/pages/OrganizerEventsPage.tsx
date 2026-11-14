import { useQuery } from '@tanstack/react-query'
import { getMyEvents, organizerKeys } from '../api/organizer'
import { ErrorMessage } from '../components/ErrorMessage'
import { CATEGORY_LABELS } from '../features/events/filters'
import { formatShowTime } from '../lib/format'
import type { EventStatus } from '../types/api'

const STATUS_STYLES: Record<EventStatus, string> = {
  PUBLISHED: 'bg-emerald-50 text-emerald-700 ring-emerald-200',
  DRAFT: 'bg-amber-50 text-amber-700 ring-amber-200',
  CANCELLED: 'bg-slate-100 text-slate-600 ring-slate-200',
}

/** The organizer's own events (drafts included). "Create show" arrives on W2·D4. */
export function OrganizerEventsPage() {
  const query = useQuery({ queryKey: organizerKeys.events, queryFn: ({ signal }) => getMyEvents(signal) })

  return (
    <section className="space-y-4">
      <title>My events · SeatLock</title>
      <h1 className="text-2xl font-semibold">My events</h1>

      {query.isPending && <p className="text-sm text-slate-500">Loading your events…</p>}
      {query.isError && <ErrorMessage error={query.error} onRetry={() => query.refetch()} />}
      {query.isSuccess && query.data.length === 0 && (
        <p className="rounded-xl border border-dashed border-slate-300 bg-white p-8 text-center text-slate-600">
          You don’t have any events yet.
        </p>
      )}
      {query.isSuccess && query.data.length > 0 && (
        <div className="overflow-x-auto rounded-xl border border-slate-200 bg-white">
          <table className="w-full text-left text-sm">
            <thead className="border-b border-slate-200 bg-slate-50 text-xs uppercase text-slate-500">
              <tr>
                <th className="px-4 py-3">Event</th>
                <th className="px-4 py-3">Status</th>
                <th className="px-4 py-3 text-right">Shows</th>
                <th className="px-4 py-3">Next show</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {query.data.map((event) => (
                <tr key={event.id}>
                  <td className="px-4 py-3">
                    <div className="font-medium">{event.title}</div>
                    <div className="text-xs text-slate-500">
                      {CATEGORY_LABELS[event.category]} · {event.language}
                    </div>
                  </td>
                  <td className="px-4 py-3">
                    <span className={`rounded-full px-2 py-0.5 text-xs font-medium ring-1 ${STATUS_STYLES[event.status]}`}>
                      {event.status.toLowerCase()}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-right tabular-nums">
                    {event.upcomingShowCount} upcoming / {event.showCount}
                  </td>
                  <td className="px-4 py-3">{event.nextShowAt ? formatShowTime(event.nextShowAt) : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
