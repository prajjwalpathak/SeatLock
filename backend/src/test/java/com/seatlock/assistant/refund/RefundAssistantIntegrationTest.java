package com.seatlock.assistant.refund;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatlock.assistant.rag.PolicyIngestionService;
import com.seatlock.identity.domain.UserRepository;
import com.seatlock.identity.security.TokenService;
import com.seatlock.payment.application.PaymentCaptureService;
import com.seatlock.payment.gateway.GatewayName;
import com.seatlock.payment.support.PaymentFixtures;
import com.seatlock.payment.support.PaymentFixtures.Order;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import testsupport.IntegrationTest;

/**
 * Real bookings, real refund policy, real (hashing) retrieval, no model: the template answer must carry the
 * engine's exact amounts, and ownership must hold.
 */
@IntegrationTest
class RefundAssistantIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired UserRepository users;
    @Autowired TokenService tokenService;
    @Autowired PaymentCaptureService capture;
    @Autowired PolicyIngestionService ingestion;

    private PaymentFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new PaymentFixtures(mvc, jdbc, redis, users, tokenService);
        ingestion.ingestAll();
    }

    @AfterEach
    void cleanUp() {
        fx.cleanUp();
    }

    private ResultActions ask(String token, String bookingId, String question) throws Exception {
        var request = post("/api/v1/bookings/{id}/assistant", bookingId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"" + question + "\"}");
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mvc.perform(request);
    }

    @Test
    void paidBookingGetsTheEngineAmountsInTheTemplateWithPolicyExcerpts() throws Exception {
        Order o = fx.mockOrder(fx.newUserToken("alice"));
        capture.capture(GatewayName.MOCK, o.orderId(), "pay_" + UUID.randomUUID().toString().substring(0, 12), o.amountPaise());
        String full = Money.rupees(o.amountPaise());
        String half = Money.rupees(o.amountPaise() * 50 / 100);

        ask(o.token(), o.bookingId(), "How much do I get back if I cancel?")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("TEMPLATE"))
                .andExpect(jsonPath("$.fallbackReason").value("DISABLED"))
                .andExpect(jsonPath("$.situation").value("PAID_CANCELLABLE"))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("100% back: " + full)))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("50% (" + half + ")")))
                .andExpect(jsonPath("$.sources.length()").value(3));
    }

    @Test
    void unpaidBookingCanBeCancelledForFree() throws Exception {
        Order o = fx.mockOrder(fx.newUserToken("bob"));

        ask(o.token(), o.bookingId(), "Can I cancel?")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situation").value("UNPAID"))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("at no cost")));
    }

    @Test
    void someoneElsesBookingIs404AndAnonymousIs401() throws Exception {
        Order o = fx.mockOrder(fx.newUserToken("carol"));

        ask(fx.newUserToken("mallory"), o.bookingId(), "Can I cancel?").andExpect(status().isNotFound());
        ask(null, o.bookingId(), "Can I cancel?").andExpect(status().isUnauthorized());
        ask(o.token(), o.bookingId(), "x".repeat(301)).andExpect(status().isBadRequest());
    }
}
