package com.seatlock.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Contract tests for the Razorpay adapter: the exact request Razorpay's Orders API expects, and how every kind
 * of answer is handled. No network: the RestClient is bound to a mock server. (WireMock over real HTTP: W4·D5.)
 */
class RazorpayGatewayTest {

    static final String KEY_ID = "rzp_test_abc123";
    static final String SECRET = "s3cret";

    static RazorpayProperties props(String keyId, String secret) {
        return new RazorpayProperties(keyId, secret, "whsec", URI.create("https://api.razorpay.test"),
                Duration.ofSeconds(2), Duration.ofSeconds(5));
    }

    MockRestServiceServer server;
    RazorpayGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RazorpayGateway.clientBuilder(props(KEY_ID, SECRET));
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new RazorpayGateway(builder.build(), KEY_ID);
    }

    @Test
    void createsAnOrderWithBasicAuthAndTheServerSideAmount() {
        String basic = "Basic " + Base64.getEncoder()
                .encodeToString((KEY_ID + ":" + SECRET).getBytes(StandardCharsets.UTF_8));
        server.expect(requestTo("https://api.razorpay.test/v1/orders"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", basic))
                .andExpect(content().json("""
                        {"amount": 150000, "currency": "INR", "receipt": "abc-1"}
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {"id": "order_Rz123456789012", "entity": "order", "amount": 150000, "amount_paid": 0,
                         "amount_due": 150000, "currency": "INR", "receipt": "abc-1", "status": "created",
                         "attempts": 0, "notes": [], "created_at": 1764583200}
                        """, MediaType.APPLICATION_JSON));

        GatewayOrder order = gateway.createOrder(new CreateOrderCommand("abc-1", 150_000, "INR"));

        assertThat(order.gateway()).isEqualTo(GatewayName.RAZORPAY);
        assertThat(order.orderId()).isEqualTo("order_Rz123456789012");
        assertThat(order.amountPaise()).isEqualTo(150_000);
        assertThat(order.receipt()).isEqualTo("abc-1");
        server.verify();
    }

    @Test
    void aRazorpayErrorBecomesAGatewayExceptionWithItsCode() {
        server.expect(requestTo("https://api.razorpay.test/v1/orders"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON).body("""
                        {"error": {"code": "BAD_REQUEST_ERROR", "description": "Authentication failed"}}
                        """));

        assertThatThrownBy(() -> gateway.createOrder(new CreateOrderCommand("abc-1", 100, "INR")))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("401")
                .hasMessageContaining("BAD_REQUEST_ERROR")
                .hasMessageNotContaining(SECRET);
    }

    @Test
    void serverErrorsAndTimeoutsBecomeGatewayExceptions() {
        server.expect(requestTo("https://api.razorpay.test/v1/orders")).andRespond(withServerError());
        assertThatThrownBy(() -> gateway.createOrder(new CreateOrderCommand("abc-1", 100, "INR")))
                .isInstanceOf(PaymentGatewayException.class);

        server.reset();
        server.expect(requestTo("https://api.razorpay.test/v1/orders"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));
        assertThatThrownBy(() -> gateway.createOrder(new CreateOrderCommand("abc-1", 100, "INR")))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("unreachable");
    }

    @Test
    void anOrderForADifferentAmountIsRejected() {
        server.expect(requestTo("https://api.razorpay.test/v1/orders"))
                .andRespond(withSuccess("""
                        {"id": "order_Rz123456789012", "amount": 1, "currency": "INR", "receipt": "abc-1"}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.createOrder(new CreateOrderCommand("abc-1", 100, "INR")))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("expected 100");
    }

    @Test
    void aResponseWithoutAnOrderIdIsRejected() {
        server.expect(requestTo("https://api.razorpay.test/v1/orders"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.createOrder(new CreateOrderCommand("abc-1", 100, "INR")))
                .isInstanceOf(PaymentGatewayException.class);
    }

    @Test
    void configNeedsTestModeKeys() {
        assertThatThrownBy(() -> PaymentGatewayConfig.select(GatewayName.RAZORPAY, props("", "")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("RAZORPAY_KEY_ID");
        assertThatThrownBy(() -> PaymentGatewayConfig.select(GatewayName.RAZORPAY, new RazorpayProperties(KEY_ID, SECRET,
                "", URI.create("https://api.razorpay.test"), Duration.ofSeconds(2), Duration.ofSeconds(5))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("RAZORPAY_WEBHOOK_SECRET");
        assertThatThrownBy(() -> PaymentGatewayConfig.select(GatewayName.RAZORPAY, props("rzp_live_x", "s")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("test-mode");

        PaymentGateway selected = PaymentGatewayConfig.select(GatewayName.RAZORPAY, props(KEY_ID, SECRET));
        assertThat(selected).isInstanceOf(RazorpayGateway.class);
        assertThat(((RazorpayGateway) selected).keyId()).isEqualTo(KEY_ID);
        assertThat(props(KEY_ID, SECRET).toString()).doesNotContain(SECRET);
    }
}
