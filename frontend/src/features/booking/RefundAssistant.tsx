import { useMutation } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { askRefundAssistant } from '../../api/refundAssistant'
import { hasCode } from '../../api/client'
import type { RefundAnswer } from '../../types/api'

const SUGGESTIONS = ['Can I cancel?', 'How much will I get back?', 'When does cancellation close?']

const DOC_NAMES: Record<string, string> = {
  'refund-policy': 'Refund policy',
  terms: 'Terms of booking',
  faq: 'FAQ',
}

function docName(doc: string): string {
  return DOC_NAMES[doc] ?? doc.replace(/^venue-/, '').replace(/-/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase())
}

/**
 * "Questions about cancelling?": the answer comes from SeatLock's refund engine (amounts, deadlines), explained by
 * AI when available and checked so it can only repeat the engine's numbers; otherwise a standard answer. It never
 * cancels anything: that stays the Cancel button.
 */
export function RefundAssistant({ bookingId }: { bookingId: string }) {
  const [question, setQuestion] = useState('')
  const [answer, setAnswer] = useState<RefundAnswer | null>(null)
  const [error, setError] = useState<string | null>(null)

  const ask = useMutation({
    mutationFn: (q: string) => askRefundAssistant(bookingId, q),
    onSuccess: (a) => {
      setAnswer(a)
      setError(null)
    },
    onError: (e) => {
      setAnswer(null)
      setError(
        hasCode(e, 'RATE_LIMITED')
          ? `Too many questions. Please wait ${e.retryAfterSeconds ?? 30} seconds.`
          : e instanceof Error
            ? e.message
            : 'Could not get an answer. Please try again.',
      )
    },
  })

  function submit(e: FormEvent) {
    e.preventDefault()
    if (question.trim()) ask.mutate(question.trim())
  }

  function quick(q: string) {
    setQuestion(q)
    ask.mutate(q)
  }

  return (
    <section aria-labelledby="refund-assistant-title" className="space-y-3 rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
      <h2 id="refund-assistant-title" className="text-base font-semibold">Questions about cancelling?</h2>
      <div className="flex flex-wrap gap-2">
        {SUGGESTIONS.map((s) => (
          <button
            key={s}
            type="button"
            onClick={() => quick(s)}
            disabled={ask.isPending}
            className="rounded-full border border-slate-300 px-3 py-1 text-xs text-slate-700 hover:bg-slate-50 disabled:opacity-50"
          >
            {s}
          </button>
        ))}
      </div>
      <form onSubmit={submit} className="flex flex-col gap-2 sm:flex-row">
        <label htmlFor="refund-question" className="sr-only">Your question</label>
        <input
          id="refund-question"
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          maxLength={300}
          placeholder="Ask about cancelling or refunds for this booking"
          className="flex-1 rounded-lg border border-slate-300 px-3 py-2 text-sm"
        />
        <button
          type="submit"
          disabled={ask.isPending || !question.trim()}
          className="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700 disabled:opacity-50"
        >
          {ask.isPending ? 'Thinking…' : 'Ask'}
        </button>
      </form>

      <div aria-live="polite" className="space-y-2">
        {error && <p role="alert" className="text-sm text-red-700">{error}</p>}
        {answer && (
          <div className="space-y-2 rounded-lg bg-slate-50 p-3 text-sm">
            <p className="text-slate-900">{answer.answer}</p>
            <p className="text-xs text-slate-500">
              {answer.source === 'AI'
                ? 'Explained by AI from SeatLock’s refund rules; amounts are checked against your booking.'
                : 'Standard answer from SeatLock’s refund rules.'}
            </p>
            {answer.sources.length > 0 && (
              <details className="text-xs text-slate-600">
                <summary className="cursor-pointer">From our policies ({answer.sources.length})</summary>
                <ul className="mt-2 space-y-2">
                  {answer.sources.map((s) => (
                    <li key={`${s.doc}:${s.heading}`}>
                      <span className="font-medium">{docName(s.doc)} · {s.heading}</span>
                      <p className="text-slate-500">{s.text.slice(s.text.indexOf('. ') + 2)}</p>
                    </li>
                  ))}
                </ul>
              </details>
            )}
          </div>
        )}
      </div>
    </section>
  )
}
