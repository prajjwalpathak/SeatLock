import { keepPreviousData, useInfiniteQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router'
import { catalogKeys, searchEvents } from '../api/catalog'
import { ErrorMessage } from '../components/ErrorMessage'
import { IntroBanner } from '../components/IntroBanner'
import { EventCard } from '../features/events/EventCard'
import { EventCardSkeleton } from '../features/events/EventCardSkeleton'
import { EventFilters } from '../features/events/EventFilters'
import { CATEGORY_LABELS, parseFilters, type EventFilterValues } from '../features/events/filters'
import { EVENT_CATEGORIES, type EventSearchParams } from '../types/api'

const PAGE_SIZE = 12

export function EventListPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const filters = parseFilters(searchParams)
  const params: EventSearchParams = { ...filters, limit: PAGE_SIZE }

  const query = useInfiniteQuery({
    queryKey: catalogKeys.eventList(params),
    queryFn: ({ pageParam, signal }) => searchEvents(params, pageParam, signal),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    placeholderData: keepPreviousData, // keep old results visible while new filters load
  })

  const events = query.data?.pages.flatMap((page) => page.items) ?? []

  // Filters live in the URL; replace (not push) so filtering doesn't flood browser history.
  function updateFilters(next: Partial<EventFilterValues>) {
    setSearchParams(
      (prev) => {
        const merged = new URLSearchParams(prev)
        for (const [key, value] of Object.entries(next)) {
          if (value) merged.set(key, value)
          else merged.delete(key)
        }
        return merged
      },
      { replace: true },
    )
  }

  return (
    <section className="space-y-6">
      <IntroBanner />

      <div className="flex items-baseline justify-between">
        <h1 className="text-3xl font-extrabold tracking-tight sm:text-4xl">What&rsquo;s on</h1>
        {query.data && (
          <span className="text-sm text-slate-500">
            {events.length} shown{query.hasNextPage ? ', more available' : ''}
          </span>
        )}
      </div>

      {/* Category chips (W8·D4): one tap instead of a dropdown; scroll sideways on phones. */}
      <div className="-mx-4 flex gap-2 overflow-x-auto px-4 pb-1 [scrollbar-width:none] sm:mx-0 sm:flex-wrap sm:px-0 [&::-webkit-scrollbar]:hidden" role="group" aria-label="Category">
        {[undefined, ...EVENT_CATEGORIES].map((category) => {
          const active = filters.category === category
          return (
            <button
              key={category ?? 'all'}
              type="button"
              aria-pressed={active}
              onClick={() => updateFilters({ category })}
              className={`shrink-0 rounded-full border px-4 py-1.5 text-sm font-semibold transition focus:outline-none focus:ring-2 focus:ring-indigo-300 ${
                active
                  ? 'border-indigo-600 bg-indigo-600 text-white'
                  : 'border-slate-300 bg-white text-slate-700 hover:border-slate-400'
              }`}
            >
              {category ? CATEGORY_LABELS[category] : 'All'}
            </button>
          )
        })}
      </div>

      <EventFilters values={filters} onChange={updateFilters} />

      {query.isPending ? (
        <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
          {Array.from({ length: 6 }, (_, i) => (
            <EventCardSkeleton key={i} />
          ))}
        </div>
      ) : query.isError && !query.data ? (
        <ErrorMessage error={query.error} onRetry={() => query.refetch()} />
      ) : events.length === 0 ? (
        <div className="rounded-xl border border-dashed border-slate-300 bg-white p-10 text-center">
          <p className="font-medium">No upcoming events match your filters.</p>
          <button
            type="button"
            onClick={() => updateFilters({ q: undefined, city: undefined, category: undefined })}
            className="mt-3 text-sm text-indigo-600 hover:underline"
          >
            Clear filters
          </button>
        </div>
      ) : (
        <>
          <div
            className={`grid gap-5 transition-opacity sm:grid-cols-2 lg:grid-cols-3 ${
              query.isPlaceholderData ? 'opacity-50' : ''
            }`}
          >
            {events.map((event) => (
              <EventCard key={event.id} event={event} />
            ))}
          </div>

          {query.isFetchNextPageError && (
            <ErrorMessage error={query.error} onRetry={() => query.fetchNextPage()} />
          )}

          {query.hasNextPage && (
            <div className="flex justify-center">
              <button
                type="button"
                onClick={() => query.fetchNextPage()}
                disabled={query.isFetchingNextPage}
                className="rounded-lg border border-slate-300 bg-white px-5 py-2 text-sm font-medium hover:bg-slate-50 disabled:cursor-wait disabled:opacity-60"
              >
                {query.isFetchingNextPage ? 'Loading…' : 'Load more'}
              </button>
            </div>
          )}
        </>
      )}
    </section>
  )
}
