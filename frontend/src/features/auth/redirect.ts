/** Where to go after signing in. Only same-app paths: never "//evil.com" or "https://…". */
export function safeRedirectPath(value: unknown, fallback = '/events'): string {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//') || value.startsWith('/\\')) {
    return fallback
  }
  if (value.startsWith('/login') || value.startsWith('/register') || value.startsWith('/auth/')) {
    return fallback // don't bounce back into the sign-in screens
  }
  return value
}

/** Google sign-in leaves the SPA, so the "come back here" path is parked in sessionStorage. */
const POST_LOGIN_KEY = 'seatlock.postLoginRedirect'

export function rememberPostLoginRedirect(path: string): void {
  try {
    sessionStorage.setItem(POST_LOGIN_KEY, safeRedirectPath(path))
  } catch {
    // storage unavailable: we'll land on the default page
  }
}

/** Read without removing: safe to call during render (React may render twice in dev). */
export function peekPostLoginRedirect(): string {
  try {
    return safeRedirectPath(sessionStorage.getItem(POST_LOGIN_KEY))
  } catch {
    return safeRedirectPath(null)
  }
}

export function clearPostLoginRedirect(): void {
  try {
    sessionStorage.removeItem(POST_LOGIN_KEY)
  } catch {
    // nothing to clear
  }
}

/** Shape of router state when a protected route sends you to /login. */
export type FromState = { from?: string }
