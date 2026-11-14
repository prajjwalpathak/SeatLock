import { ApiError } from '../../api/client'

/** The server's validation message for one field (from a 400 VALIDATION_FAILED), if any. */
export function fieldError(error: unknown, field: string): string | undefined {
  return error instanceof ApiError ? error.fieldErrors.find((f) => f.field === field)?.message : undefined
}

/** A form-level message: hidden when the problem is already shown next to the fields. */
export function formError(error: unknown): string | null {
  if (!error) return null
  if (error instanceof ApiError) {
    return error.code === 'VALIDATION_FAILED' ? 'Please fix the highlighted fields.' : error.message
  }
  return 'Something went wrong. Please try again.'
}
