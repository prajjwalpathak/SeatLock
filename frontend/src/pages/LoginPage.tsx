import { useMutation } from '@tanstack/react-query'
import type { FormEvent } from 'react'
import { Link, Navigate, useLocation, useNavigate, useSearchParams } from 'react-router'
import { login } from '../api/auth'
import { AuthCard } from '../features/auth/AuthCard'
import { DEMO_ACCOUNTS, DEMO_PASSWORD } from '../features/auth/demoAccounts'
import { FieldError } from '../features/auth/FieldError'
import { fieldError, formError } from '../features/auth/formErrors'
import { GoogleButton } from '../features/auth/GoogleButton'
import { safeRedirectPath, type FromState } from '../features/auth/redirect'
import { fieldClass, primaryButtonClass, secondaryButtonClass } from '../features/auth/styles'
import { useAuth } from '../features/auth/useAuth'

/** Messages for ?error=CODE set by the backend after a failed Google sign-in. */
const GOOGLE_ERRORS: Record<string, string> = {
  GOOGLE_EMAIL_NOT_VERIFIED: 'Your Google e-mail address is not verified, so we can’t use it to sign you in.',
  GOOGLE_NO_EMAIL: 'Google didn’t share an e-mail address. Please allow e-mail access or sign in another way.',
  GOOGLE_SIGN_IN_FAILED: 'Google sign-in didn’t complete. Please try again.',
}

export function LoginPage() {
  const { status, signIn } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [searchParams] = useSearchParams()
  const redirectTo = safeRedirectPath((location.state as FromState | null)?.from)
  const googleError = searchParams.get('error')

  const mutation = useMutation({
    mutationFn: async ({ email, password }: { email: string; password: string }) => signIn(await login(email, password)),
    onSuccess: () => navigate(redirectTo, { replace: true }),
  })

  if (status === 'signedIn') {
    return <Navigate to={redirectTo} replace />
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    mutation.mutate({ email: String(form.get('email') ?? '').trim(), password: String(form.get('password') ?? '') })
  }

  const emailError = fieldError(mutation.error, 'email')
  const passwordError = fieldError(mutation.error, 'password')
  const message = formError(mutation.error)

  return (
    <AuthCard
      title="Sign in"
      subtitle={
        <>
          New here?{' '}
          <Link to="/register" state={location.state} className="font-medium text-indigo-600 hover:underline">
            Create an account
          </Link>
        </>
      }
    >
      {googleError && !mutation.isPending && !mutation.error && (
        <p role="alert" className="mb-4 rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800">
          {GOOGLE_ERRORS[googleError] ?? GOOGLE_ERRORS.GOOGLE_SIGN_IN_FAILED}
        </p>
      )}

      <form onSubmit={handleSubmit} noValidate className="space-y-4">
        <label className="block">
          <span className="text-xs font-medium text-slate-600">E-mail</span>
          <input
            name="email"
            type="email"
            autoComplete="email"
            required
            maxLength={254}
            aria-invalid={Boolean(emailError)}
            aria-describedby="login-email-error"
            className={fieldClass}
          />
          <FieldError id="login-email-error" message={emailError} />
        </label>
        <label className="block">
          <span className="text-xs font-medium text-slate-600">Password</span>
          <input
            name="password"
            type="password"
            autoComplete="current-password"
            required
            maxLength={256}
            aria-invalid={Boolean(passwordError)}
            aria-describedby="login-password-error"
            className={fieldClass}
          />
          <FieldError id="login-password-error" message={passwordError} />
        </label>

        {message && (
          <p role="alert" className="text-sm text-red-700">
            {message}
          </p>
        )}
        <button type="submit" disabled={mutation.isPending} className={primaryButtonClass}>
          {mutation.isPending ? 'Signing in…' : 'Sign in'}
        </button>
      </form>

      <div className="my-5 flex items-center gap-3 text-xs text-slate-400">
        <span className="h-px flex-1 bg-slate-200" /> or <span className="h-px flex-1 bg-slate-200" />
      </div>

      <div className="space-y-2">
        <GoogleButton redirectTo={redirectTo} />
        {DEMO_ACCOUNTS.map((account) => (
          <button
            key={account.email}
            type="button"
            disabled={mutation.isPending}
            onClick={() => mutation.mutate({ email: account.email, password: DEMO_PASSWORD })}
            className={secondaryButtonClass}
          >
            {account.label}
          </button>
        ))}
        <p className="pt-1 text-center text-xs text-slate-500">
          Demo password for both: <code className="font-mono">{DEMO_PASSWORD}</code>
        </p>
      </div>
    </AuthCard>
  )
}
