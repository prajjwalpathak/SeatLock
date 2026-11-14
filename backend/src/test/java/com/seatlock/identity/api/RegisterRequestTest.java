package com.seatlock.identity.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RegisterRequestTest {

    @Test
    void toStringNeverContainsThePassword() {
        var request = new RegisterRequest("a@example.com", "hunter2-hunter2", "A");
        assertThat(request.toString()).doesNotContain("hunter2").contains("password=***");
    }
}
