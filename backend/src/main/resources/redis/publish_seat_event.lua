-- Numbers and publishes one seat event atomically, so Pub/Sub delivery order = version order on every instance.
-- KEYS[1] = realtime:seat-version:show:{showId}   ARGV[1] = channel   ARGV[2] = event JSON (without version)
-- Message on the wire: "<version>|<json>". Returns the version.
local version = redis.call('INCR', KEYS[1])
redis.call('PUBLISH', ARGV[1], version .. '|' .. ARGV[2])
return version
