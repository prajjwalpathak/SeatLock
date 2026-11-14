import { createContext } from 'react'
import type { TokenResponse, User } from '../../types/api'

export type AuthStatus = 'loading' | 'signedIn' | 'signedOut'

export type AuthContextValue = {
  status: AuthStatus
  user: User | null
  /** Stores the token and loads the user (/me). Resolves with the signed-in user. */
  signIn: (token: TokenResponse) => Promise<User>
  /** Forgets the token on this device. The JWT itself stays valid until it expires (stateless). */
  signOut: () => void
}

export const AuthContext = createContext<AuthContextValue | null>(null)
