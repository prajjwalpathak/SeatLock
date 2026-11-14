import { useMutation } from '@tanstack/react-query'
import type { FormEvent } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router'
import { login, register, type RegisterInput } from '../api/auth'
import { AuthCard } from '../features/auth/AuthCard'
import { FieldError } from '../features/auth/FieldError'
import { fieldError, formError } from '../features/auth/formErrors'
import { GoogleButton } from '../features/auth/GoogleButton'
import { safeRedirectPath, type FromState } from '../features/auth/redirect'
import { fieldClass, primaryButtonClass } from '../features/auth/styles'
import { useAuth } from '../features/auth/useAuth'

export function RegisterPage() {
  const { status, signIn } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const redirectTo = safeRedirectPath((location.state as FromState | null)?.from)

  const mutation = useMutation({
    // Register, then sign straight in with the same credentials
    mutationFn: async (input: RegisterInput) => {
      await register(input)
      return signIn(await login(input.email, input.password))
    },
    onSuccess: () => navigate(redirectTo, { replace: true }),
  })

  if (status === 'signedIn') {
    return <Navigate to={redirectTo} replace />
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    mutation.mutate({
      email: String(form.get('email') ?? '').trim(),
      displayName: String(form.get('displayName') ?? '').trim(),
      password: String(form.get('password') ?? ''),
    })
  }

  const errors = {
    email: fieldError(mutation.error, 'email'),
    displayName: fieldError(mutation.error, 'displayName'),
    password: fieldError(mutation.error, 'password'),
  }
  const message = formError(mutation.error)

  return (
    <AuthCard
      title="Create your account"
      subtitle={
        <>
          Already have one?{' '}
          <Link to="/login" state={location.state} className="font-medium text-indigo-600 hover:underline">
            Sign in
          </Link>
        </>
      }
    >
      <form onSubmit={handleSubmit} noValidate className="space-y-4">
        <label className="block">
          <span className="text-xs font-medium text-slate-600">Name</span>
          <input
            name="displayName"
            autoComplete="name"
            required
            maxLength={100}
            aria-invalid={Boolean(errors.displayName)}
            aria-describedby="register-name-error"
            className={fieldClass}
          />
          <FieldError id="register-name-error" message={errors.displayName} />
        </label>
        <label className="block">
          <span className="text-xs font-medium text-slate-600">E-mail</span>
          <input
            name="email"
            type="email"
            autoComplete="email"
            required
            maxLength={254}
            aria-invalid={Boolean(errors.email)}
            aria-describedby="register-email-error"
            className={fieldClass}
          />
          <FieldError id="register-email-error" message={errors.email} />
        </label>
        <label className="block">
          <span className="text-xs font-medium text-slate-600">Password</span>
          <input
            name="password"
            type="password"
            autoComplete="new-password"
            required
            minLength={8}
            maxLength={72}
            aria-invalid={Boolean(errors.password)}
            aria-describedby="register-password-hint register-password-error"
            className={fieldClass}
          />
          <p id="register-password-hint" className="mt-1 text-xs text-slate-500">
            At least 8 characters. A long passphrase is best.
          </p>
          <FieldError id="register-password-error" message={errors.password} />
        </label>

        {message && (
          <p role="alert" className="text-sm text-red-700">
            {message}
          </p>
        )}
        <button type="submit" disabled={mutation.isPending} className={primaryButtonClass}>
          {mutation.isPending ? 'Creating account…' : 'Create account'}
        </button>
      </form>

      <div className="my-5 flex items-center gap-3 text-xs text-slate-400">
        <span className="h-px flex-1 bg-slate-200" /> or <span className="h-px flex-1 bg-slate-200" />
      </div>
      <GoogleButton redirectTo={redirectTo} />
    </AuthCard>
  )
}
