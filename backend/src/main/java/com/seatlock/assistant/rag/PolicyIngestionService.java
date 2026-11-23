package com.seatlock.assistant.rag;

import com.seatlock.assistant.ai.AiGateway;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Indexes {@code classpath:policies/*.md} into {@code policy_chunks}. Per file:
 * <ol>
 *   <li>SHA-256 of the content; unchanged content + same embedding model → skipped (normal restarts cost nothing);</li>
 *   <li>chunk + embed <b>outside</b> any transaction (the model is CPU work, no connection held);</li>
 *   <li>one short transaction under a per-document advisory lock: re-check the hash (the other instance may have
 *       just done it), replace the chunks, upsert the document row.</li>
 * </ol>
 * Files that no longer exist are removed. Two instances starting together never write the same document at once.
 */
@Service
public class PolicyIngestionService {

    static final String LOCATION = "classpath:policies/*.md";

    private static final Logger log = LoggerFactory.getLogger(PolicyIngestionService.class);

    private final PolicyEmbedder embedder;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PolicyIngestionService(PolicyEmbedder embedder, JdbcClient jdbc, TransactionTemplate tx, Clock clock) {
        this.embedder = embedder;
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    public record Report(int indexed, int unchanged, int removed, int chunks) {}

    /** Synchronized: the startup run and an admin re-index on one instance never interleave. */
    public synchronized Report ingestAll() {
        if (!embedder.enabled()) {
            log.info("Policy ingestion skipped: embeddings disabled (spring.ai.model.embedding=none)");
            return new Report(0, 0, 0, 0);
        }
        Map<String, String> files = readPolicies();
        int indexed = 0;
        int unchanged = 0;
        int chunks = 0;
        for (Map.Entry<String, String> file : files.entrySet()) {
            Optional<Integer> written = ingest(file.getKey(), file.getValue());
            if (written.isPresent()) {
                indexed++;
                chunks += written.get();
            } else {
                unchanged++;
            }
        }
        int removed = removeMissing(files.keySet());
        log.info("Policy ingestion ({}): {} indexed ({} chunks), {} unchanged, {} removed",
                embedder.model(), indexed, chunks, unchanged, removed);
        return new Report(indexed, unchanged, removed, chunks);
    }

    /** @return number of chunks written, or empty if the stored version was already current */
    Optional<Integer> ingest(String doc, String markdown) {
        String hash = AiGateway.sha256(markdown);
        if (isCurrent(doc, hash)) {
            return Optional.empty();
        }
        List<PolicyChunk> chunks = MarkdownChunker.chunk(doc, markdown);
        List<String> vectors = new ArrayList<>(chunks.size());
        for (PolicyChunk chunk : chunks) {
            vectors.add(PolicyVectors.literal(embedder.embed(chunk.text())));   // outside the transaction
        }
        return tx.execute(status -> {
            // void-returning lock function wrapped in count(*) so the driver reads a plain number
            jdbc.sql("SELECT count(*) FROM (SELECT pg_advisory_xact_lock(hashtext(:key))) AS locked")
                    .param("key", "policy:" + doc).query(Long.class).single();
            if (isCurrent(doc, hash)) {
                return Optional.<Integer>empty();   // the other instance finished it while we were embedding
            }
            jdbc.sql("""
                    INSERT INTO policy_documents (doc, content_sha256, embedding_model, chunk_count, indexed_at)
                    VALUES (:doc, :hash, :model, :count, :at)
                    ON CONFLICT (doc) DO UPDATE SET content_sha256 = EXCLUDED.content_sha256,
                        embedding_model = EXCLUDED.embedding_model, chunk_count = EXCLUDED.chunk_count,
                        indexed_at = EXCLUDED.indexed_at
                    """)
                    .param("doc", doc).param("hash", hash).param("model", embedder.model())
                    .param("count", chunks.size()).param("at", Timestamp.from(clock.instant()))
                    .update();
            jdbc.sql("DELETE FROM policy_chunks WHERE doc = :doc").param("doc", doc).update();
            for (int i = 0; i < chunks.size(); i++) {
                PolicyChunk c = chunks.get(i);
                jdbc.sql("""
                        INSERT INTO policy_chunks (doc, chunk_index, heading, content, embedding)
                        VALUES (:doc, :idx, :heading, :content, CAST(:embedding AS vector))
                        """)
                        .param("doc", doc).param("idx", c.index()).param("heading", c.heading())
                        .param("content", c.text()).param("embedding", vectors.get(i))
                        .update();
            }
            return Optional.of(chunks.size());
        });
    }

    private boolean isCurrent(String doc, String hash) {
        return jdbc.sql("SELECT count(*) FROM policy_documents WHERE doc = :doc AND content_sha256 = :hash AND embedding_model = :model")
                .param("doc", doc).param("hash", hash).param("model", embedder.model())
                .query(Long.class).single() > 0;
    }

    private int removeMissing(java.util.Set<String> present) {
        List<String> stored = jdbc.sql("SELECT doc FROM policy_documents").query(String.class).list();
        int removed = 0;
        for (String doc : stored) {
            if (!present.contains(doc)) {
                removed += jdbc.sql("DELETE FROM policy_documents WHERE doc = :doc").param("doc", doc).update();
            }
        }
        return removed;
    }

    /** doc name (file name without .md) → content, sorted by name for a stable order. */
    static Map<String, String> readPolicies() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(LOCATION);
            Map<String, String> files = new LinkedHashMap<>();
            java.util.Arrays.stream(resources)
                    .sorted(java.util.Comparator.comparing(r -> String.valueOf(r.getFilename())))
                    .forEach(r -> files.put(docName(r), read(r)));
            return files;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String docName(Resource r) {
        String name = String.valueOf(r.getFilename());
        return name.endsWith(".md") ? name.substring(0, name.length() - 3) : name;
    }

    private static String read(Resource r) {
        try (var in = r.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
