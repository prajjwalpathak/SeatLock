/**
 * Where the access token lives between page loads. localStorage, because there is no refresh
 * token (decision #43) and the API is on another site (no cookies). Trade-off: readable by
 * scripts if an XSS bug ever ships, which is why the token expires and CSP comes on W8·D1.
 */
export const TOKEN_STORAGE_KEY = 'seatlock.auth'

/** Treat the token as expired a little early so we never send one that dies in flight. */
const EXPIRY_MARGIN_MS = 30_000

export type StoredToken = {
  accessToken: string
  /** Epoch millis. */
  expiresAt: number
}

export function readToken(): StoredToken | null {
  try {
    const raw = localStorage.getItem(TOKEN_STORAGE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as Partial<StoredToken>
    if (typeof parsed.accessToken !== 'string' || typeof parsed.expiresAt !== 'number') return null
    if (parsed.expiresAt - EXPIRY_MARGIN_MS <= Date.now()) {
      clearToken()
      return null
    }
    return { accessToken: parsed.accessToken, expiresAt: parsed.expiresAt }
  } catch {
    return null // storage disabled (private mode) or corrupted value
  }
}

export function writeToken(token: StoredToken): void {
  try {
    localStorage.setItem(TOKEN_STORAGE_KEY, JSON.stringify(token))
  } catch {
    // storage unavailable: the user stays signed in only for this page load
  }
}

export function clearToken(): void {
  try {
    localStorage.removeItem(TOKEN_STORAGE_KEY)
  } catch {
    // nothing to clear
  }
}

/** Milliseconds until the token should be treated as expired. */
export function msUntilExpiry(token: StoredToken): number {
  return Math.max(0, token.expiresAt - EXPIRY_MARGIN_MS - Date.now())
}
