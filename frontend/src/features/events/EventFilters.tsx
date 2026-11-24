import { useState, type FormEvent } from 'react'
import { CITIES, type EventFilterValues } from './filters'

type Props = {
  values: EventFilterValues
  onChange: (next: Partial<EventFilterValues>) => void
}

const fieldClass =
  'mt-1 w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm ' +
  'focus:border-indigo-500 focus:outline-none focus:ring-2 focus:ring-indigo-200'

export function EventFilters({ values, onChange }: Props) {
  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const q = String(new FormData(event.currentTarget).get('q') ?? '').trim()
    onChange({ q: q || undefined })
  }

  const hasFilters = Boolean(values.q || values.city || values.category)
  // W8·D4 audit: on phones the full form filled the first screen. City starts hidden there (always shown from `sm`
  // up, and whenever it is set). Category is chosen with the chips above the form.
  const [moreOpen, setMoreOpen] = useState(false)
  const showMore = moreOpen || Boolean(values.city)
  const moreClass = `${showMore ? 'block' : 'hidden'} sm:block sm:w-40`

  return (
    <form
      role="search"
      onSubmit={handleSubmit}
      className="flex flex-col gap-3 rounded-2xl border border-slate-200 bg-white p-4 shadow-sm sm:flex-row sm:items-end"
    >
      <label className="flex-1">
        <span className="text-xs font-medium text-slate-600">Search</span>
        {/* Uncontrolled + keyed by the URL value: Back/Forward resets it without effects. */}
        <input
          key={values.q ?? ''}
          name="q"
          type="search"
          defaultValue={values.q ?? ''}
          maxLength={100}
          placeholder="Try “comedy”, “kites” or “qawwali”"
          className={fieldClass}
        />
      </label>

      <label className={moreClass}>
        <span className="text-xs font-medium text-slate-600">City</span>
        <select
          value={values.city ?? ''}
          onChange={(e) => onChange({ city: e.target.value || undefined })}
          className={fieldClass}
        >
          <option value="">All cities</option>
          {CITIES.map((city) => (
            <option key={city} value={city}>
              {city}
            </option>
          ))}
        </select>
      </label>


      <div className="flex gap-2">
        <button
          type="submit"
          className="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700 focus:outline-none focus:ring-2 focus:ring-indigo-300"
        >
          Search
        </button>
        {!showMore && (
          <button
            type="button"
            onClick={() => setMoreOpen(true)}
            aria-expanded="false"
            className="rounded-lg border border-slate-300 px-4 py-2 text-sm text-slate-700 hover:bg-slate-50 focus:outline-none focus:ring-2 focus:ring-indigo-200 sm:hidden"
          >
            City
          </button>
        )}
        {hasFilters && (
          <button
            type="button"
            onClick={() => onChange({ q: undefined, city: undefined, category: undefined })}
            className="rounded-lg border border-slate-300 px-4 py-2 text-sm text-slate-700 hover:bg-slate-50 focus:outline-none focus:ring-2 focus:ring-indigo-200"
          >
            Clear
          </button>
        )}
      </div>
    </form>
  )
}
