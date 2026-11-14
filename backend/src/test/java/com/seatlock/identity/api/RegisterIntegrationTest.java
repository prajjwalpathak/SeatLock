package com.seatlock.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatlock.identity.domain.User;
import com.seatlock.identity.domain.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import testsupport.IntegrationTest;

/** Each test uses a unique e-mail: the Testcontainers DB is shared by all integration tests. */
@IntegrationTest
class RegisterIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;

    @Test
    void registersUserWithNormalizedEmailAndBcryptHash() throws Exception {
        String local = "reg-" + UUID.randomUUID();
        register(local.toUpperCase() + "@Example.COM", "s3cret-passphrase", "  Asha Rao ")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/me"))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.email").value(local + "@example.com"))
                .andExpect(jsonPath("$.displayName").value("Asha Rao"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(content().string(not(containsString("bcrypt"))));

        User saved = users.findByEmail(local + "@example.com").orElseThrow();
        assertThat(saved.getPasswordHash()).startsWith("{bcrypt}").doesNotContain("s3cret-passphrase");
    }

    @Test
    void duplicateEmailInAnyLetterCaseIs409() throws Exception {
        String email = "dup-" + UUID.randomUUID() + "@example.com";
        register(email, "password-one", "First").andExpect(status().isCreated());

        register(email.toUpperCase(), "password-two", "Second")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
    }

    @Test
    void invalidFieldsAre400WithFieldErrors() throws Exception {
        register("not-an-email", "short", " ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'email')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'password')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'displayName')]").exists());
    }

    @Test
    void passwordOver72BytesIsRejectedEvenIfUnder72Characters() throws Exception {
        String emojiPassword = "\uD83C\uDF9F".repeat(20); // 20 ticket emoji: 40 Java chars, 80 bytes in UTF-8
        register("long-" + UUID.randomUUID() + "@example.com", emojiPassword, "Emoji Fan")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_TOO_LONG"));
    }

    @Test
    void registrationIsPublicButOtherAuthPostsAreNot() throws Exception {
        mvc.perform(post("/api/v1/auth/something-else").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions register(String email, String password, String displayName) throws Exception {
        String json = """
                {"email":"%s","password":"%s","displayName":"%s"}""".formatted(email, password, displayName);
        return mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
