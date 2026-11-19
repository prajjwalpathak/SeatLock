package com.seatlock.payment.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.seatlock.payment.support.PaymentFixtures.webhookBody;
import static com.seatlock.payment.support.PaymentFixtures.webhookSignature;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jayway.jsonpath.JsonPath;
import com.seatlock.identity.domain.UserRepository;
import com.seatlock.identity.security.TokenService;
import com.seatlock.payment.application.RefundProcessor;
import com.seatlock.payment.support.PaymentFixtures;
import com.seatlock.payment.support.PaymentFixtures.Order;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import testsupport.TestcontainersConfiguration;

/**
 * The whole app with {@code seatlock.payments.gateway=razorpay}, its base URL pointing at WireMock: pay → Razorpay
 * order over HTTP → signed webhook confirms → cancel → the refund worker calls Razorpay's Refund API. Exercises the
 * real wiring (config, adapter, timeouts, outbox) that the mock gateway skips. Own context (gateway differs).
 */
@SpringBootTest(properties = {
        "seatlock.payments.gateway=razorpay",
        "seatlock.payments.razorpay.key-id=rzp_test_wiremock",
        "seatlock.payments.razorpay.key-secret=" + PaymentFixtures.KEY_SECRET,
        "seatlock.payments.razorpay.webhook-secret=" + PaymentFixtures.WEBHOOK_SECRET,
        "seatlock.payments.razorpay.read-timeout=2s"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class RazorpayFlowIntegrationTest {

    static final WireMockServer RAZORPAY = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        RAZORPAY.start();
    }

    @DynamicPropertySource
    static void razorpayUrl(DynamicPropertyRegistry registry) {
        registry.add("seatlock.payments.razorpay.base-url", RAZORPAY::baseUrl);
    }

    @AfterAll
    static void stop() {
        RAZORPAY.stop();
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired UserRepository users;
    @Autowired TokenService tokenService;
    @Autowired RefundProcessor refunds;

    PaymentFixtures fx;
    String orderId;

    @BeforeEach
    void setUp() {
        RAZORPAY.resetAll();
        fx = new PaymentFixtures(mvc, jdbc, redis, users, tokenService);
        jdbc.update("UPDATE refunds SET status = 'FAILED', attempts = 8 WHERE status = 'PENDING'"); // other tests' leftovers
        orderId = "order_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        // Razorpay echoes what it was asked for; the order id is ours to choose per test
        RAZORPAY.stubFor(post("/v1/orders").willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"id":"%s","entity":"order","amount":{{jsonPath request.body '$.amount'}},"currency":"INR",
                         "receipt":"{{jsonPath request.body '$.receipt'}}","status":"created"}""".formatted(orderId))
                .withTransformers("response-template")));
    }

    @AfterEach
    void cleanUp() {
        fx.cleanUp();
        jdbc.update("DELETE FROM webhook_events WHERE event_id LIKE 'evt_flow_%'");
    }

    @Test
    void payConfirmCancelRefundAgainstRazorpayOverHttp() throws Exception {
        Order o = fx.mockOrder(fx.newUserToken("alice")); // "mock" = created through the API; the gateway is razorpay here
        assertThat(o.orderId()).isEqualTo(orderId);
        RAZORPAY.verify(1, postRequestedFor(urlEqualTo("/v1/orders"))
                .withHeader("Authorization", equalTo(basic()))
                .withRequestBody(matchingJsonPath("$.amount", equalTo(String.valueOf(o.amountPaise()))))
                .withRequestBody(matchingJsonPath("$.receipt", equalTo(o.bookingId().replace("-", "") + "-1"))));

        // the order response carries the public key for Checkout.js
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/bookings/{id}/payment-order", o.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + o.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gateway").value("RAZORPAY"))
                .andExpect(jsonPath("$.keyId").value("rzp_test_wiremock"));

        String paymentId = "pay_FLOW" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String body = webhookBody("payment.captured", orderId, paymentId, o.amountPaise());
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Razorpay-Signature", webhookSignature(body))
                        .header("X-Razorpay-Event-Id", "evt_flow_" + UUID.randomUUID()))
                .andExpect(status().isOk());
        assertThat(fx.bookingStatus(o.bookingId())).isEqualTo("CONFIRMED");

        String cancelled = mvc.perform(MockMvcRequestBuilders.post("/api/v1/bookings/{id}/refund", o.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + o.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.booking.status").value("REFUNDED"))
                .andReturn().getResponse().getContentAsString();
        long refundPaise = ((Number) JsonPath.read(cancelled, "$.refundPaise")).longValue();

        RAZORPAY.stubFor(get(urlPathEqualTo("/v1/payments/" + paymentId + "/refunds")).willReturn(okJson("{\"items\":[]}")));
        RAZORPAY.stubFor(post("/v1/payments/" + paymentId + "/refund").willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"id":"rfnd_FLOW00000001","entity":"refund","amount":{{jsonPath request.body '$.amount'}},
                         "payment_id":"%s","receipt":"{{jsonPath request.body '$.receipt'}}","status":"processed"}"""
                        .formatted(paymentId))
                .withTransformers("response-template")));

        assertThat(refunds.processDue()).isEqualTo(1);

        RAZORPAY.verify(1, postRequestedFor(urlEqualTo("/v1/payments/" + paymentId + "/refund"))
                .withHeader("Authorization", equalTo(basic()))
                .withRequestBody(matchingJsonPath("$.amount", equalTo(String.valueOf(refundPaise))))
                .withRequestBody(matchingJsonPath("$.speed", equalTo("normal"))));
        assertThat(jdbc.queryForObject("""
                SELECT r.gateway_refund_id FROM refunds r JOIN payments p ON p.id = r.payment_id WHERE p.gateway_order_id = ?""",
                String.class, orderId)).isEqualTo("rfnd_FLOW00000001");
        assertThat(fx.paymentStatus(orderId)).isEqualTo("REFUNDED");
    }

    @Test
    void razorpayDownMeans503AndNothingStored() throws Exception {
        RAZORPAY.stubFor(post("/v1/orders").willReturn(aResponse().withStatus(503)));
        String token = fx.newUserToken("alice");

        // hold + booking work without Razorpay; only the payment order needs it
        String bookingId = bookingWithoutOrder(token);
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/bookings/{id}/payment-order", bookingId).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PAYMENTS_UNAVAILABLE"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM payments p JOIN bookings b ON b.id = p.booking_id WHERE b.public_id = ?::uuid""",
                Integer.class, bookingId)).isZero();
        assertThat(fx.bookingStatus(bookingId)).as("still payable later").isEqualTo("HELD");
    }

    private String bookingWithoutOrder(String token) throws Exception {
        long seat = jdbc.queryForObject("""
                SELECT seat_id FROM show_seats WHERE show_id = ? AND status = 'AVAILABLE' ORDER BY seat_id LIMIT 1""",
                Long.class, fx.showId());
        String hold = mvc.perform(MockMvcRequestBuilders.post("/api/v1/shows/{id}/holds", fx.showId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seatIds\":[" + seat + "]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String booking = mvc.perform(MockMvcRequestBuilders.post("/api/v1/holds/{id}/booking", (String) JsonPath.read(hold, "$.holdId"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(booking, "$.publicId");
        fx.track(seat);
        return id;
    }

    private static String basic() {
        return "Basic " + java.util.Base64.getEncoder().encodeToString(
                ("rzp_test_wiremock:" + PaymentFixtures.KEY_SECRET).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
