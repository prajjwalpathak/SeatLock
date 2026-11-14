import type { OrganizerEvent } from '../types/api'
import { USER_SCOPED_KEY } from './auth'
import { apiGet } from './client'

export const organizerKeys = {
  events: [...USER_SCOPED_KEY, 'organizer', 'events'] as const,
}

export function getMyEvents(signal?: AbortSignal): Promise<OrganizerEvent[]> {
  return apiGet<OrganizerEvent[]>('/api/v1/organizer/events', undefined, signal)
}
