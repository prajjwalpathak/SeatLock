package com.seatlock.assistant.rag;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Nearest policy sections to a question: embed the question with the same model, then order by cosine distance
 * ({@code <=>}, HNSW index). Only chunks embedded by the current model are searched. Embeddings off → empty list
 * (the Refund Assistant then answers from its template). Not transactional: one query.
 */
@Service
public class PolicyRetriever {

    private final PolicyEmbedder embedder;
    private final JdbcClient jdbc;
    private final int defaultTopK;

    public PolicyRetriever(PolicyEmbedder embedder, JdbcClient jdbc, @Value("${seatlock.rag.top-k:4}") int defaultTopK) {
        this.embedder = embedder;
        this.jdbc = jdbc;
        this.defaultTopK = defaultTopK;
    }

    /** {@code similarity} = 1 − cosine distance (1 = same direction). */
    public record Match(String doc, String heading, String text, double similarity) {}

    public boolean enabled() {
        return embedder.enabled();
    }

    public List<Match> search(String question) {
        return search(question, defaultTopK);
    }

    public List<Match> search(String question, int k) {
        if (!embedder.enabled() || question == null || question.isBlank()) {
            return List.of();
        }
        String vector = PolicyVectors.literal(embedder.embed(question));
        return jdbc.sql("""
                SELECT c.doc, c.heading, c.content, 1 - (c.embedding <=> CAST(:q AS vector)) AS similarity
                FROM policy_chunks c JOIN policy_documents d ON d.doc = c.doc
                WHERE d.embedding_model = :model
                ORDER BY c.embedding <=> CAST(:q AS vector)
                LIMIT :k
                """)
                .param("q", vector).param("model", embedder.model()).param("k", k)
                .query((rs, n) -> new Match(rs.getString("doc"), rs.getString("heading"), rs.getString("content"),
                        rs.getDouble("similarity")))
                .list();
    }
}
