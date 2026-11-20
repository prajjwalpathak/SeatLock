// Shared helpers for the k6 scripts (W6·D3). Plain k6 modules only, no remote imports.
import http from 'k6/http';
import { fail } from 'k6';

export const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8088').replace(/\/$/, '');   // local LB (2 instances)
export const API = `${BASE_URL}/api/v1`;
export const JSON_HEADERS = { 'Content-Type': 'application/json', Accept: 'application/json' };

export function getJson(url, params) {
  const res = http.get(url, Object.assign({ headers: { Accept: 'application/json' } }, params || {}));
  if (res.status !== 200) {
    fail(`GET ${url} → ${res.status} ${res.body && String(res.body).slice(0, 200)}`);
  }
  return res.json();
}

/** Published events with their upcoming shows that start at least `minHoursAhead` from now (holdable, refundable). */
export function discoverShows(maxEvents, minHoursAhead) {
  const page = getJson(`${API}/events?limit=${Math.min(maxEvents, 50)}`);
  const cutoff = Date.now() + minHoursAhead * 3600 * 1000;
  const events = [];
  const shows = [];
  for (const e of page.items) {
    events.push(e.id);
    const detail = getJson(`${API}/events/${e.id}`);
    for (const s of detail.upcomingShows) {
      if (Date.parse(s.startsAt) > cutoff) {
        shows.push(s.id);
      }
    }
  }
  if (events.length === 0 || shows.length === 0) {
    fail('No published events / upcoming shows found: is the stack seeded and running?');
  }
  return { events, shows };
}

/**
 * Creates `count` fresh users (register + login) in parallel batches and returns their access tokens.
 * E-mails are unique per run (k6-<run>-<n>@seatlock.test), so runs never collide.
 */
export function createUsers(count, runId) {
  const password = 'k6-load-test-pass';
  const tokens = [];
  const batchSize = 25;   // BCrypt is CPU-bound: bigger batches only queue on the server
  for (let start = 0; start < count; start += batchSize) {
    const ids = [];
    for (let i = start; i < Math.min(start + batchSize, count); i++) ids.push(i);
    const reg = http.batch(ids.map((i) => ['POST', `${API}/auth/register`,
      JSON.stringify({ email: `k6-${runId}-${i}@seatlock.test`, password, displayName: `k6 user ${i}` }),
      { headers: JSON_HEADERS, tags: { name: 'setup register' } }]));
    reg.forEach((r, n) => { if (r.status !== 201) fail(`register ${ids[n]} → ${r.status} ${r.body}`); });
    const login = http.batch(ids.map((i) => ['POST', `${API}/auth/login`,
      JSON.stringify({ email: `k6-${runId}-${i}@seatlock.test`, password }),
      { headers: JSON_HEADERS, tags: { name: 'setup login' } }]));
    login.forEach((r, n) => {
      if (r.status !== 200) fail(`login ${ids[n]} → ${r.status} ${r.body}`);
      tokens.push(r.json('accessToken'));
    });
  }
  return tokens;
}

/** A distinct client IP per simulated user, from the benchmarking range 198.18.0.0/15 (RFC 2544). */
export function fakeClientIp(n) {
  return `198.18.${Math.floor(n / 250) % 256}.${(n % 250) + 1}`;
}

/** Idempotency-Key: 8–64 chars of [A-Za-z0-9_-], unique per attempt. */
export function idempotencyKey(vu, attempt) {
  return `k6-${vu}-${attempt}-${Date.now()}-${Math.floor(Math.random() * 1e9)}`;
}

/** Compact text summary (no jslib): p50/p95/p99 per tagged request name, plus the counters given. */
export function textSummary(data, title, counters) {
  const lines = [`\n=== ${title} ===`];
  const ms = (v) => (v === undefined ? '-' : `${v.toFixed(1)} ms`);
  for (const [name, m] of Object.entries(data.metrics)) {
    if (m.type === 'trend' && (name === 'http_req_duration' || name.startsWith('http_req_duration{'))) {
      const v = m.values;
      lines.push(`${name.padEnd(58)} p50 ${ms(v.med)}  p95 ${ms(v['p(95)'])}  p99 ${ms(v['p(99)'])}  max ${ms(v.max)}`);
    }
  }
  const reqs = data.metrics.http_reqs && data.metrics.http_reqs.values;
  if (reqs) lines.push(`http_reqs: ${reqs.count} (${reqs.rate.toFixed(1)}/s)`);
  const failed = data.metrics.http_req_failed && data.metrics.http_req_failed.values;
  if (failed) lines.push(`http_req_failed: ${(failed.rate * 100).toFixed(2)} %`);
  for (const c of counters || []) {
    const m = data.metrics[c];
    lines.push(`${c}: ${m ? m.values.count : 0}`);
  }
  const broken = [];
  for (const [name, m] of Object.entries(data.metrics)) {
    for (const [expr, t] of Object.entries(m.thresholds || {})) {
      if (!t.ok) broken.push(`${name}: ${expr}`);
    }
  }
  lines.push(broken.length ? `THRESHOLDS FAILED:\n  ${broken.join('\n  ')}` : 'All thresholds passed');
  return lines.join('\n') + '\n';
}
