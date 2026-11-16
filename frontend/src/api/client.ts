import { clearToken, readToken } from '../lib/tokenStorage'
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
  /** Extra machine-readable fields of the problem body, e.g. seatIds (409), holdId (HOLD_EXISTS). */
  readonly details: Record<string, unknown>
  /** Seconds from the Retry-After header (429, 503), if the server sent one. */
  readonly retryAfterSeconds?: number

  // Explicit fields instead of constructor parameter properties (tsconfig: erasableSyntaxOnly).
  constructor(
    status: number,
    code: string,
    message: string,
    requestId?: string,
    fieldErrors: FieldViolation[] = [],
    details: Record<string, unknown> = {},
    retryAfterSeconds?: number,
  ) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.requestId = requestId
    this.fieldErrors = fieldErrors
    this.details = details
    this.retryAfterSeconds = retryAfterSeconds
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

const STANDARD_PROBLEM_FIELDS = new Set(['type', 'title', 'status', 'detail', 'instance', 'code', 'requestId', 'errors'])

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
  const retryAfter = Number(response.headers.get('Retry-After'))
  const { title, detail, code, requestId, errors } = problem
  // Everything that isn't a standard ProblemDetail field is an endpoint-specific detail (seatIds, holdId, …).
  const details = Object.fromEntries(Object.entries(problem).filter(([key]) => !STANDARD_PROBLEM_FIELDS.has(key)))
  return new ApiError(
    response.status,
    code ?? `HTTP_${response.status}`,
    detail ?? title ?? (response.statusText || 'Request failed'),
    requestId ?? headerRequestId,
    errors ?? [],
    details,
    Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : undefined,
  )
}

/** Called when the server rejects our token (expired/invalid). AuthProvider signs the user out. */
let tokenRejectedListener: (() => void) | null = null

export function setTokenRejectedListener(listener: (() => void) | null): void {
  tokenRejectedListener = listener
}

type RequestOptions = {
  params?: QueryParams
  body?: unknown
  signal?: AbortSignal
  headers?: Record<string, string>
}

/** A JSON response together with its HTTP status (e.g. to tell 201 Created from 200 OK). */
export type ApiResponse<T> = { status: number; data: T }

async function send(method: string, path: string, options: RequestOptions, token: string | null): Promise<Response> {
  const headers: Record<string, string> = { Accept: 'application/json', ...options.headers }
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'
  if (token) headers.Authorization = `Bearer ${token}`
  try {
    return await fetch(buildUrl(path, options.params), {
      method,
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal,
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw error // TanStack Query cancelled it (e.g. user navigated away): not a failure
    }
    throw new ApiError(0, 'NETWORK_ERROR', 'Cannot reach the SeatLock server. Check your connection and try again.')
  }
}

/**
 * Sends the stored token (if any). If the server says INVALID_TOKEN, the token is dropped, the user
 * is signed out, and the request is retried once without it, so public pages keep working.
 */
async function request<T>(method: string, path: string, options: RequestOptions = {}): Promise<ApiResponse<T>> {
  const token = readToken()?.accessToken ?? null
  let response = await send(method, path, options, token)

  if (!response.ok && response.status === 401 && token) {
    const error = await toApiError(response)
    if (error.code !== 'INVALID_TOKEN') throw error
    clearToken()
    tokenRejectedListener?.()
    response = await send(method, path, options, null)
  }
  if (!response.ok) {
    throw await toApiError(response)
  }
  if (response.status === 204) {
    return { status: 204, data: undefined as T }
  }
  return { status: response.status, data: (await response.json()) as T }
}

/** GET a JSON resource. Throws ApiError on any failure; a cancelled request rethrows its AbortError. */
export async function apiGet<T>(path: string, params?: QueryParams, signal?: AbortSignal): Promise<T> {
  return (await request<T>('GET', path, { params, signal })).data
}

/** POST a JSON body and read a JSON response. Throws ApiError on any failure. */
export async function apiPost<T>(path: string, body: unknown, signal?: AbortSignal): Promise<T> {
  return (await request<T>('POST', path, { body, signal })).data
}

/** POST with extra headers (e.g. Idempotency-Key); returns status + body. Body may be omitted. */
export function apiPostWithStatus<T>(
  path: string,
  body?: unknown,
  headers?: Record<string, string>,
  signal?: AbortSignal,
): Promise<ApiResponse<T>> {
  return request<T>('POST', path, { body, headers, signal })
}

/** DELETE a resource (expects 204). Throws ApiError on any failure. */
export async function apiDelete(path: string, signal?: AbortSignal): Promise<void> {
  await request<void>('DELETE', path, { signal })
}

/** True if `error` is an ApiError with one of these codes. */
export function hasCode(error: unknown, ...codes: string[]): error is ApiError {
  return error instanceof ApiError && codes.includes(error.code)
}
