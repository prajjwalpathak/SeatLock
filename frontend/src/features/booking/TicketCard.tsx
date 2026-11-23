import { useQuery } from '@tanstack/react-query'
import QRCode from 'qrcode'
import { useState } from 'react'
import { formatPaise, formatShowTime } from '../../lib/format'
import type { Booking, ShowSeatMap } from '../../types/api'

type Props = {
  booking: Booking
  /** Event title, time and venue; the card still works without them (seats + booking ID). */
  show?: ShowSeatMap
}

const QR_SIZE = 200

/** The booking page's own URL: scanning opens the same owner-only page (anyone else gets "not found"). */
function ticketUrl(publicId: string): string {
  return `${window.location.origin}/bookings/${publicId}`
}

function seatList(booking: Booking): string {
  return booking.seats.map((s) => `${s.rowLabel}${s.seatNumber}`).join(', ')
}

/**
 * Ticket for a CONFIRMED booking: details + a QR code of the booking URL, and "Download ticket" as a PNG drawn on a
 * canvas in the browser (no server round trip, no screenshot library). Decision #219: no PDF, e-mail or check-in.
 */
export function TicketCard({ booking, show }: Props) {
  const url = ticketUrl(booking.publicId)
  const qr = useQuery({
    queryKey: ['ticket-qr', url],
    queryFn: () => QRCode.toDataURL(url, { width: QR_SIZE, margin: 1, errorCorrectionLevel: 'M' }),
    staleTime: Infinity,
  })
  const [downloadError, setDownloadError] = useState<string | null>(null)

  async function download() {
    if (!qr.data) return
    setDownloadError(null)
    try {
      const blob = await drawTicket(booking, show, qr.data)
      const link = document.createElement('a')
      link.href = URL.createObjectURL(blob)
      link.download = `seatlock-ticket-${booking.publicId.slice(0, 8)}.png`
      link.click()
      setTimeout(() => URL.revokeObjectURL(link.href), 1_000)   // revoking at once can cancel the download
    } catch {
      setDownloadError('Could not create the image. Please try again.')
    }
  }

  return (
    <section aria-labelledby="ticket-title" className="flex flex-col gap-4 rounded-xl border-2 border-dashed border-emerald-300 bg-white p-4 shadow-sm sm:flex-row sm:items-center">
      <div className="flex-1 space-y-1">
        <h2 id="ticket-title" className="text-sm font-semibold uppercase tracking-wide text-emerald-700">Your ticket</h2>
        {show && (
          <>
            <p className="text-lg font-semibold">{show.eventTitle}</p>
            <p className="text-sm text-slate-600">
              {formatShowTime(show.startsAt)} · {show.venue.name}, {show.venue.city}
            </p>
          </>
        )}
        <p className="text-sm">
          Seats <span className="font-medium">{seatList(booking)}</span> · {formatPaise(booking.totalPaise)}
        </p>
        <p className="font-mono text-xs text-slate-500">Booking {booking.publicId}</p>
        <button
          type="button"
          onClick={download}
          disabled={!qr.data}
          className="mt-2 rounded-lg bg-emerald-600 px-4 py-2 text-sm font-medium text-white hover:bg-emerald-700 disabled:opacity-50"
        >
          Download ticket
        </button>
        {downloadError && <p role="alert" className="text-sm text-red-700">{downloadError}</p>}
      </div>
      <div className="flex size-[200px] items-center justify-center self-center">
        {qr.data ? (
          <img src={qr.data} width={QR_SIZE} height={QR_SIZE} alt={`QR code linking to booking ${booking.publicId}`} />
        ) : (
          <div aria-hidden="true" className="size-full animate-pulse rounded bg-slate-200" />
        )}
      </div>
    </section>
  )
}

/** Draws the ticket (840 × 380 px) and returns it as a PNG blob. */
async function drawTicket(booking: Booking, show: ShowSeatMap | undefined, qrDataUrl: string): Promise<Blob> {
  const canvas = document.createElement('canvas')
  canvas.width = 840
  canvas.height = 380
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('Canvas not supported')

  ctx.fillStyle = '#ffffff'
  ctx.fillRect(0, 0, canvas.width, canvas.height)
  ctx.strokeStyle = '#6ee7b7'
  ctx.lineWidth = 4
  ctx.setLineDash([12, 8])
  ctx.strokeRect(10, 10, canvas.width - 20, canvas.height - 20)
  ctx.setLineDash([])

  const font = 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif'
  const textWidth = 520
  ctx.textBaseline = 'top'
  ctx.fillStyle = '#047857'
  ctx.font = `600 16px ${font}`
  ctx.fillText('SEATLOCK · TICKET', 40, 40)

  ctx.fillStyle = '#0f172a'
  ctx.font = `700 30px ${font}`
  ctx.fillText(fit(ctx, show?.eventTitle ?? 'Your booking', textWidth), 40, 72)

  ctx.fillStyle = '#475569'
  ctx.font = `400 18px ${font}`
  if (show) {
    ctx.fillText(fit(ctx, formatShowTime(show.startsAt), textWidth), 40, 122)
    ctx.fillText(fit(ctx, `${show.venue.name}, ${show.venue.city}`, textWidth), 40, 150)
  }

  ctx.fillStyle = '#0f172a'
  ctx.font = `600 22px ${font}`
  ctx.fillText(fit(ctx, `Seats ${seatList(booking)}`, textWidth), 40, 200)
  ctx.font = `400 20px ${font}`
  ctx.fillText(`Paid ${formatPaise(booking.totalPaise)}`, 40, 236)

  ctx.fillStyle = '#64748b'
  ctx.font = `400 14px ui-monospace, Consolas, monospace`
  ctx.fillText(`Booking ${booking.publicId}`, 40, 300)
  ctx.font = `400 13px ${font}`
  ctx.fillText('Show this ticket or the booking page at the entry.', 40, 324)

  const qr = await loadImage(qrDataUrl)
  ctx.drawImage(qr, canvas.width - 40 - 260, 60, 260, 260)

  return new Promise((resolve, reject) =>
    canvas.toBlob((blob) => (blob ? resolve(blob) : reject(new Error('Empty image'))), 'image/png'),
  )
}

/** Shortens text with "…" until it fits the width. */
function fit(ctx: CanvasRenderingContext2D, text: string, maxWidth: number): string {
  if (ctx.measureText(text).width <= maxWidth) return text
  let t = text
  while (t.length > 1 && ctx.measureText(`${t}…`).width > maxWidth) t = t.slice(0, -1)
  return `${t}…`
}

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => resolve(img)
    img.onerror = () => reject(new Error('QR image failed to load'))
    img.src = src
  })
}
