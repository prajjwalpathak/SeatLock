interface ImportMetaEnv {
  /** Base URL of the SeatLock API, e.g. http://localhost:8080 (no trailing slash). */
  readonly VITE_API_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
