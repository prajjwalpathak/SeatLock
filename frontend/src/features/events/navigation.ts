/** Router state an event card passes to the detail page, so "back" can restore the list's filters. */
export type EventListState = { listSearch: string }

/** The event list URL to return to: keeps the user's filters if they came from the list. */
export function eventListPathFrom(state: unknown): string {
  if (typeof state === 'object' && state !== null && 'listSearch' in state) {
    const search = (state as { listSearch: unknown }).listSearch
    if (typeof search === 'string' && search.startsWith('?')) {
      return `/events${search}`
    }
  }
  return '/events'
}
