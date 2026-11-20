// k6 hold throughput (W6·D4): a closed loop to compare SeatLockingStrategy variants and virtual threads.
//   k6 run -e VUS=50 -e DURATION=60s load-tests/strategies.js      (normally via bench-strategies.ps1)
// Each VU is its own user and loops as fast as it can: hold 1–2 random seats of one large show → release.
// Low contention (seats are rarely wanted by two VUs at once), so this measures the cost of a hold, not queueing for
// one seat; the flash sale (flash-sale.js) is the contention case. Local stack only, rate limiting off (bench overlay).
import http from 'k6/http';
import { fail } from 'k6';
import { Counter } from 'k6/metrics';
import { API, JSON_HEADERS, getJson, discoverShows, createUsers, fakeClientIp, idempotencyKey, textSummary }
  from './lib/common.js';

const VUS = Number(__ENV.VUS || 50);
const DURATION = __ENV.DURATION || '60s';

const holdsOk = new Counter('holds_ok');
const releasesOk = new Counter('releases_ok');
const conflicts = new Counter('hold_conflicts');      // 409, tagged code (seat taken by another VU, rare)
const rateLimited = new Counter('rate_limited');      // must be 0 (bench overlay turns the limiter off)
const unexpected = new Counter('unexpected_responses');

export const options = {
  setupTimeout: '5m',
  scenarios: {
    loop: { executor: 'constant-vus', vus: VUS, duration: DURATION },
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    unexpected_responses: ['count==0'],
    rate_limited: ['count==0'],
    // reported, not gated (see benchmarks.md section 5)
    'http_req_duration{name:POST /shows/{id}/holds}': ['p(99)<60000'],
    'http_req_duration{name:DELETE /holds/{id}}': ['p(99)<60000'],
  },
};

http.setResponseCallback(http.expectedStatuses(200, 201, 204, 409));

export function setup() {
  const { shows } = discoverShows(10, 24);
  let best = null;
  for (const showId of shows.slice(0, 6)) {
    const map = getJson(`${API}/shows/${showId}/seats`);
    const free = map.seats.filter((s) => s.status === 'AVAILABLE').map((s) => s.seatId);
    if (!best || free.length > best.free.length) best = { showId, free };
  }
  if (!best || best.free.length < VUS * 3) {
    fail(`Need a show with at least ${VUS * 3} free seats (best: ${best ? best.free.length : 0})`);
  }
  console.log(`Throughput: show ${best.showId}, ${best.free.length} free seats, ${VUS} VUs for ${DURATION}`);
  return { showId: best.showId, free: best.free, tokens: createUsers(VUS, Date.now()) };
}

let iteration = 0;

export default function (data) {
  iteration++;
  const n = __VU - 1;
  const headers = Object.assign({}, JSON_HEADERS, {
    Authorization: `Bearer ${data.tokens[n]}`,
    'X-Forwarded-For': fakeClientIp(n),
  });
  const want = 1 + Math.floor(Math.random() * 2);
  const seatIds = [];
  while (seatIds.length < want) {
    const id = data.free[Math.floor(Math.random() * data.free.length)];
    if (!seatIds.includes(id)) seatIds.push(id);
  }

  const hold = http.post(`${API}/shows/${data.showId}/holds`, JSON.stringify({ seatIds }), {
    headers: Object.assign({}, headers, { 'Idempotency-Key': idempotencyKey(__VU, iteration) }),
    tags: { name: 'POST /shows/{id}/holds' },
  });
  if (hold.status === 201) {
    holdsOk.add(1);
    const release = http.del(`${API}/holds/${hold.json('holdId')}`, null, {
      headers, tags: { name: 'DELETE /holds/{id}' },
    });
    if (release.status === 204) {
      releasesOk.add(1);
    } else {
      unexpected.add(1, { step: 'release', status: String(release.status) });
    }
  } else if (hold.status === 409) {
    conflicts.add(1, { code: String(hold.json('code')) });
  } else if (hold.status === 429) {
    rateLimited.add(1);
  } else {
    unexpected.add(1, { step: 'hold', status: String(hold.status) });
    if (Math.random() < 0.05) console.warn(`hold → ${hold.status} ${String(hold.body).slice(0, 200)}`);
  }
}

export function handleSummary(data) {
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  return {
    stdout: textSummary(data, `hold throughput: ${VUS} VUs, ${DURATION}`,
      ['holds_ok', 'releases_ok', 'hold_conflicts', 'rate_limited', 'unexpected_responses']),
    [__ENV.SUMMARY_FILE || `load-tests/results/strategies-${stamp}.json`]: JSON.stringify(data, null, 2),
  };
}
