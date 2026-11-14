import { EVENT_CATEGORIES, type EventCategory } from '../../types/api'

/** Filters for the event list. They live in the URL query string, so links are shareable. */
export type EventFilterValues = {
  q?: string
  city?: string
  category?: EventCategory
}

/** Cities of the seeded venues. TODO: replace with a /api/v1/cities endpoint. */
export const CITIES = ['Bengaluru', 'Delhi', 'Mumbai', 'Pune'] as const

export const CATEGORY_LABELS: Record<EventCategory, string> = {
  MOVIE: 'Movies',
  CONCERT: 'Concerts',
  COMEDY: 'Comedy',
  THEATRE: 'Theatre',
  SPORTS: 'Sports',
  WORKSHOP: 'Workshops',
}

function isEventCategory(value: string | null): value is EventCategory {
  return value !== null && (EVENT_CATEGORIES as readonly string[]).includes(value)
}

/** Reads filters from the URL, ignoring blank or invalid values. */
export function parseFilters(searchParams: URLSearchParams): EventFilterValues {
  const q = searchParams.get('q')?.trim()
  const city = searchParams.get('city')?.trim()
  const category = searchParams.get('category')
  return {
    q: q || undefined,
    city: city || undefined,
    category: isEventCategory(category) ? category : undefined,
  }
}
