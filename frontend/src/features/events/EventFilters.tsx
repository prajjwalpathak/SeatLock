import type { FormEvent } from 'react'
import { EVENT_CATEGORIES, type EventCategory } from '../../types/api'
import { CATEGORY_LABELS, CITIES, type EventFilterValues } from './filters'

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

  return (
    <form
      role="search"
      onSubmit={handleSubmit}
      className="flex flex-col gap-3 rounded-xl border border-slate-200 bg-white p-4 shadow-sm sm:flex-row sm:items-end"
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

      <label className="sm:w-40">
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

      <label className="sm:w-40">
        <span className="text-xs font-medium text-slate-600">Category</span>
        <select
          value={values.category ?? ''}
          onChange={(e) => onChange({ category: (e.target.value || undefined) as EventCategory | undefined })}
          className={fieldClass}
        >
          <option value="">All categories</option>
          {EVENT_CATEGORIES.map((category) => (
            <option key={category} value={category}>
              {CATEGORY_LABELS[category]}
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
