import { useMutation } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useLocation } from 'react-router'
import { hasCode } from '../../api/client'
import { askSeatFinder, findSeats } from '../../api/seatFinder'
import { formatPaise } from '../../lib/format'
import type {
  AiFallbackReason,
  RowPreference,
  SeatAskResult,
  SeatFinderFilters,
  SeatFinderOption,
  SeatFinderResult,
  ShowSeatMap,
} from '../../types/api'
import { MAX_SELECTION, sectionName } from './seats'

type Props = {
  seatMap: ShowSeatMap
  signedIn: boolean
  /** Picking is off (own hold active, show started, hold in progress). */
  disabled: boolean
  /** "Select these seats": replaces the current selection. */
  onPick: (seatIds: number[]) => void
  /** Called with every seat of the current options (ringed on the map); [] clears them. */
  onSuggest: (seatIds: number[]) => void
}

const ROWS: { value: RowPreference; label: string }[] = [
  { value: 'ANY', label: 'Best view' },
  { value: 'FRONT', label: 'Front' },
  { value: 'MIDDLE', label: 'Middle' },
  { value: 'BACK', label: 'Back' },
]

const FALLBACK_TEXT: Record<AiFallbackReason, string> = {
  DISABLED: 'The seat assistant is switched off right now.',
  USER_QUOTA: "You've used the seat assistant a lot today.",
  QUOTA: "The seat assistant has reached today's limit.",
  CIRCUIT_OPEN: 'The seat assistant is not responding right now.',
  TIMEOUT: 'The seat assistant took too long to answer.',
  BUSY: 'The seat assistant is busy right now.',
  ERROR: 'The seat assistant is not responding right now.',
  INVALID_OUTPUT: "The seat assistant couldn't understand that.",
}

type Form = { count: number; maxRupees: string; sections: string[]; aisle: boolean; rows: RowPreference }

const EMPTY_FORM: Form = { count: 2, maxRupees: '', sections: [], aisle: false, rows: 'ANY' }

function toFilters(form: Form): SeatFinderFilters {
  const rupees = Number(form.maxRupees)
  return {
    count: form.count,
    maxPricePaise: form.maxRupees.trim() !== '' && rupees > 0 ? Math.round(rupees * 100) : null,
    sections: form.sections,
    aisle: form.aisle,
    rows: form.rows,
  }
}

function fromFilters(f: SeatFinderFilters): Form {
  return {
    count: Math.min(f.count, MAX_SELECTION),
    maxRupees: f.maxPricePaise ? String(f.maxPricePaise / 100) : '',
    sections: f.sections ?? [],
    aisle: f.aisle ?? false,
    rows: f.rows ?? 'ANY',
  }
}

/** "2 seats · up to ₹800 each · Gold · aisle · front": what the search used, in words. */
function describe(f: SeatFinderFilters): string {
  const parts = [`${f.count} ${f.count === 1 ? 'seat' : 'seats'}`]
  if (f.maxPricePaise) parts.push(`up to ${formatPaise(f.maxPricePaise)} each`)
  if (f.sections?.length) parts.push(f.sections.map(sectionName).join(' or '))
  if (f.aisle) parts.push('aisle')
  if (f.rows && f.rows !== 'ANY') parts.push(f.rows.toLowerCase())
  return parts.join(' · ')
}

/**
 * Seat Finder: describe seats in words (signed in, AI) or use the filters (anyone, no AI). Both end in the same
 * search; the AI only fills in the filters, and anything it can't do falls back to them. Suggestions hold nothing:
 * "Select these seats" puts them in the selection, and Hold stays the only step that claims seats.
 */
export function SeatFinderPanel({ seatMap, signedIn, disabled, onPick, onSuggest }: Props) {
  const location = useLocation()
  const [text, setText] = useState('')
  const [form, setForm] = useState<Form>(EMPTY_FORM)
  const [result, setResult] = useState<SeatFinderResult | null>(null)
  const [searched, setSearched] = useState<SeatFinderFilters | null>(null)
  const [note, setNote] = useState<string | null>(null)

  const showResult = (r: SeatFinderResult, used: SeatFinderFilters) => {
    setResult(r)
    setSearched(used)
    onSuggest(r.options.flatMap((o) => o.seatIds))
  }
  const clearResult = () => {
    setResult(null)
    setSearched(null)
    onSuggest([])
  }

  const errorNote = (error: unknown) => {
    if (hasCode(error, 'RATE_LIMITED')) {
      return `Too many searches. Please wait ${error.retryAfterSeconds ?? 30} seconds, or use the filters below.`
    }
    if (hasCode(error, 'TOO_MANY_SEATS', 'UNKNOWN_SECTION')) return error.message
    return error instanceof Error ? error.message : 'Search failed. Please try again.'
  }

  const ask = useMutation({
    mutationFn: (t: string) => askSeatFinder(seatMap.showId, t),
    onSuccess: (r: SeatAskResult) => {
      if (r.understood && r.filters && r.result) {
        setForm(fromFilters(r.filters))
        showResult(r.result, r.filters)
        setNote(r.assumptions.length ? r.assumptions.join(' ') : null)
      } else {
        clearResult()
        setNote(`${FALLBACK_TEXT[r.fallbackReason ?? 'ERROR']} Use the filters below instead.`)
      }
    },
    onError: (error) => {
      clearResult()
      setNote(errorNote(error))
    },
  })

  const manual = useMutation({
    mutationFn: (filters: SeatFinderFilters) => findSeats(seatMap.showId, filters),
    onSuccess: (r, filters) => {
      showResult(r, filters)
      setNote(null)
    },
    onError: (error) => {
      clearResult()
      setNote(errorNote(error))
    },
  })

  const busy = ask.isPending || manual.isPending

  function submitText(e: FormEvent) {
    e.preventDefault()
    if (text.trim()) ask.mutate(text.trim())
  }

  function submitFilters(e: FormEvent) {
    e.preventDefault()
    manual.mutate(toFilters(form))
  }

  function toggleSection(section: string) {
    setForm((f) => ({
      ...f,
      sections: f.sections.includes(section) ? f.sections.filter((s) => s !== section) : [...f.sections, section],
    }))
  }

  return (
    <section aria-labelledby="seat-finder-title" className="space-y-4 rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
      <h2 id="seat-finder-title" className="text-base font-semibold">Find seats for me</h2>

      {signedIn ? (
        <form onSubmit={submitText} className="flex flex-col gap-2 sm:flex-row">
          <label htmlFor="seat-finder-text" className="sr-only">Describe the seats you want</label>
          <input
            id="seat-finder-text"
            value={text}
            onChange={(e) => setText(e.target.value)}
            maxLength={300}
            placeholder='e.g. "2 together near the aisle under ₹800, front rows"'
            className="flex-1 rounded-lg border border-slate-300 px-3 py-2 text-sm"
            disabled={disabled}
          />
          <button
            type="submit"
            disabled={disabled || busy || !text.trim()}
            className="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {ask.isPending ? 'Thinking…' : 'Find seats'}
          </button>
        </form>
      ) : (
        <p className="text-sm text-slate-600">
          <Link to="/login" state={{ from: location.pathname }} className="text-indigo-600 hover:underline">
            Sign in
          </Link>{' '}
          to describe the seats you want in your own words, or use the filters below.
        </p>
      )}

      <form onSubmit={submitFilters} className="flex flex-wrap items-end gap-3 text-sm" aria-label="Seat filters">
        <label className="flex flex-col gap-1">
          <span className="text-xs text-slate-500">Seats</span>
          <select
            value={form.count}
            onChange={(e) => setForm({ ...form, count: Number(e.target.value) })}
            className="rounded-md border border-slate-300 px-2 py-1.5"
          >
            {Array.from({ length: MAX_SELECTION }, (_, i) => i + 1).map((n) => (
              <option key={n} value={n}>{n}</option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-xs text-slate-500">Max ₹ per seat</span>
          <input
            type="number"
            min={1}
            inputMode="numeric"
            value={form.maxRupees}
            onChange={(e) => setForm({ ...form, maxRupees: e.target.value })}
            placeholder="Any"
            className="w-28 rounded-md border border-slate-300 px-2 py-1.5"
          />
        </label>
        <fieldset className="flex flex-col gap-1">
          <legend className="text-xs text-slate-500">Sections</legend>
          <div className="flex gap-3 py-1.5">
            {seatMap.sections.map((s) => (
              <label key={s.section} className="flex items-center gap-1">
                <input type="checkbox" checked={form.sections.includes(s.section)} onChange={() => toggleSection(s.section)} />
                {sectionName(s.section)}
              </label>
            ))}
          </div>
        </fieldset>
        <label className="flex items-center gap-1 py-1.5">
          <input type="checkbox" checked={form.aisle} onChange={(e) => setForm({ ...form, aisle: e.target.checked })} />
          Aisle seat
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-xs text-slate-500">Rows</span>
          <select
            value={form.rows}
            onChange={(e) => setForm({ ...form, rows: e.target.value as RowPreference })}
            className="rounded-md border border-slate-300 px-2 py-1.5"
          >
            {ROWS.map((r) => (
              <option key={r.value} value={r.value}>{r.label}</option>
            ))}
          </select>
        </label>
        <button
          type="submit"
          disabled={disabled || busy}
          className="rounded-lg border border-slate-300 px-4 py-1.5 font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50"
        >
          {manual.isPending ? 'Searching…' : 'Search'}
        </button>
      </form>

      <div aria-live="polite" className="space-y-3">
        {note && <p className="rounded-lg bg-slate-50 p-3 text-sm text-slate-700">{note}</p>}
        {result && searched && (
          <>
            <p className="text-sm text-slate-600">
              Searched for <span className="font-medium text-slate-900">{describe(searched)}</span>
            </p>
            {result.options.length === 0 ? (
              <p className="text-sm text-slate-600">
                No {searched.count} seats side by side match that. Try fewer seats, a higher budget or other sections.
              </p>
            ) : (
              <ol className="grid gap-2 sm:grid-cols-3">
                {result.options.map((o) => (
                  <OptionCard key={o.rank} option={o} disabled={disabled} onPick={onPick} />
                ))}
              </ol>
            )}
          </>
        )}
      </div>
    </section>
  )
}

function OptionCard({ option, disabled, onPick }: { option: SeatFinderOption; disabled: boolean; onPick: (ids: number[]) => void }) {
  const first = option.seats[0]
  const last = option.seats[option.seats.length - 1]
  const range = option.seats.length === 1 ? `${first.rowLabel}${first.seatNumber}` : `${first.rowLabel}${first.seatNumber}–${last.rowLabel}${last.seatNumber}`
  return (
    <li className="flex flex-col justify-between gap-2 rounded-lg border border-emerald-200 bg-emerald-50/50 p-3 text-sm">
      <div>
        <p className="font-medium">
          Option {option.rank}: {range}
        </p>
        <p className="text-slate-600">
          Row {option.rowLabel} · {option.sections.map(sectionName).join(', ')}
          {option.hasAisleSeat ? ' · aisle' : ''}
        </p>
        <p className="font-semibold">{formatPaise(option.totalPricePaise)} total</p>
      </div>
      <button
        type="button"
        disabled={disabled}
        onClick={() => onPick(option.seatIds)}
        className="rounded-md border border-emerald-600 px-3 py-1 text-xs font-medium text-emerald-700 hover:bg-emerald-100 disabled:opacity-50"
      >
        Select these seats
      </button>
    </li>
  )
}
