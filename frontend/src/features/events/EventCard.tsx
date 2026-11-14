import { Link, useLocation } from 'react-router'
import { formatDuration, formatPaise, formatShowTime } from '../../lib/format'
import type { EventSummary } from '../../types/api'
import { CATEGORY_LABELS } from './filters'
import type { EventListState } from './navigation'

export function EventCard({ event }: { event: EventSummary }) {
  const location = useLocation()
  const state: EventListState = { listSearch: location.search } // lets "back" restore the filters

  return (
    <Link
      to={`/events/${event.id}`}
      state={state}
      className="group block rounded-xl border border-slate-200 bg-white p-4 shadow-sm transition hover:border-indigo-300 hover:shadow-md focus:outline-none focus:ring-2 focus:ring-indigo-300"
    >
      <div className="flex items-center justify-between gap-2 text-xs">
        <span className="rounded-full bg-indigo-50 px-2 py-0.5 font-medium text-indigo-700">
          {CATEGORY_LABELS[event.category]}
        </span>
        <span className="text-slate-500">
          {event.language} · {formatDuration(event.durationMinutes)}
        </span>
      </div>
      <h2 className="mt-3 text-lg font-semibold leading-snug group-hover:text-indigo-700">{event.title}</h2>
      <p className="mt-2 text-sm text-slate-700">{formatShowTime(event.nextShowAt)}</p>
      <p className="text-sm text-slate-500">
        {event.nextShowVenue}, {event.nextShowCity}
      </p>
      <p className="mt-3 text-sm text-slate-600">
        from <span className="font-semibold text-slate-900">{formatPaise(event.fromPricePaise)}</span>
      </p>
    </Link>
  )
}
