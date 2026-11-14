/**
 * Public demo logins (Flyway V5). The password is public on purpose so anyone, e.g. an
 * interviewer, can try the app. These accounts are low-privilege and reset nightly from W8.
 */
export const DEMO_PASSWORD = 'seatlock-demo'

export const DEMO_ACCOUNTS = [
  { label: 'Try as demo user', email: 'demo.user@seatlock.test' },
  { label: 'Try as demo organizer', email: 'organizer.cinema@seatlock.test' },
] as const
