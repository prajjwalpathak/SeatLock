package com.seatlock.assistant.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import testsupport.IntegrationTest;

/** Ingestion and pgvector search on real Postgres (pgvector image) with the hashing stand-in embedder. */
@IntegrationTest
class PolicyIngestionIntegrationTest {

    @Autowired PolicyIngestionService ingestion;
    @Autowired PolicyRetriever retriever;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanSlate() {
        jdbc.update("DELETE FROM policy_documents");   // cascades to chunks
    }

    private long chunks() {
        return jdbc.queryForObject("SELECT count(*) FROM policy_chunks", Long.class);
    }

    @Test
    void firstRunIndexesEverythingAndARestartSkipsUnchangedFiles() {
        PolicyIngestionService.Report first = ingestion.ingestAll();
        assertThat(first).isEqualTo(new PolicyIngestionService.Report(7, 0, 0, 40));
        assertThat(chunks()).isEqualTo(40);
        assertThat(jdbc.queryForObject("SELECT vector_dims(embedding) FROM policy_chunks LIMIT 1", Integer.class))
                .isEqualTo(384);

        assertThat(ingestion.ingestAll()).isEqualTo(new PolicyIngestionService.Report(0, 7, 0, 0));
        assertThat(chunks()).isEqualTo(40);
    }

    @Test
    void changedFileIsReplacedAndRemovedFileIsDeleted() {
        ingestion.ingestAll();
        assertThat(ingestion.ingest("faq", "# FAQ\n## Only one\nShort.")).contains(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM policy_chunks WHERE doc = 'faq'", Long.class)).isEqualTo(1);

        jdbc.update("""
                INSERT INTO policy_documents (doc, content_sha256, embedding_model, chunk_count, indexed_at)
                VALUES ('old-doc', ?, 'hashing-test', 0, now())""", "0".repeat(64));
        PolicyIngestionService.Report report = ingestion.ingestAll();   // faq back to the shipped file, old-doc gone

        assertThat(report.indexed()).isEqualTo(1);
        assertThat(report.removed()).isEqualTo(1);
        assertThat(chunks()).isEqualTo(40);
    }

    @Test
    void twoInstancesIndexingTheSameFileAtOnceWriteItOnce() throws Exception {
        String md = "# Race\n## A\nalpha text\n## B\nbeta text";
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<java.util.Optional<Integer>>> runs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            runs.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ingestion.ingest("race-doc", md);
            }));
        }
        start.countDown();
        long writers = 0;
        for (var run : runs) {
            if (run.get().isPresent()) {
                writers++;
            }
        }
        assertThat(writers).isBetween(1L, 4L);   // later writers may rewrite identical content; never fail
        assertThat(jdbc.queryForObject("SELECT count(*) FROM policy_chunks WHERE doc = 'race-doc'", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT chunk_count FROM policy_documents WHERE doc = 'race-doc'", Integer.class)).isEqualTo(2);
    }

    @Test
    void searchReturnsTheClosestSectionsFirst() {
        ingestion.ingestAll();

        List<PolicyRetriever.Match> top = retriever.search("When cancellation closes");

        assertThat(top).hasSize(4);
        assertThat(top.getFirst().doc()).isEqualTo("refund-policy");
        assertThat(top.getFirst().heading()).isEqualTo("When cancellation closes");
        assertThat(top).extracting(PolicyRetriever.Match::similarity).isSortedAccordingTo((a, b) -> Double.compare(b, a));
        assertThat(retriever.search("aisle seats", 2)).hasSize(2);
        assertThat(retriever.search(" ")).isEmpty();
    }

    @Test
    void chunksOfAnotherModelAreNotSearched() {
        ingestion.ingestAll();
        jdbc.update("UPDATE policy_documents SET embedding_model = 'other-model'");

        assertThat(retriever.search("refund")).isEmpty();
    }
}
