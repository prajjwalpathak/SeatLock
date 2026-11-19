package testsupport;

import com.seatlock.realtime.SeatEventMessage;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * A real STOMP client over a real WebSocket (Tomcat's client), the way a browser talks to {@code /ws}.
 * Payloads are taken as raw bytes and parsed here, so the test checks the actual JSON on the wire.
 * ERROR frames (sent by the server before it closes the connection) are collected too.
 */
public final class StompTestClient implements AutoCloseable {

    public static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final WebSocketStompClient client;
    private final String url;
    private final BlockingQueue<StompHeaders> errors = new LinkedBlockingQueue<>();
    private StompSession session;

    public StompTestClient(int port) {
        this.client = new WebSocketStompClient(new StandardWebSocketClient());
        this.client.setDefaultHeartbeat(new long[] {0, 0}); // no client scheduler; heart-beats aren't under test
        this.url = "ws://localhost:" + port + "/ws";
    }

    /** {@code Authorization} header value for a token. */
    public static String bearer(String token) {
        return "Bearer " + token;
    }

    /** CONNECT, optionally with an {@code Authorization} header (raw value) and a browser-like Origin. */
    public CompletableFuture<StompSession> connect(String authorization, String origin) {
        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        if (origin != null) {
            handshake.setOrigin(origin);
        }
        StompHeaders connect = new StompHeaders();
        if (authorization != null) {
            connect.add("Authorization", authorization);
        }
        return client.connectAsync(url, handshake, connect, new StompSessionHandlerAdapter() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                errors.add(headers); // frames without a subscription = ERROR frames
            }

            @Override
            public void handleException(StompSession s, StompCommand command, StompHeaders headers,
                                        byte[] payload, Throwable exception) {
                throw new IllegalStateException("STOMP client failure", exception);
            }
        }).thenApply(s -> session = s);
    }

    /** Connects anonymously and waits for CONNECTED. */
    public StompTestClient connected() throws Exception {
        connect(null, null).get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        return this;
    }

    /** Subscribes and returns the queue every seat event on that destination lands in. */
    public BlockingQueue<SeatEventMessage> subscribe(String destination) {
        BlockingQueue<SeatEventMessage> received = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add(JSON.readValue((byte[]) payload, SeatEventMessage.class));
            }
        });
        return received;
    }

    public StompSession session() {
        return session;
    }

    /** The next ERROR frame's {@code message} header (our error code), or null if none arrives in time. */
    public String nextErrorCode() throws InterruptedException {
        StompHeaders error = errors.poll(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        return error == null ? null : error.getFirst("message");
    }

    /**
     * Best-effort DISCONNECT. After an ERROR frame the server closes the socket, and {@code isConnected()}
     * may still say true for a moment, so the DISCONNECT can hit a closed connection: that's expected, not a failure.
     */
    @Override
    public void close() {
        if (session != null && session.isConnected()) {
            try {
                session.disconnect();
            } catch (RuntimeException alreadyClosedByServer) {
                // IllegalStateException "Connection closed" / MessageDeliveryException: nothing left to close
            }
        }
    }
}
