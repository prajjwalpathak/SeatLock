interface ImportMetaEnv {
  /** Base URL of the SeatLock API, e.g. http://localhost:8080 (no trailing slash). */
  readonly VITE_API_BASE_URL?: string
  /** "true" shows "Continue with Google" (only test users of the Google OAuth app can use it). */
  readonly VITE_GOOGLE_SIGNIN?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
