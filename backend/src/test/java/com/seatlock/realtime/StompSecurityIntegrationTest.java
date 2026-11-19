package com.seatlock.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static testsupport.StompTestClient.bearer;

import com.seatlock.identity.domain.UserRepository;
import com.seatlock.identity.security.TokenService;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.messaging.simp.stomp.StompSession;
import testsupport.RealServerTest;
import testsupport.StompTestClient;
import testsupport.TestUsers;

/** Who may connect to /ws, subscribe to what, and what is refused, through a real WebSocket. */
@RealServerTest
class StompSecurityIntegrationTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:5173"; // seatlock.cors.allowed-origins (test)

    @Autowired Environment env;
    @Autowired UserRepository userRepository;
    @Autowired TokenService tokenService;

    private StompTestClient client;
    private StompTestClient listener;

    @BeforeEach
    void setUp() {
        int port = env.getRequiredProperty("local.server.port", Integer.class);
        client = new StompTestClient(port);
        listener = new StompTestClient(port);
    }

    @AfterEach
    void tearDown() {
        client.close();
        listener.close();
    }

    @Test
    void anonymousViewersAndSignedInUsersCanConnectFromTheAllowedOrigin() throws Exception {
        String token = new TestUsers(userRepository, tokenService).tokens("ws-auth", 1).getFirst();

        StompSession signedIn = client.connect(bearer(token), ALLOWED_ORIGIN).get(5, TimeUnit.SECONDS);
        StompSession anonymous = listener.connect(null, ALLOWED_ORIGIN).get(5, TimeUnit.SECONDS);

        assertThat(signedIn.isConnected()).isTrue();
        assertThat(anonymous.isConnected()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer not-a-jwt", "Bearer ", "Basic dXNlcjpwYXNz", "eyJhbGciOiJSUzI1NiJ9.e30.x"})
    void aPresentButInvalidTokenIsRefused(String authorization) throws Exception {
        CompletableFuture<StompSession> connecting = client.connect(authorization, null);

        assertThat(client.nextErrorCode()).isEqualTo("INVALID_TOKEN");
        assertThatThrownBy(() -> connecting.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
    }

    @Test
    void aTamperedTokenIsRefused() throws Exception {
        String token = new TestUsers(userRepository, tokenService).tokens("ws-tamper", 1).getFirst();
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");

        client.connect(bearer(tampered), null);

        assertThat(client.nextErrorCode()).isEqualTo("INVALID_TOKEN");
    }

    @Test
    void otherOriginsCannotOpenTheWebSocket() {
        assertThatThrownBy(() -> client.connect(null, "https://evil.example").get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .satisfies(e -> assertThat(causeMessages(e)).as("handshake answered 403").contains("403"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/topic/shows/abc/seats", "/topic/shows/*/seats", "/topic/shows/**",
            "/topic/admin", "/user/queue/errors", "/app/anything"})
    void onlySeatTopicsCanBeSubscribedTo(String destination) throws Exception {
        client.connected().subscribe(destination);

        assertRejected(client, "SUBSCRIPTION_DENIED");
    }

    @Test
    void subscriptionsPerConnectionAreCapped() throws Exception {
        client.connected();
        for (int i = 1; i <= StompAuthChannelInterceptor.MAX_SUBSCRIPTIONS; i++) {
            client.subscribe(SeatTopics.forShow(i));
        }
        assertThat(client.session().isConnected()).isTrue();

        client.subscribe(SeatTopics.forShow(999));

        assertRejected(client, "TOO_MANY_SUBSCRIPTIONS");
    }

    @Test
    void clientsCannotPublishForgedSeatEvents() throws Exception {
        String topic = SeatTopics.forShow(7);
        BlockingQueue<SeatEventMessage> viewers = listener.connected().subscribe(topic);
        Thread.sleep(300); // let the viewer's SUBSCRIBE register (no receipt on the simple broker)

        client.connected().session().send(topic,
                "{\"showId\":7,\"version\":1,\"status\":\"BOOKED\",\"seatIds\":[1]}".getBytes(StandardCharsets.UTF_8));

        assertRejected(client, "SEND_NOT_ALLOWED");
        assertThat(viewers.poll(500, TimeUnit.MILLISECONDS)).as("nothing reached the broker").isNull();
    }

    private static String causeMessages(Throwable e) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            all.append(t).append(" | ");
        }
        return all.toString();
    }

    /** Closed by the server; the ERROR code, when the frame survives the close race, is the expected one. */
    private static void assertRejected(StompTestClient c, String expectedCode) throws InterruptedException {
        String code = c.awaitRejection();
        assertThat(code).as("ERROR frame code (null = frame lost in the write/close race)").isIn(expectedCode, null);
    }
}
