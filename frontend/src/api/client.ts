import type { FieldViolation, ProblemDetail } from '../types/api'

export const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080').replace(/\/+$/, '')

/** Every failed API call becomes an ApiError carrying the server's problem+json details. */
export class ApiError extends Error {
  /** HTTP status; 0 means the request never got a response (server down, CORS blocked, offline). */
  readonly status: number
  /** Stable machine-readable code from the backend, e.g. NOT_FOUND, VALIDATION_FAILED. */
  readonly code: string
  /** Correlates with server logs; show it so users can quote it. */
  readonly requestId?: string
  readonly fieldErrors: FieldViolation[]

  // Explicit fields instead of constructor parameter properties (tsconfig: erasableSyntaxOnly).
  constructor(status: number, code: string, message: string, requestId?: string, fieldErrors: FieldViolation[] = []) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.requestId = requestId
    this.fieldErrors = fieldErrors
  }

  get isClientError(): boolean {
    return this.status >= 400 && this.status < 500
  }
}

type QueryValue = string | number | boolean | null | undefined
export type QueryParams = Record<string, QueryValue>

function buildUrl(path: string, params?: QueryParams): string {
  const url = new URL(API_BASE_URL + path)
  for (const [key, value] of Object.entries(params ?? {})) {
    if (value !== undefined && value !== null && value !== '') {
      url.searchParams.set(key, String(value))
    }
  }
  return url.toString()
}

async function toApiError(response: Response): Promise<ApiError> {
  const headerRequestId = response.headers.get('X-Request-Id') ?? undefined
  let problem: ProblemDetail = {}
  try {
    if (response.headers.get('Content-Type')?.includes('json')) {
      problem = (await response.json()) as ProblemDetail
    }
  } catch {
    // Non-JSON or malformed body (e.g. a proxy error page): fall back to the status line.
  }
  return new ApiError(
    response.status,
    problem.code ?? `HTTP_${response.status}`,
    problem.detail ?? problem.title ?? (response.statusText || 'Request failed'),
    problem.requestId ?? headerRequestId,
    problem.errors ?? [],
  )
}

/** GET a JSON resource. Throws ApiError on any failure; a cancelled request rethrows its AbortError. */
export async function apiGet<T>(path: string, params?: QueryParams, signal?: AbortSignal): Promise<T> {
  let response: Response
  try {
    response = await fetch(buildUrl(path, params), {
      headers: { Accept: 'application/json' },
      signal,
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw error // TanStack Query cancelled it (e.g. user navigated away): not a failure
    }
    throw new ApiError(0, 'NETWORK_ERROR', 'Cannot reach the SeatLock server. Check your connection and try again.')
  }
  if (!response.ok) {
    throw await toApiError(response)
  }
  return (await response.json()) as T
}
