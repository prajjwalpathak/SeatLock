package testsupport;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

/**
 * Fires N HTTP requests at a real server at the same instant and collects every outcome.
 *
 * <p>Each request gets its own platform thread. All threads wait on a start gate ({@code go}) until
 * every one of them is ready, so the requests genuinely overlap instead of trickling in like a loop.
 * {@link Result#peakInFlight()} proves the overlap. Nothing is swallowed: a transport failure is an
 * outcome with status -1, so a test can't pass because half the requests silently died.
 */
public final class ConcurrencyHarness {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1) // one TCP connection per in-flight request, like real browsers
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public ConcurrencyHarness(int port) {
        this.baseUrl = "http://localhost:" + port;
    }

    /** Builder for a request against this server; {@code path} starts with "/api/...". */
    public HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(TIMEOUT);
    }

    /** One HTTP outcome. {@code status} is -1 when the request failed before a response arrived. */
    public record Outcome(int index, int status, String body, long nanos) {

        /** The {@code code} field of a ProblemDetail body, or null. */
        public String code() {
            if (body == null || !body.contains("\"code\"")) {
                return null;
            }
            return JsonPath.read(body, "$.code");
        }

        public <T> T json(String path) {
            return JsonPath.read(body, path);
        }
    }

    /** All outcomes (in request order) plus how many requests were actually in flight at once. */
    public record Result(List<Outcome> outcomes, int peakInFlight, long wallNanos) {

        public List<Outcome> withStatus(int status) {
            return outcomes.stream().filter(o -> o.status() == status).toList();
        }

        public Map<Integer, Long> statusCounts() {
            return outcomes.stream().collect(Collectors.groupingBy(Outcome::status, TreeMap::new, Collectors.counting()));
        }

        public Map<String, Long> codeCounts() {
            return outcomes.stream().filter(o -> o.code() != null)
                    .collect(Collectors.groupingBy(Outcome::code, TreeMap::new, Collectors.counting()));
        }

        /** Nearest-rank percentile of response time, in milliseconds. */
        public double percentileMillis(double p) {
            long[] sorted = outcomes.stream().mapToLong(Outcome::nanos).sorted().toArray();
            int rank = (int) Math.ceil(p / 100.0 * sorted.length);
            return sorted[Math.max(0, rank - 1)] / 1_000_000.0;
        }

        /** Distinct transport errors (status -1) with counts; empty when every request got a response. */
        public Map<String, Long> transportErrors() {
            return outcomes.stream().filter(o -> o.status() == -1)
                    .collect(Collectors.groupingBy(Outcome::body, TreeMap::new, Collectors.counting()));
        }

        /** One line for the test log and for CORRECTNESS.md. */
        public String summary() {
            return "n=%d peakInFlight=%d statuses=%s codes=%s p50=%.1fms p95=%.1fms p99=%.1fms max=%.1fms wall=%.0fms%s"
                    .formatted(outcomes.size(), peakInFlight, statusCounts(), codeCounts(),
                            percentileMillis(50), percentileMillis(95), percentileMillis(99), percentileMillis(100),
                            wallNanos / 1_000_000.0,
                            transportErrors().isEmpty() ? "" : " transportErrors=" + transportErrors());
        }
    }

    /**
     * Builds request {@code i} with {@code requestFor.apply(i)} (before the gate opens, so building
     * costs nothing during the race), then sends all {@code n} at once.
     */
    public Result race(int n, IntFunction<HttpRequest> requestFor) throws InterruptedException {
        HttpRequest[] requests = new HttpRequest[n];
        Arrays.setAll(requests, requestFor::apply);

        Outcome[] outcomes = new Outcome[n];
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            for (int i = 0; i < n; i++) {
                int index = i;
                pool.execute(() -> {
                    try {
                        ready.countDown();
                        go.await();
                        peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                        long start = System.nanoTime();
                        try {
                            HttpResponse<String> response = http.send(requests[index], HttpResponse.BodyHandlers.ofString());
                            outcomes[index] = new Outcome(index, response.statusCode(), response.body(), System.nanoTime() - start);
                        } catch (Exception e) {
                            outcomes[index] = new Outcome(index, -1, e.toString(), System.nanoTime() - start);
                        } finally {
                            inFlight.decrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        outcomes[index] = new Outcome(index, -1, "interrupted before start", 0);
                    } finally {
                        done.countDown();
                    }
                });
            }
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Only " + (n - ready.getCount()) + " of " + n + " threads started");
            }
            long wallStart = System.nanoTime();
            go.countDown();
            if (!done.await(TIMEOUT.toSeconds() + 10, TimeUnit.SECONDS)) {
                throw new IllegalStateException(done.getCount() + " of " + n + " requests did not finish");
            }
            long wall = System.nanoTime() - wallStart;
            return new Result(Collections.unmodifiableList(new ArrayList<>(Arrays.asList(outcomes))), peak.get(), wall);
        } finally {
            pool.shutdownNow();
        }
    }
}
