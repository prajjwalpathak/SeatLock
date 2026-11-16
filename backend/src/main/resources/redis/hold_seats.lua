-- Atomically hold N seats of one show for one user, all or nothing.
-- Redis runs a script without interleaving other commands, so "check all, then set all" can't race.
--
-- KEYS[1..n]  seat keys        hold:show:{showId}:seat:{seatId}
-- KEYS[n+1]   user-show key    hold:user:{userId}:show:{showId}
-- KEYS[n+2]   hold record key  hold:id:{holdId}
-- ARGV[1] holdId   ARGV[2] ttl (ms)   ARGV[3] userId   ARGV[4] showId
-- ARGV[5] seatIds as CSV (same order as the seat keys)   ARGV[6] expiresAt (epoch ms)
--
-- Returns (all strings):
--   {"OK"}
--   {"HOLD_EXISTS", existingHoldId}        the user already holds seats for this show
--   {"SEATS_TAKEN", "2", "5", ...}         1-based positions of seat keys already held (nothing written)

local n = #KEYS - 2
local userKey = KEYS[n + 1]
local recordKey = KEYS[n + 2]
local holdId = ARGV[1]
local ttl = tonumber(ARGV[2])

local existing = redis.call('GET', userKey)
if existing then
  return { 'HOLD_EXISTS', existing }
end

local taken = {}
for i = 1, n do
  if redis.call('EXISTS', KEYS[i]) == 1 then
    taken[#taken + 1] = tostring(i)
  end
end
if #taken > 0 then
  table.insert(taken, 1, 'SEATS_TAKEN')
  return taken
end

for i = 1, n do
  redis.call('SET', KEYS[i], holdId, 'PX', ttl)
end
redis.call('SET', userKey, holdId, 'PX', ttl)
redis.call('HSET', recordKey, 'userId', ARGV[3], 'showId', ARGV[4], 'seatIds', ARGV[5], 'expiresAt', ARGV[6])
redis.call('PEXPIRE', recordKey, ttl)
return { 'OK' }
