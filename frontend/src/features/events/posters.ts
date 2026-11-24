import type { EventCategory } from '../../types/api'

/**
 * Poster backgrounds per category (W8·D4): the seed has no artwork, so each card gets a bold gradient plus a large
 * glyph. Full class strings (not built at runtime) so Tailwind sees them.
 */
export const CATEGORY_POSTER: Record<EventCategory, { gradient: string; glyph: string }> = {
  MOVIE: { gradient: 'from-violet-600 via-indigo-700 to-slate-900', glyph: '🎬' },
  CONCERT: { gradient: 'from-pink-500 via-fuchsia-600 to-indigo-900', glyph: '🎵' },
  COMEDY: { gradient: 'from-amber-400 via-orange-500 to-rose-700', glyph: '🎤' },
  THEATRE: { gradient: 'from-rose-600 via-red-700 to-stone-900', glyph: '🎭' },
  SPORTS: { gradient: 'from-emerald-500 via-teal-600 to-slate-900', glyph: '🏟️' },
  WORKSHOP: { gradient: 'from-sky-500 via-cyan-600 to-slate-900', glyph: '✏️' },
}
