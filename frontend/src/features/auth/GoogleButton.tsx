import { GOOGLE_SIGN_IN_URL } from '../../api/auth'
import { rememberPostLoginRedirect } from './redirect'
import { secondaryButtonClass } from './styles'

/**
 * Shown only when VITE_GOOGLE_SIGNIN=true (W8·D4): the Google OAuth app is in testing mode, so on the public site
 * every visitor who isn't a registered test user would hit Google's "access blocked" page. Locally: set it in
 * frontend/.env.local.
 */
const GOOGLE_SIGN_IN_ENABLED = import.meta.env.VITE_GOOGLE_SIGNIN === 'true'

/** A plain link: Google sign-in is a full-page redirect, not an API call. */
export function GoogleButton({ redirectTo }: { redirectTo: string }) {
  if (!GOOGLE_SIGN_IN_ENABLED) {
    return null
  }
  return (
    <a
      href={GOOGLE_SIGN_IN_URL}
      onClick={() => rememberPostLoginRedirect(redirectTo)}
      className={`${secondaryButtonClass} flex items-center justify-center gap-2`}
    >
      <span aria-hidden="true" className="font-bold text-indigo-600">
        G
      </span>
      Continue with Google
    </a>
  )
}
