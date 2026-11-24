import { useMutation } from '@tanstack/react-query'
import { useState } from 'react'
import { login } from '../api/auth'
import { DEMO_ACCOUNTS, DEMO_PASSWORD } from '../features/auth/demoAccounts'
import { useAuth } from '../features/auth/useAuth'

const DISMISS_KEY = 'seatlock.intro.dismissed'

function readDismissed(): boolean {
  try {
    return window.localStorage.getItem(DISMISS_KEY) === '1'
  } catch {
    return false // storage blocked (private mode, strict settings): just show the banner
  }
}

/**
 * First thing a visitor sees on the event list (W8·D4 audit, finding 1): what SeatLock is, that payments are in
 * test mode, and a one-click demo login. Dismissible; the choice is remembered in this browser.
 */
export function IntroBanner() {
  const { status, signIn } = useAuth()
  const [dismissed, setDismissed] = useState(readDismissed)
  const demoUser = DEMO_ACCOUNTS[0]

  const demoLogin = useMutation({
    mutationFn: async () => signIn(await login(demoUser.email, DEMO_PASSWORD)),
  })

  if (dismissed) {
    return null
  }

  function dismiss() {
    setDismissed(true)
    try {
      window.localStorage.setItem(DISMISS_KEY, '1')
    } catch {
      // not remembered; it stays hidden until the next page load
    }
  }

  return (
    <section
      aria-labelledby="intro-title"
      className="relative overflow-hidden rounded-3xl bg-gradient-to-br from-indigo-600 via-violet-700 to-slate-950 p-6 text-white shadow-lg sm:p-8"
    >
      <span aria-hidden="true" className="pointer-events-none absolute -right-6 -top-10 text-[9rem] opacity-15">
        🎟️
      </span>
      <button
        type="button"
        onClick={dismiss}
        aria-label="Hide this introduction"
        className="absolute right-3 top-3 rounded-full px-2.5 py-1 text-white/70 hover:bg-white/15 hover:text-white focus:outline-none focus:ring-2 focus:ring-white/60"
      >
        ✕
      </button>
      <p className="text-xs font-semibold uppercase tracking-[0.2em] text-white/70">Live demo · test mode</p>
      <h2 id="intro-title" className="mt-2 max-w-2xl pr-8 text-2xl font-extrabold leading-tight tracking-tight sm:text-4xl">
        Flash-sale tickets. Nobody gets the same seat twice.
      </h2>
      <p className="mt-3 max-w-2xl text-sm text-white/80 sm:text-base">
        Hold seats on a live map (other people&rsquo;s holds appear in real time), pay with Razorpay in{' '}
        <strong className="font-semibold text-white">test mode</strong>: no real money, Netbanking → any bank → Success.
        Then ask the AI Seat Finder or the Refund Assistant.
      </p>
      <div className="mt-5 flex flex-wrap items-center gap-3">
        {status === 'signedIn' ? (
          <span className="rounded-full bg-white/15 px-4 py-2 text-sm font-medium">You&rsquo;re signed in: pick a show below ↓</span>
        ) : (
          <button
            type="button"
            onClick={() => demoLogin.mutate()}
            disabled={demoLogin.isPending || status === 'loading'}
            className="rounded-full bg-[#ffffff] px-5 py-2.5 text-sm font-bold text-[#4338ca] shadow hover:bg-[#eef2ff] focus:outline-none focus:ring-2 focus:ring-white/70 disabled:cursor-not-allowed disabled:opacity-60"
          >
            {demoLogin.isPending ? 'Signing in…' : 'Try the demo (one click)'}
          </button>
        )}
      </div>
      {demoLogin.isError && (
        <p role="alert" className="mt-3 text-sm text-rose-100">
          The demo login didn&rsquo;t work just now. Please try again, or use the Sign in page.
        </p>
      )}
    </section>
  )
}
