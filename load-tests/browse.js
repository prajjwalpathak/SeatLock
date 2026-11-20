// k6 browse test (W6·D3): anonymous visitors list events, open one, open a seat map, think, repeat.
//   k6 run load-tests/browse.js                         (defaults: 50 VUs, ~3.5 min, local LB on 8088)
//   k6 run -e VUS=100 -e HOLD=5m load-tests/browse.js
// Runs on the laptop against the local 2-instance stack only, never against the VM (burst credits, #69).
import http from 'k6/http';
import { check, sleep } from 'k6';
import { API, discoverShows, textSummary } from './lib/common.js';

const VUS = Number(__ENV.VUS || 50);
const HOLD = __ENV.HOLD || '2m30s';
const CITIES = ['Delhi', 'Mumbai', 'Bengaluru', 'Pune'];

export const options = {
  scenarios: {
    browse: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: VUS },   // ramp up
        { duration: HOLD, target: VUS },    // steady state (the numbers we report)
        { duration: '20s', target: 0 },     // ramp down
      ],
      gracefulRampDown: '10s',
    },
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:GET /events}': ['p(95)<250', 'p(99)<500'],
    'http_req_duration{name:GET /events/{id}}': ['p(95)<250', 'p(99)<500'],
    'http_req_duration{name:GET /shows/{id}/seats}': ['p(95)<300', 'p(99)<600'],
    checks: ['rate>0.99'],
  },
};

export function setup() {
  return discoverShows(30, 1);
}

function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}

export default function (data) {
  // 1 in 3 visitors filter by city; the rest see the default list (the cached page).
  const query = Math.random() < 0.33 ? `?city=${pick(CITIES)}` : '';
  const list = http.get(`${API}/events${query}`, { tags: { name: 'GET /events' } });
  check(list, { 'events 200': (r) => r.status === 200 });
  sleep(1 + Math.random() * 2);

  const detail = http.get(`${API}/events/${pick(data.events)}`, { tags: { name: 'GET /events/{id}' } });
  check(detail, { 'event 200': (r) => r.status === 200 });
  sleep(1 + Math.random() * 2);

  const seats = http.get(`${API}/shows/${pick(data.shows)}/seats`, { tags: { name: 'GET /shows/{id}/seats' } });
  check(seats, { 'seat map 200': (r) => r.status === 200 });
  sleep(2 + Math.random() * 3);
}

export function handleSummary(data) {
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  return {
    stdout: textSummary(data, `browse: ${VUS} VUs`, []),
    [`load-tests/results/browse-${stamp}.json`]: JSON.stringify(data, null, 2),
  };
}
