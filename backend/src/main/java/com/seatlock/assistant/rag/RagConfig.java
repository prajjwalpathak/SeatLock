package com.seatlock.assistant.rag;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

/**
 * Chooses the embedder from {@code seatlock.rag.embeddings} ({@code SEATLOCK_EMBEDDINGS}):
 * <ul>
 *   <li>{@code transformers}: the local ONNX model, built <b>here</b> rather than by Spring AI's auto-configuration,
 *       so a model that fails to load (missing native library, unwritable cache folder, corrupt file) turns
 *       retrieval off with an ERROR log instead of failing startup: one AI extra must never take booking down
 *       (W7·D6, ADR-017 update);</li>
 *   <li>{@code none}: off; with {@code seatlock.rag.fake-embeddings} (tests) the hashing stand-in instead.</li>
 * </ul>
 * After startup the policies are indexed on a single background thread, so readiness never waits for indexing.
 */
@Configuration(proxyBeanMethods = false)
public class RagConfig {

    private static final Logger log = LoggerFactory.getLogger(RagConfig.class);

    @Bean
    PolicyEmbedder policyEmbedder(@Value("${seatlock.rag.embeddings:none}") String mode,
                                  @Value("${seatlock.rag.fake-embeddings:false}") boolean fake,
                                  @Value("${seatlock.rag.onnx.model-uri}") String modelUri,
                                  @Value("${seatlock.rag.onnx.tokenizer-uri}") String tokenizerUri,
                                  @Value("${seatlock.rag.onnx.cache-directory}") String cacheDirectory) {
        return choose(mode, fake, () -> {
            rejectLfsPointer(new DefaultResourceLoader().getResource(modelUri), modelUri);
            TransformersEmbeddingModel model = new TransformersEmbeddingModel();
            model.setModelResource(modelUri);
            model.setTokenizerResource(tokenizerUri);
            model.setResourceCacheDirectory(cacheDirectory);
            model.afterPropertiesSet();
            model.embed("warm-up");   // loads the native runtime now, not on the first user question
            return model;
        });
    }

    /**
     * W8·D3 (FAILURES F15): spring-ai-transformers 2.0.2 ships {@code onnx/all-MiniLM-L6-v2/model.onnx} as a 133-byte
     * Git LFS pointer, not the 90 MB model, so loading it fails deep inside ONNX with "protobuf parsing failed". Local
     * files and classpath entries are checked up front so the log says what is actually wrong. Remote URLs are not
     * fetched here (Spring AI downloads and caches them itself).
     */
    static void rejectLfsPointer(Resource model, String uri) throws IOException {
        if (uri.startsWith("http:") || uri.startsWith("https:") || !model.exists()) {
            return;
        }
        long size = model.contentLength();
        if (size >= MIN_MODEL_BYTES) {
            return;
        }
        byte[] head;
        try (InputStream in = model.getInputStream()) {
            head = in.readNBytes(64);
        }
        if (new String(head, StandardCharsets.US_ASCII).startsWith("version https://git-lfs")) {
            throw new IllegalStateException("model " + uri + " is a Git LFS pointer (" + size + " bytes), not the "
                    + "model: use the image's /app/models copy or the pinned download URL (FAILURES F15)");
        }
        throw new IllegalStateException("model " + uri + " is only " + size + " bytes; a real ONNX model is MBs");
    }

    /** A real all-MiniLM-L6-v2 is ~90 MB; anything under 1 MB can't be a model. */
    static final long MIN_MODEL_BYTES = 1_000_000;

    /** Package-private for tests: the loader is only called for "transformers". */
    static PolicyEmbedder choose(String mode, boolean fake, Callable<EmbeddingModel> loader) {
        if ("transformers".equals(mode.strip().toLowerCase(Locale.ROOT))) {
            try {
                OnnxPolicyEmbedder embedder = new OnnxPolicyEmbedder(loader.call());
                log.info("RAG embeddings: {}", embedder.model());
                return embedder;
            } catch (Exception | LinkageError e) {   // UnsatisfiedLinkError etc. from native libraries included
                log.error("RAG DISABLED: the embedding model failed to load ({}). The Refund Assistant answers from "
                        + "its template; everything else is unaffected.", e.toString());
                return PolicyEmbedder.DISABLED;
            }
        }
        if (fake) {
            log.info("RAG embeddings: hashing stand-in (tests)");
            return new HashingPolicyEmbedder();
        }
        log.info("RAG DISABLED (seatlock.rag.embeddings={}): the Refund Assistant answers from its template", mode);
        return PolicyEmbedder.DISABLED;
    }

    /** Separate component so the listener exists only with ingest-on-startup (tests call the service directly). */
    @Bean
    @ConditionalOnProperty(name = "seatlock.rag.ingest-on-startup", havingValue = "true", matchIfMissing = true)
    StartupIngestion startupIngestion(PolicyIngestionService ingestion) {
        return new StartupIngestion(ingestion);
    }

    /**
     * Owns its single "rag-ingest" thread. Deliberately NOT an Executor bean: an Executor bean makes Boot skip its
     * own applicationTaskExecutor (same trap as decision #157).
     */
    static class StartupIngestion implements org.springframework.beans.factory.DisposableBean {
        private final PolicyIngestionService ingestion;
        private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "rag-ingest");
            t.setDaemon(true);
            return t;
        });

        StartupIngestion(PolicyIngestionService ingestion) {
            this.ingestion = ingestion;
        }

        @Override
        public void destroy() {
            executor.shutdownNow();
        }

        @EventListener(ApplicationReadyEvent.class)
        void onReady() {
            executor.submit(() -> {
                try {
                    ingestion.ingestAll();
                } catch (RuntimeException e) {
                    log.warn("Policy ingestion failed; retrieval uses whatever was indexed before ({})", e.toString());
                }
            });
        }
    }
}
