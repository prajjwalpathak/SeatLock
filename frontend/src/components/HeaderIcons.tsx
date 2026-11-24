import { useState } from 'react'
import { currentTheme, setTheme, type Theme } from '../lib/theme'

const REPO_URL = 'https://github.com/prajjwalpathak/SeatLock'

const iconButtonClass =
  'inline-flex h-9 w-9 items-center justify-center rounded-full text-slate-600 hover:bg-slate-100 hover:text-slate-900 ' +
  'focus:outline-none focus:ring-2 focus:ring-indigo-300'

/** Light/dark switch (W8·D4). Shows the theme you'd switch TO. */
export function ThemeToggle() {
  const [theme, setThemeState] = useState<Theme>(currentTheme)
  const next: Theme = theme === 'dark' ? 'light' : 'dark'
  return (
    <button
      type="button"
      onClick={() => {
        setTheme(next)
        setThemeState(next)
      }}
      aria-label={`Switch to ${next} mode`}
      title={`Switch to ${next} mode`}
      className={iconButtonClass}
    >
      {next === 'dark' ? (
        <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
          <path strokeLinecap="round" strokeLinejoin="round" d="M20.5 14.5A8.5 8.5 0 0 1 9.5 3.5a8.5 8.5 0 1 0 11 11Z" />
        </svg>
      ) : (
        <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
          <circle cx="12" cy="12" r="4" />
          <path
            strokeLinecap="round"
            d="M12 2.5v2M12 19.5v2M4.6 4.6l1.4 1.4M18 18l1.4 1.4M2.5 12h2M19.5 12h2M4.6 19.4 6 18M18 6l1.4-1.4"
          />
        </svg>
      )}
    </button>
  )
}

/** Source code link (W8·D4): a generic "code" icon, not GitHub's logo. */
export function SourceLink() {
  return (
    <a href={REPO_URL} target="_blank" rel="noreferrer" aria-label="Source code on GitHub" title="Source code on GitHub" className={iconButtonClass}>
      <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
        <path strokeLinecap="round" strokeLinejoin="round" d="m8 7-5 5 5 5M16 7l5 5-5 5M13.5 4.5l-3 15" />
      </svg>
    </a>
  )
}
