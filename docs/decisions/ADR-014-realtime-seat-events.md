# ADR-014: Live seat events over STOMP, secured on CONNECT

- **Status:** Accepted (W5·D1); relay and resync added W5·D2 (section "W5·D2 update").
- **Context:** Seat-map viewers need seat changes pushed within a second, from every path that changes a seat
  (holds, release, booking, payment confirm, expiry, cancel, refund), across 2 instances later. Our API is
  stateless with RS256 JWTs; browsers can't put an `Authorization` header on the WebSocket handshake.

## Decision
1. **Spring WebSocket + STOMP** at `/ws` (native WebSocket, no SockJS), in-memory simple broker for `/topic`,
   10 s server/client heart-beats (Nginx's 60 s read timeout). One topic per show: `/topic/shows/{id}/seats`.
2. **Observer via domain events.** `SeatsChangedEvent` is published where seats change:
   catalog's `ShowSeatStatusService` (every DB status change, seats taken from `UPDATE … RETURNING seat_id`)
   and `HoldService` (hold created, released by the user). `SeatEventBroadcaster` listens with
   `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true)`: rolled-back changes are never sent;
   Redis holds (no transaction) are sent at once. Converting a hold into a booking drops its keys through
   `releaseConvertedHold`, which publishes nothing (the booking's HELD is the truth).
3. **Hold expiry publishes nothing.** HELD messages from holds carry `holdExpiresAt`; clients expire them locally.
4. **Per-show version** from Redis `INCR realtime:seat-version:show:{id}` (shared by all instances). Clients
   resync on a gap or on a smaller number (Redis restarted; no persistence in prod).
5. **Broadcasting is best effort:** failures are logged, never surfaced to the user whose change committed.
6. **Security on the STOMP layer** with our own inbound `ChannelInterceptor`:
   - CONNECT: `Authorization: Bearer <jwt>` optional (seat maps are public); present but invalid → ERROR
     `INVALID_TOKEN`. Validated by identity's `TokenAuthenticator` (same decoder/validators/roles as REST).
   - SUBSCRIBE: only `/topic/shows/{id}/seats` (`SUBSCRIPTION_DENIED`), at most 10 per connection
     (`TOO_MANY_SUBSCRIPTIONS`). SEND: always `SEND_NOT_ALLOWED` (no forged events into `/topic`).
   - ERROR frames carry the code in `message`; unexpected failures → `INTERNAL_ERROR` without details.
   - Handshake: Origin must be in `seatlock.cors.allowed-origins` (403 otherwise); `/ws` is `permitAll` in HTTP security.

## Alternatives rejected
- **`@EnableWebSocketSecurity`:** requires a CSRF token on CONNECT (meant for cookie sessions) that can only be
  disabled by overriding an internal bean; our rules fit in one small, tested interceptor.
- **Token in the handshake query string:** ends up in access logs and browser history.
- **Version column on `shows`** (`UPDATE … RETURNING` in the same transaction): commit-ordered, but a hot row
  lock on every hold/booking of a show, exactly where the 200-user races contend.
- **Redis keyspace notifications for hold expiry:** fire-and-forget, per instance, delayed; client-side expiry is simpler.

## Consequences
- Versions are taken after commit, so two commits on one show can (rarely) get versions in the opposite order;
  the per-seat state is still correct after the client's resync (W5·D2/D3). Documented in CORRECTNESS section 1n.
- The JWT is checked only at CONNECT. Fine while all topics are public; user destinations would need a
  check on SUBSCRIBE and a disconnect at token expiry.
- Spring logs rejected frames at ERROR (`StompSubProtocolHandler`); expected in tests.

## W5·D2 update: relay across instances, resync
7. **One Redis Pub/Sub channel** `realtime:seat-events` for all shows. `publish_seat_event.lua` does `INCR` +
   `PUBLISH "<version>|<json>"` atomically, so Redis delivers events in version order to every instance.
   Each instance forwards everything it receives (its own events too: one path, one order) to its simple broker,
   on **one** dedicated thread (the container's default thread-per-message executor could reorder), and the broker
   preserves publish order per session (`setPreservePublishOrder(true)`). The executor is not a bean (an `Executor`
   bean would make Boot drop its application task executor).
8. **Resync = the seat map.** `GET /api/v1/shows/{id}/seats` returns `version`, read **before** the statuses
   (catalog port `SeatEventVersionLookup`, implemented in realtime). Client: load the map, apply only messages with
   a greater version; on a gap, a smaller version, or a reconnect, load the map again. Redis down → `version: null`,
   `holdsLive: false`.
   - Why it loses nothing: a version is assigned after its change committed; so every event ≤ the map's version
     is already in the statuses read afterwards. Events above it are re-applied, which is harmless because each one
     states the seats' new status.
9. Rejected: Redis Streams (replayable, but consumer groups/trimming for a portfolio app; resync already covers loss),
   per-show channels with `PSUBSCRIBE` (more pattern matching per message for no gain at 2 instances), a dedicated
   resync endpoint (the seat map already is one; one request on load and on resync).

## W5·D3 update: browser client
10. **One STOMP connection per tab** (`frontend/src/lib/stompClient.ts`, `@stomp/stompjs` 7), ref-counted, closed 10 s
    after the last live view; JWT from storage in CONNECT, a refused token is not resent (anonymous instead);
    reconnect 1 s → 30 s exponential with jitter; 10 s heart-beats both ways.
11. **Subscribe, then load:** every (re)subscription reloads the seat map; events are written into the TanStack Query
    cache by version (`applySeatEvent`): ≤ map.version ignore, next one apply, gap reload, `version: null` reload.
12. **Counter restarts** (Redis restart) are detected by a version smaller than one already received, or by a
    message ≤ map.version that happened > 5 s after the map was loaded (server clock); both reload the map.
13. **Hold expiry on the client:** HELD-by-hold seats turn AVAILABLE at `holdExpiresAt` (server clock, 1 s tick while
    any is pending). Refetch on tab focus stays as an extra resync after sleep.
