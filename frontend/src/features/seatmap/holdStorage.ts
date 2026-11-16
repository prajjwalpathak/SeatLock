// Remembers the user's live hold per show for this tab, so a refresh (F5) resumes the countdown.
// sessionStorage can throw (privacy modes, storage full): every access is guarded; losing it is harmless,
// because the server still knows the hold (a new attempt answers HOLD_EXISTS with its holdId).

const key = (showId: number) => `seatlock.hold.${showId}`

export function rememberHold(showId: number, holdId: string): void {
  try {
    sessionStorage.setItem(key(showId), holdId)
  } catch {
    // ignore
  }
}

export function recallHold(showId: number): string | null {
  try {
    return sessionStorage.getItem(key(showId))
  } catch {
    return null
  }
}

export function forgetHold(showId: number): void {
  try {
    sessionStorage.removeItem(key(showId))
  } catch {
    // ignore
  }
}
