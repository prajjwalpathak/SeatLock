package com.seatlock.common.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.SerializationException;
import tools.jackson.databind.json.JsonMapper;

class TypedJsonRedisSerializerTest {

    record Sample(long id, String name) {}

    final JsonMapper json = JsonMapper.builder().build();
    final TypedJsonRedisSerializer<Sample> serializer =
            new TypedJsonRedisSerializer<>(json, json.getTypeFactory().constructType(Sample.class));

    @Test
    void roundTrip() {
        assertThat(serializer.deserialize(serializer.serialize(new Sample(7, "x")))).isEqualTo(new Sample(7, "x"));
    }

    @Test
    void nullsAndEmptyAreNull() {
        assertThat(serializer.serialize(null)).isNull();
        assertThat(serializer.deserialize(null)).isNull();
        assertThat(serializer.deserialize(new byte[0])).isNull();
    }

    @Test
    void garbageOrForeignTypeInfoIsRejectedNotInstantiated() {
        byte[] garbage = "not json".getBytes(StandardCharsets.UTF_8);
        byte[] gadget = "[\"java.net.URL\",\"http://evil.example\"]".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> serializer.deserialize(garbage)).isInstanceOf(SerializationException.class);
        assertThatThrownBy(() -> serializer.deserialize(gadget)).isInstanceOf(SerializationException.class);
    }
}
