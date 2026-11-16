-- Release a hold: delete only the keys that still belong to it.
-- A seat key whose TTL ran out may already belong to someone else's newer hold; comparing the value
-- with our holdId (instead of a blind DEL) means we never release a seat we no longer own.
--
-- KEYS[1..n] seat keys   KEYS[n+1] user-show key   KEYS[n+2] hold record key
-- ARGV[1] holdId
-- Returns the number of seat keys released.

local n = #KEYS - 2
local holdId = ARGV[1]
local released = 0

for i = 1, n do
  if redis.call('GET', KEYS[i]) == holdId then
    redis.call('DEL', KEYS[i])
    released = released + 1
  end
end
if redis.call('GET', KEYS[n + 1]) == holdId then
  redis.call('DEL', KEYS[n + 1])
end
redis.call('DEL', KEYS[n + 2])
return released
