package com.seatlock.payment.api;

import static com.seatlock.payment.support.PaymentFixtures.webhookBody;
import static com.seatlock.payment.support.PaymentFixtures.webhookSignature;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatlock.identity.domain.UserRepository;
import com.seatlock.identity.security.TokenService;
import com.seatlock.payment.support.PaymentFixtures;
import com.seatlock.payment.support.PaymentFixtures.Order;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import testsupport.IntegrationTest;

/** POST /api/v1/webhooks/razorpay: public, HMAC over the raw body, de-duplicated by event id. */
@IntegrationTest
class RazorpayWebhookIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired UserRepository users;
    @Autowired TokenService tokenService;

    PaymentFixtures fx;
    String eventId;

    @BeforeEach
    void setUp() {
        fx = new PaymentFixtures(mvc, jdbc, redis, users, tokenService);
        eventId = "evt_" + UUID.randomUUID();
    }

    @AfterEach
    void cleanUp() {
        fx.cleanUp();
        jdbc.update("DELETE FROM webhook_events WHERE event_id LIKE 'evt_%' OR event_id LIKE 'sha256:%'");
    }

    @Test
    void paymentCapturedConfirmsTheBookingWithoutAnyUser() throws Exception {
        Order o = fx.razorpayOrder(fx.newUserToken("alice"));
        String body = webhookBody("payment.captured", o.orderId(), "pay_WH0000000001", o.amountPaise());

        send(body, webhookSignature(body), eventId).andExpect(status().isOk());

        assertThat(fx.bookingStatus(o.bookingId())).isEqualTo("CONFIRMED");
        assertThat(fx.paymentStatus(o.orderId())).isEqualTo("CAPTURED");
        assertThat(outcome(eventId)).isEqualTo("CONFIRMED");
    }

    @Test
    void aRedeliveredEventIsSkipped() throws Exception {
        Order o = fx.razorpayOrder(fx.newUserToken("alice"));
        String body = webhookBody("payment.captured", o.orderId(), "pay_WH0000000001", o.amountPaise());

        send(body, webhookSignature(body), eventId).andExpect(status().isOk());
        send(body, webhookSignature(body), eventId).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_events WHERE event_id = ?", Integer.class, eventId))
                .isEqualTo(1);
        assertThat(outcome(eventId)).isEqualTo("CONFIRMED");
    }

    @Test
    void orderPaidAfterPaymentCapturedIsANoOp() throws Exception {
        Order o = fx.razorpayOrder(fx.newUserToken("alice"));
        String captured = webhookBody("payment.captured", o.orderId(), "pay_WH0000000001", o.amountPaise());
        String paid = webhookBody("order.paid", o.orderId(), "pay_WH0000000001", o.amountPaise());

        send(captured, webhookSignature(captured), eventId).andExpect(status().isOk());
        String second = "evt_" + UUID.randomUUID();
        send(paid, webhookSignature(paid), second).andExpect(status().isOk());

        assertThat(outcome(second)).isEqualTo("ALREADY_DONE");
        assertThat(fx.bookingStatus(o.bookingId())).isEqualTo("CONFIRMED");
    }

    @Test
    void aBadSignatureIs400AndStoresNothing() throws Exception {
        Order o = fx.razorpayOrder(fx.newUserToken("alice"));
        String body = webhookBody("payment.captured", o.orderId(), "pay_WH0000000001", o.amountPaise());
        String tampered = body.replace("\"amount\":" + o.amountPaise(), "\"amount\":1");

        send(tampered, webhookSignature(body), eventId).andExpect(status().isBadRequest());
        send(body, null, eventId).andExpect(status().isBadRequest());

        assertThat(fx.bookingStatus(o.bookingId())).isEqualTo("PAYMENT_PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_events WHERE event_id = ?", Integer.class, eventId))
                .isZero();
    }

    @Test
    void aWrongAmountIsRecordedButNeverConfirms() throws Exception {
        Order o = fx.razorpayOrder(fx.newUserToken("alice"));
        String body = webhookBody("payment.captured", o.orderId(), "pay_WH0000000001", o.amountPaise() - 100);

        send(body, webhookSignature(body), eventId).andExpect(status().isOk());

        assertThat(outcome(eventId)).isEqualTo("NEEDS_REVIEW");
        assertThat(fx.bookingStatus(o.bookingId())).isEqualTo("PAYMENT_PENDING");
    }

    @Test
    void unknownOrdersAndOtherEventsAreAcknowledged() throws Exception {
        String unknown = webhookBody("payment.captured", "order_NOTOURS0000001", "pay_WH0000000001", 100);
        send(unknown, webhookSignature(unknown), eventId).andExpect(status().isOk());
        assertThat(outcome(eventId)).isEqualTo("IGNORED_UNKNOWN_ORDER");

        String refund = webhookBody("refund.created", "order_NOTOURS0000001", "pay_WH0000000001", 100);
        String other = "evt_" + UUID.randomUUID();
        send(refund, webhookSignature(refund), other).andExpect(status().isOk());
        assertThat(outcome(other)).isEqualTo("IGNORED_EVENT_TYPE");
    }

    @Test
    void aFailedTryKeepsTheOrderOpenAndALaterPaymentStillConfirms() throws Exception {
        Order o = fx.razorpayOrder(fx.newUserToken("alice"));
        String failed = """
                {"entity":"event","event":"payment.failed","payload":{"payment":{"entity":{"id":"pay_FAIL000000001",
                 "amount":%d,"currency":"INR","status":"failed","order_id":"%s","error_code":"BAD_REQUEST_ERROR",
                 "error_description":"Payment failed at the bank"}}}}""".formatted(o.amountPaise(), o.orderId());

        send(failed, webhookSignature(failed), eventId).andExpect(status().isOk());

        assertThat(outcome(eventId)).isEqualTo("RECORDED_FAILED_ATTEMPT");
        assertThat(fx.paymentStatus(o.orderId())).as("Razorpay lets the user retry the same order").isEqualTo("CREATED");
        assertThat(jdbc.queryForObject("SELECT failure_reason FROM payments WHERE gateway_order_id = ?", String.class,
                o.orderId())).contains("pay_FAIL000000001").contains("failed at the bank");

        String captured = webhookBody("payment.captured", o.orderId(), "pay_OK00000000001", o.amountPaise());
        String second = "evt_" + UUID.randomUUID();
        send(captured, webhookSignature(captured), second).andExpect(status().isOk());
        assertThat(outcome(second)).isEqualTo("CONFIRMED");
        assertThat(fx.bookingStatus(o.bookingId())).isEqualTo("CONFIRMED");
    }

    @Test
    void aGetOnTheWebhookUrlIs405NotSignIn() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/webhooks/razorpay"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void withoutAnEventIdTheBodyHashDeduplicates() throws Exception {
        Order o = fx.razorpayOrder(fx.newUserToken("alice"));
        String body = webhookBody("payment.captured", o.orderId(), "pay_WH0000000001", o.amountPaise());

        send(body, webhookSignature(body), null).andExpect(status().isOk());
        send(body, webhookSignature(body), null).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_events WHERE event_id LIKE 'sha256:%'",
                Integer.class)).isEqualTo(1);
    }

    private String outcome(String id) {
        return jdbc.queryForObject("SELECT outcome FROM webhook_events WHERE event_id = ?", String.class, id);
    }

    private ResultActions send(String body, String signature, String id) throws Exception {
        var request = post("/api/v1/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).content(body);
        if (signature != null) {
            request.header("X-Razorpay-Signature", signature);
        }
        if (id != null) {
            request.header("X-Razorpay-Event-Id", id);
        }
        return mvc.perform(request);
    }
}
