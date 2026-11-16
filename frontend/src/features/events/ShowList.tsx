import { Link } from 'react-router'
import { formatPaise, formatShowTime } from '../../lib/format'
import type { ShowSummary } from '../../types/api'

export function ShowList({ shows }: { shows: ShowSummary[] }) {
  if (shows.length === 0) {
    return (
      <p className="rounded-xl border border-dashed border-slate-300 bg-white p-6 text-center text-slate-600">
        No upcoming shows right now.
      </p>
    )
  }

  return (
    <ul className="divide-y divide-slate-200 rounded-xl border border-slate-200 bg-white shadow-sm">
      {shows.map((show) => (
        <li key={show.id} className="flex flex-col gap-3 p-4 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <p className="font-medium">{formatShowTime(show.startsAt)}</p>
            <p className="text-sm text-slate-500">
              {show.venueName}, {show.city}
            </p>
          </div>
          <div className="flex items-center gap-4">
            <span className="text-sm text-slate-600">
              from <span className="font-semibold text-slate-900">{formatPaise(show.fromPricePaise)}</span>
            </span>
            <Link
              to={`/shows/${show.id}`}
              className="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700"
            >
              Select seats
            </Link>
          </div>
        </li>
      ))}
    </ul>
  )
}
