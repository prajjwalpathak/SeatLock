package com.seatlock.payment.api;

import static com.seatlock.payment.support.PaymentFixtures.checkoutSignature;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatlock.identity.domain.UserRepository;
import com.seatlock.identity.security.TokenService;
import com.seatlock.payment.support.PaymentFixtures;
import com.seatlock.payment.support.PaymentFixtures.Order;
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

/** POST /api/v1/payments/razorpay/verify: the checkout callback, signature first. */
@IntegrationTest
class PaymentVerifyIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired UserRepository users;
    @Autowired TokenService tokenService;

    PaymentFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new PaymentFixtures(mvc, jdbc, redis, users, tokenService);
    }

    @AfterEach
    void cleanUp() {
        fx.cleanUp();
    }

    @Test
    void aValidSignatureConfirmsTheBooking() throws Exception {
        Order o = fx.razorpayOrder(fx.newUserToken("alice"));

        verify(o.token(), o.orderId(), "pay_OK0000000001", checkoutSignature(o.orderId(), "pay_OK0000000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("CONFIRMED"))
                .andExpect(jsonPath("$.booking.publicId").value(o.bookingId()))
                .andExpect(jsonPath("$.booking.status").value("CONFIRMED"));
        assertThat(fx.paymentStatus(o.orderId())).isEqualTo("CAPTURED");

        verify(o.token(), o.orderId(), "pay_OK0000000001", checkoutSignature(o.orderId(), "pay_OK0000000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ALREADY_DONE"));
    }

    @Test
    void aForgedSignatureChangesNothing() throws Exception {
        Order o = fx.razorpayOrder(fx.newUserToken("alice"));

        verify(o.token(), o.orderId(), "pay_OK0000000001", checkoutSignature(o.orderId(), "pay_OTHER0000001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAYMENT_SIGNATURE"));
        verify(o.token(), o.orderId(), "pay_OK0000000001", "0".repeat(64))
                .andExpect(status().isBadRequest());

        assertThat(fx.bookingStatus(o.bookingId())).isEqualTo("PAYMENT_PENDING");
        assertThat(fx.paymentStatus(o.orderId())).isEqualTo("CREATED");
    }

    @Test
    void someoneElsesOrderIsNotFoundEvenWithAValidSignature() throws Exception {
        Order o = fx.razorpayOrder(fx.newUserToken("alice"));
        String bob = fx.newUserToken("bob");

        verify(bob, o.orderId(), "pay_OK0000000001", checkoutSignature(o.orderId(), "pay_OK0000000001"))
                .andExpect(status().isNotFound());
        assertThat(fx.bookingStatus(o.bookingId())).isEqualTo("PAYMENT_PENDING");
    }

    @Test
    void missingFieldsAre400AndAnonymousIs401() throws Exception {
        Order o = fx.razorpayOrder(fx.newUserToken("alice"));

        mvc.perform(post("/api/v1/payments/razorpay/verify").header(HttpHeaders.AUTHORIZATION, "Bearer " + o.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"orderId\":\"" + o.orderId() + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/payments/razorpay/verify").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions verify(String token, String orderId, String paymentId, String signature) throws Exception {
        return mvc.perform(post("/api/v1/payments/razorpay/verify")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"orderId":"%s","paymentId":"%s","signature":"%s"}""".formatted(orderId, paymentId, signature)));
    }
}
