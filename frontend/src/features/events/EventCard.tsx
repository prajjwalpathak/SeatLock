import { Link, useLocation } from 'react-router'
import { formatDuration, formatPaise, formatShowTime } from '../../lib/format'
import type { EventSummary } from '../../types/api'
import { CATEGORY_LABELS } from './filters'
import type { EventListState } from './navigation'
import { CATEGORY_POSTER } from './posters'

/** Poster-style card (W8·D4): gradient "artwork" with the title, then date, venue and price. */
export function EventCard({ event }: { event: EventSummary }) {
  const location = useLocation()
  const state: EventListState = { listSearch: location.search } // lets "back" restore the filters
  const poster = CATEGORY_POSTER[event.category]

  return (
    <Link
      to={`/events/${event.id}`}
      state={state}
      className="group block overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm transition duration-200 hover:-translate-y-0.5 hover:shadow-lg focus:outline-none focus:ring-2 focus:ring-indigo-400 motion-reduce:transition-none motion-reduce:hover:translate-y-0"
    >
      <div className={`relative flex aspect-[16/10] flex-col justify-between bg-gradient-to-br p-4 text-white ${poster.gradient}`}>
        <div className="flex items-center justify-between text-xs">
          <span className="rounded-full bg-black/25 px-2.5 py-1 font-semibold backdrop-blur-sm">
            {CATEGORY_LABELS[event.category]}
          </span>
          <span className="rounded-full bg-black/25 px-2.5 py-1 backdrop-blur-sm">
            {event.language} · {formatDuration(event.durationMinutes)}
          </span>
        </div>
        <span aria-hidden="true" className="pointer-events-none absolute -bottom-3 right-3 text-7xl opacity-30 transition group-hover:scale-110 motion-reduce:transition-none">
          {poster.glyph}
        </span>
        <h2 className="relative max-w-[85%] text-xl font-extrabold leading-tight tracking-tight drop-shadow-sm sm:text-2xl">
          {event.title}
        </h2>
      </div>
      <div className="space-y-0.5 p-4">
        <p className="text-sm font-medium text-slate-800">{formatShowTime(event.nextShowAt)}</p>
        <p className="text-sm text-slate-500">
          {event.nextShowVenue}, {event.nextShowCity}
        </p>
        <p className="pt-2 text-sm text-slate-600">
          from <span className="font-bold text-slate-900">{formatPaise(event.fromPricePaise)}</span>
        </p>
      </div>
    </Link>
  )
}
