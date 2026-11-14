import type { Role } from '../../types/api'

/** Mirrors the backend hierarchy ADMIN ⊃ ORGANIZER ⊃ USER. UI only: the server is the real check. */
const RANK: Record<Role, number> = { USER: 1, ORGANIZER: 2, ADMIN: 3 }

export function hasRole(userRole: Role | undefined, required: Role): boolean {
  return userRole !== undefined && RANK[userRole] >= RANK[required]
}

export const ROLE_LABELS: Record<Role, string> = {
  USER: 'User',
  ORGANIZER: 'Organizer',
  ADMIN: 'Admin',
}
