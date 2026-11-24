/**
 * Light/dark theme (W8·D4). The class on <html> is set before React loads by the inline script in index.html (same
 * key and rule as here), so the first paint already has the right colours; this module only reads and changes it.
 */
export type Theme = 'light' | 'dark'

const KEY = 'seatlock.theme'

export function currentTheme(): Theme {
  return document.documentElement.classList.contains('dark') ? 'dark' : 'light'
}

export function setTheme(theme: Theme): void {
  document.documentElement.classList.toggle('dark', theme === 'dark')
  try {
    window.localStorage.setItem(KEY, theme)
  } catch {
    // storage blocked: the choice lasts until the page is reloaded
  }
}
