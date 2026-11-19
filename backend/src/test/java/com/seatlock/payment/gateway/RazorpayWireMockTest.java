package com.seatlock.payment.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Razorpay adapter against a real HTTP server (WireMock): real sockets, real JDK HttpClient, real timeouts and
 * connection faults, which MockRestServiceServer (RazorpayGatewayTest) can't simulate.
 */
class RazorpayWireMockTest {

    static final String KEY_ID = "rzp_test_wiremock";
    static final String SECRET = "wm-secret";
    static final String BASIC = "Basic " + Base64.getEncoder()
            .encodeToString((KEY_ID + ":" + SECRET).getBytes(StandardCharsets.UTF_8));

    static WireMockServer razorpay;
    RazorpayGateway gateway;

    @BeforeAll
    static void start() {
        razorpay = new WireMockServer(wireMockConfig().dynamicPort());
        razorpay.start();
    }

    @AfterAll
    static void stop() {
        razorpay.stop();
    }

    @BeforeEach
    void setUp() {
        razorpay.resetAll();
        gateway = RazorpayGateway.create(new RazorpayProperties(KEY_ID, SECRET, "wh", URI.create(razorpay.baseUrl()),
                Duration.ofSeconds(1), Duration.ofMillis(800)));
    }

    @Test
    void createsAnOrderOverRealHttp() {
        razorpay.stubFor(post("/v1/orders")
                .withHeader("Authorization", equalTo(BASIC))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("""
                        {"amount": 75000, "currency": "INR", "receipt": "abc-1"}"""))
                .willReturn(okJson("""
                        {"id":"order_WM000000000001","entity":"order","amount":75000,"amount_paid":0,"currency":"INR",
                         "receipt":"abc-1","status":"created","notes":[],"created_at":1764583200}""")));

        GatewayOrder order = gateway.createOrder(new CreateOrderCommand("abc-1", 75_000, "INR"));

        assertThat(order.orderId()).isEqualTo("order_WM000000000001");
        razorpay.verify(1, postRequestedFor(urlEqualTo("/v1/orders")));
    }

    @Test
    void aSlowRazorpayTimesOutInsteadOfHangingTheRequest() {
        razorpay.stubFor(post("/v1/orders").willReturn(okJson("{}").withFixedDelay(3_000)));

        long start = System.nanoTime();
        assertThatThrownBy(() -> gateway.createOrder(new CreateOrderCommand("abc-1", 100, "INR")))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("unreachable");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).as("read timeout 800 ms, not 3 s")
                .isLessThan(Duration.ofMillis(2_500));
    }

    @Test
    void connectionFaultsAndGarbageBecomeGatewayExceptions() {
        razorpay.stubFor(post("/v1/orders").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        assertThatThrownBy(() -> gateway.createOrder(new CreateOrderCommand("abc-1", 100, "INR")))
                .isInstanceOf(PaymentGatewayException.class);

        razorpay.stubFor(post("/v1/orders").willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));
        assertThatThrownBy(() -> gateway.createOrder(new CreateOrderCommand("abc-1", 100, "INR")))
                .isInstanceOf(PaymentGatewayException.class);

        razorpay.stubFor(post("/v1/orders").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{not json")));
        assertThatThrownBy(() -> gateway.createOrder(new CreateOrderCommand("abc-1", 100, "INR")))
                .isInstanceOf(PaymentGatewayException.class);
    }

    @Test
    void aRazorpayErrorCarriesItsCodeButNotTheSecret() {
        razorpay.stubFor(post("/v1/orders").willReturn(aResponse().withStatus(401)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"Authentication failed\"}}")));

        assertThatThrownBy(() -> gateway.createOrder(new CreateOrderCommand("abc-1", 100, "INR")))
                .hasMessageContaining("401").hasMessageContaining("BAD_REQUEST_ERROR").hasMessageNotContaining(SECRET);
    }

    @Test
    void refundLooksUpTheReceiptThenCreates() {
        razorpay.stubFor(get(urlPathEqualTo("/v1/payments/pay_WM1/refunds"))
                .willReturn(okJson("{\"entity\":\"collection\",\"count\":0,\"items\":[]}")));
        razorpay.stubFor(post("/v1/payments/pay_WM1/refund")
                .withRequestBody(equalToJson("""
                        {"amount": 5000, "speed": "normal", "receipt": "rfWM"}"""))
                .willReturn(okJson("""
                        {"id":"rfnd_WM000000000001","entity":"refund","amount":5000,"payment_id":"pay_WM1",
                         "receipt":"rfWM","status":"processed"}""")));

        assertThat(gateway.refund(new RefundCommand("pay_WM1", 5000, "rfWM")).refundId()).isEqualTo("rfnd_WM000000000001");
        razorpay.verify(1, getRequestedFor(urlPathEqualTo("/v1/payments/pay_WM1/refunds")));
        razorpay.verify(1, postRequestedFor(urlEqualTo("/v1/payments/pay_WM1/refund")));
    }

    @Test
    void aDroppedLookupConnectionIsRetriedAndAnExistingRefundIsNeverSentAgain() {
        razorpay.stubFor(get(urlPathEqualTo("/v1/payments/pay_WM1/refunds")).inScenario("flaky")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))
                .willSetStateTo("up"));
        razorpay.stubFor(get(urlPathEqualTo("/v1/payments/pay_WM1/refunds")).inScenario("flaky")
                .whenScenarioStateIs("up")
                .willReturn(okJson("""
                        {"items":[{"id":"rfnd_EARLIER","amount":5000,"receipt":"rfWM","status":"processed"}]}""")));

        assertThat(gateway.refund(new RefundCommand("pay_WM1", 5000, "rfWM")).refundId()).isEqualTo("rfnd_EARLIER");
        razorpay.verify(2, getRequestedFor(urlPathEqualTo("/v1/payments/pay_WM1/refunds")));
        razorpay.verify(0, postRequestedFor(urlEqualTo("/v1/payments/pay_WM1/refund")));
    }
}
