package com.seatlock.payment.gateway;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.net.http.HttpClient;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Razorpay adapter over its REST API ({@code POST /v1/orders}, HTTP Basic with key ID + secret) using Spring's
 * {@link RestClient}: base URL and timeouts come from config, so a slow provider can't hold a request thread for
 * long, and contract tests can point it at a fake server. Every failure becomes a {@link PaymentGatewayException}
 * (→ 503 PAYMENTS_UNAVAILABLE); the response is checked against what we asked for before it is trusted.
 */
public class RazorpayGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(RazorpayGateway.class);

    private final RestClient http;
    private final String keyId;

    public RazorpayGateway(RestClient http, String keyId) {
        this.http = Objects.requireNonNull(http, "http");
        this.keyId = Objects.requireNonNull(keyId, "keyId");
    }

    /** Builder with base URL, Basic auth and timeouts. Tests bind a mock server to it before {@code build()}. */
    static RestClient.Builder clientBuilder(RazorpayProperties p) {
        HttpClient jdk = HttpClient.newBuilder().connectTimeout(p.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(p.readTimeout());
        return RestClient.builder()
                .baseUrl(p.baseUrl().toString())
                .requestFactory(factory)
                .defaultHeaders(h -> h.setBasicAuth(p.keyId(), p.keySecret()))
                .defaultHeader("User-Agent", "SeatLock/1.0");
    }

    static RazorpayGateway create(RazorpayProperties p) {
        return new RazorpayGateway(clientBuilder(p).build(), p.keyId());
    }

    @Override
    public GatewayName name() {
        return GatewayName.RAZORPAY;
    }

    /** Public key ID, needed by Checkout.js in the browser. */
    public String keyId() {
        return keyId;
    }

    @Override
    public GatewayOrder createOrder(CreateOrderCommand command) {
        OrderResponse order;
        try {
            order = http.post()
                    .uri("/v1/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new OrderRequest(command.amountPaise(), command.currency(), command.receipt()))
                    .retrieve()
                    .body(OrderResponse.class);
        } catch (RestClientResponseException e) {
            String error = errorCode(e);
            log.error("Razorpay create order failed: HTTP {} {}", e.getStatusCode().value(), error);
            throw new PaymentGatewayException("Razorpay returned HTTP " + e.getStatusCode().value() + " " + error, e);
        } catch (ResourceAccessException e) {
            throw new PaymentGatewayException("Razorpay unreachable: " + e.getMessage(), e);
        } catch (RestClientException e) {
            throw new PaymentGatewayException("Razorpay call failed: " + e.getMessage(), e);
        }
        return verified(order, command);
    }

    /** Never trust a provider response blindly: the order must be the one we asked for. */
    private static GatewayOrder verified(OrderResponse order, CreateOrderCommand command) {
        if (order == null || order.id() == null || !order.id().startsWith("order_")) {
            throw new PaymentGatewayException("Razorpay returned no order id");
        }
        if (order.amount() != command.amountPaise() || !command.currency().equals(order.currency())) {
            throw new PaymentGatewayException("Razorpay order " + order.id() + " has amount " + order.amount() + " "
                    + order.currency() + ", expected " + command.amountPaise() + " " + command.currency());
        }
        return new GatewayOrder(GatewayName.RAZORPAY, order.id(), order.amount(), order.currency(),
                order.receipt() == null ? command.receipt() : order.receipt());
    }

    /** Razorpay errors look like {"error":{"code":"BAD_REQUEST_ERROR","description":"..."}}; keep the code only. */
    private static String errorCode(RestClientResponseException e) {
        try {
            ErrorResponse body = e.getResponseBodyAs(ErrorResponse.class);
            return body != null && body.error() != null ? String.valueOf(body.error().code()) : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    record OrderRequest(long amount, String currency, String receipt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrderResponse(String id, long amount, String currency, String receipt, String status) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorResponse(Error error) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Error(String code, String description) {}
    }
}
