import type { TokenResponse, User } from '../types/api'
import { API_BASE_URL, apiGet, apiPost } from './client'

/**
 * Every query holding the signed-in user's data lives under ['me', …], so signing out can drop
 * all of it at once (and the next user never sees the previous user's data).
 */
export const USER_SCOPED_KEY = ['me'] as const

export const authKeys = {
  me: ['me', 'profile'] as const,
}

export function login(email: string, password: string): Promise<TokenResponse> {
  return apiPost<TokenResponse>('/api/v1/auth/login', { email, password })
}

export type RegisterInput = {
  email: string
  password: string
  displayName: string
}

export function register(input: RegisterInput): Promise<User> {
  return apiPost<User>('/api/v1/auth/register', input)
}

/** Second half of Google sign-in: the one-time code from /auth/callback?code=… → our JWT. */
export function exchangeOAuthCode(code: string): Promise<TokenResponse> {
  return apiPost<TokenResponse>('/api/v1/auth/oauth/exchange', { code })
}

export function getMe(signal?: AbortSignal): Promise<User> {
  return apiGet<User>('/api/v1/me', undefined, signal)
}

/** Full-page navigation (not fetch): the browser goes to the API, then Google, then back to us. */
export const GOOGLE_SIGN_IN_URL = `${API_BASE_URL}/oauth2/authorization/google`
