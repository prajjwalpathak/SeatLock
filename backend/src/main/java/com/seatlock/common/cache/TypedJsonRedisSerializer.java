package com.seatlock.common.cache;

import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON for ONE known type per cache. The type comes from our config, never from the stored
 * bytes, so Redis content can't make us instantiate arbitrary classes (the classic risk of
 * Jackson "default typing" / Java serialization in caches).
 */
final class TypedJsonRedisSerializer<T> implements RedisSerializer<T> {

    private final JsonMapper json;
    private final JavaType type;

    TypedJsonRedisSerializer(JsonMapper json, JavaType type) {
        this.json = json;
        this.type = type;
    }

    @Override
    public byte[] serialize(T value) {
        if (value == null) {
            return null;
        }
        try {
            return json.writeValueAsBytes(value);
        } catch (JacksonException ex) {
            throw new SerializationException("Cannot write " + type + " to the cache", ex);
        }
    }

    @Override
    public T deserialize(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            return json.readValue(bytes, type);
        } catch (JacksonException ex) {
            // e.g. an entry written by an older version of a DTO: treated as a miss by the error handler
            throw new SerializationException("Cannot read " + type + " from the cache", ex);
        }
    }
}
