import type { BookingStatus } from '../../types/api'

/** Label, badge colours and a one-line explanation per booking status (booking page + My bookings). */
export const STATUS_TEXT: Record<BookingStatus, { label: string; tone: string; text: string }> = {
  HELD: { label: 'Awaiting payment', tone: 'bg-indigo-50 text-indigo-700', text: 'Your seats are reserved until the timer runs out.' },
  PAYMENT_PENDING: { label: 'Payment in progress', tone: 'bg-indigo-50 text-indigo-700', text: 'We are waiting for the payment provider.' },
  CONFIRMED: { label: 'Confirmed', tone: 'bg-emerald-50 text-emerald-700', text: 'You are all set. Enjoy the show!' },
  EXPIRED: { label: 'Expired', tone: 'bg-slate-100 text-slate-600', text: 'The time ran out before payment, so the seats were released.' },
  CANCELLED: { label: 'Cancelled', tone: 'bg-slate-100 text-slate-600', text: 'You cancelled this booking; the seats were released.' },
  REFUNDED: { label: 'Cancelled · refunded', tone: 'bg-slate-100 text-slate-600', text: 'You cancelled this booking; the refund goes back to your original payment method.' },
}
