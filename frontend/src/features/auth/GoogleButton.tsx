import { GOOGLE_SIGN_IN_URL } from '../../api/auth'
import { rememberPostLoginRedirect } from './redirect'
import { secondaryButtonClass } from './styles'

/** A plain link: Google sign-in is a full-page redirect, not an API call. */
export function GoogleButton({ redirectTo }: { redirectTo: string }) {
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
