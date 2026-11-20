// k6 flash-sale test (W6·D3): many users rush for a few seats of one show at the same moment.
//   k6 run load-tests/flash-sale.js                          (defaults: 300 users, 20 seats, 3 tries each)
//   k6 run -e USERS=500 -e SEATS=10 load-tests/flash-sale.js
// Each user: read the seat map → try to hold 1–2 free seats of the sale block → on 409 re-read and try again
// (up to TRIES) → a winner converts the hold into a booking. Users never pay (bookings expire after 8 min).
// Oversell proof: no sale slot is won twice and seats won <= seats on sale (thresholds below), plus the
// invariant SQL that run-k6.ps1 runs against Postgres afterwards. Local stack only (#69).
import http from 'k6/http';
import { sleep, fail } from 'k6';
import { Counter } from 'k6/metrics';
import { API, JSON_HEADERS, getJson, discoverShows, createUsers, fakeClientIp, idempotencyKey, textSummary }
  from './lib/common.js';

const USERS = Number(__ENV.USERS || 300);
const SEATS = Number(__ENV.SEATS || 20);
const TRIES = Number(__ENV.TRIES || 3);
const MAX_PER_USER = Number(__ENV.MAX_PER_USER || 2);

const holdsWon = new Counter('holds_won');
const seatsWon = new Counter('seats_won');
const seatWins = new Counter('seat_wins');          // tagged slot=<0..SEATS-1>: must be <= 1 per slot
const conflicts = new Counter('hold_conflicts');    // tagged code=SEATS_UNAVAILABLE | ...
const soldOut = new Counter('sold_out');            // user found no free seat in the block
const rateLimited = new Counter('rate_limited');    // 429 (must stay 0: each user has its own IP)
const unexpected = new Counter('unexpected_responses');
const bookings = new Counter('bookings_created');
const bookingFailed = new Counter('booking_failed');

const thresholds = {
  [`seats_won`]: [`count<=${SEATS}`],
  unexpected_responses: ['count==0'],
  rate_limited: ['count==0'],
  booking_failed: ['count==0'],
  // Latency is REPORTED, not gated: a 300–500-user burst on a laptop (k6 on the same CPU, 10 DB connections per
  // instance) measures queueing, not a capacity target. These very loose entries only make k6 print p95/p99 per step.
  // Correctness (above and the per-slot rules below) is what passes or fails a run.
  'http_req_duration{name:GET /shows/{id}/seats}': ['p(99)<60000'],
  'http_req_duration{name:POST /shows/{id}/holds}': ['p(99)<60000'],
  'http_req_duration{name:POST /holds/{id}/booking}': ['p(99)<60000'],
};
for (let slot = 0; slot < SEATS; slot++) {
  thresholds[`seat_wins{slot:${slot}}`] = ['count<=1'];
}

export const options = {
  setupTimeout: '5m',     // creates USERS accounts (BCrypt on the server)
  scenarios: {
    rush: {
      executor: 'per-vu-iterations',   // every VU starts at once: the "sale opens" moment
      vus: USERS,
      iterations: 1,
      maxDuration: '3m',
    },
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  thresholds,
};

// 409 is a normal answer in a sale (someone else got the seat), not a failed request.
http.setResponseCallback(http.expectedStatuses(200, 201, 409));

export function setup() {
  const { shows } = discoverShows(10, 24);
  // First show with SEATS free seats; the sale block = its first SEATS AVAILABLE seats in map order.
  for (const showId of shows) {
    const map = getJson(`${API}/shows/${showId}/seats`);
    const free = map.seats.filter((s) => s.status === 'AVAILABLE').map((s) => s.seatId);
    if (free.length >= SEATS) {
      const runId = Date.now();
      console.log(`Sale: show ${showId}, ${SEATS} seats, ${USERS} users, holds live: ${map.holdsLive}`);
      const tokens = createUsers(USERS, runId);
      return { showId, block: free.slice(0, SEATS), tokens };
    }
  }
  fail(`No upcoming show with ${SEATS} free seats`);
}

function shuffle(list) {
  for (let i = list.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [list[i], list[j]] = [list[j], list[i]];
  }
  return list;
}

export default function (data) {
  const n = __VU - 1;
  const headers = Object.assign({}, JSON_HEADERS, {
    Authorization: `Bearer ${data.tokens[n]}`,
    'X-Forwarded-For': fakeClientIp(n),       // per-user client IP for the per-IP hold limit (see run notes)
  });
  const slotOf = new Map(data.block.map((seatId, slot) => [seatId, slot]));

  for (let attempt = 1; attempt <= TRIES; attempt++) {
    const map = http.get(`${API}/shows/${data.showId}/seats`, { headers, tags: { name: 'GET /shows/{id}/seats' } });
    if (map.status !== 200) {
      unexpected.add(1, { step: 'seat map', status: String(map.status) });
      return;
    }
    const free = map.json('seats').filter((s) => slotOf.has(s.seatId) && s.status === 'AVAILABLE').map((s) => s.seatId);
    if (free.length === 0) {
      soldOut.add(1);
      return;
    }
    const want = Math.min(free.length, 1 + Math.floor(Math.random() * MAX_PER_USER));
    const seatIds = shuffle(free).slice(0, want);

    const hold = http.post(`${API}/shows/${data.showId}/holds`, JSON.stringify({ seatIds }), {
      headers: Object.assign({}, headers, { 'Idempotency-Key': idempotencyKey(__VU, attempt) }),
      tags: { name: 'POST /shows/{id}/holds' },
    });

    if (hold.status === 201) {
      holdsWon.add(1);
      seatsWon.add(seatIds.length);
      seatIds.forEach((id) => seatWins.add(1, { slot: String(slotOf.get(id)) }));
      const booking = http.post(`${API}/holds/${hold.json('holdId')}/booking`, null, {
        headers, tags: { name: 'POST /holds/{id}/booking' },
      });
      if (booking.status === 201) {
        bookings.add(1);
      } else {
        bookingFailed.add(1, { status: String(booking.status) });
        console.warn(`booking for hold ${hold.json('holdId')} → ${booking.status} ${booking.body}`);
      }
      return;
    }
    if (hold.status === 409) {
      conflicts.add(1, { code: String(hold.json('code')) });
      sleep(0.1 + Math.random() * 0.3);   // a human clicks another seat
      continue;
    }
    if (hold.status === 429) {
      rateLimited.add(1);
      return;
    }
    unexpected.add(1, { step: 'hold', status: String(hold.status) });
    console.warn(`hold → ${hold.status} ${hold.body}`);
    return;
  }
}

export function teardown(data) {
  const map = getJson(`${API}/shows/${data.showId}/seats`);
  const inBlock = new Set(data.block);
  const taken = map.seats.filter((s) => inBlock.has(s.seatId) && s.status !== 'AVAILABLE').length;
  console.log(`After the sale: ${taken}/${data.block.length} sale seats not AVAILABLE on show ${data.showId}`);
}

export function handleSummary(data) {
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  return {
    stdout: textSummary(data, `flash sale: ${USERS} users, ${SEATS} seats, ${TRIES} tries`,
      ['holds_won', 'seats_won', 'bookings_created', 'hold_conflicts', 'sold_out', 'rate_limited',
        'booking_failed', 'unexpected_responses']),
    [`load-tests/results/flash-sale-${stamp}.json`]: JSON.stringify(data, null, 2),
  };
}
