/** Inline message under an input; link it with aria-describedby. */
export function FieldError({ id, message }: { id: string; message?: string }) {
  if (!message) return null
  return (
    <p id={id} className="mt-1 text-xs text-red-600">
      {message}
    </p>
  )
}
