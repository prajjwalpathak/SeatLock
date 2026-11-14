import { ApiError } from '../api/client'

type Props = {
  error: unknown
  onRetry?: () => void
}

/** Shows the server's message and the requestId users can quote when reporting a problem. */
export function ErrorMessage({ error, onRetry }: Props) {
  const apiError = error instanceof ApiError ? error : null
  const message = apiError?.message ?? 'Something went wrong. Please try again.'

  return (
    <div role="alert" className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-800">
      <p>{message}</p>
      {apiError?.requestId && (
        <p className="mt-1 text-xs text-red-600">
          Reference: <code className="font-mono">{apiError.requestId}</code>
        </p>
      )}
      {onRetry && (
        <button
          type="button"
          onClick={onRetry}
          className="mt-3 rounded-md border border-red-300 bg-white px-3 py-1 text-xs font-medium text-red-700 hover:bg-red-100"
        >
          Try again
        </button>
      )}
    </div>
  )
}
