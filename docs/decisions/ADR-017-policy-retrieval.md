# ADR-017: Policy retrieval (RAG) with local ONNX embeddings and pgvector

- **Status:** Accepted (W7·D4)
- **Context:** The Refund Assistant (W7·D5) must quote the policy rule that applies, not invent one. Policies are
  small (7 markdown files, 40 sections) and change rarely. Gemini's free tier is shared and rate-limited; the VM
  gives each app instance 900 MB.

## Decision
1. **Embeddings:** Spring AI `spring-ai-starter-model-transformers` (ONNX Runtime) with all-MiniLM-L6-v2,
   384 dimensions. The model ships inside the Spring AI jar (`classpath:/onnx/...`), so it is in the image with no
   download step and no network at runtime. Same model for documents and questions. No API key, no quota.
2. **Store:** our own Flyway-managed tables (`V14`): `policy_documents` (content SHA-256 + model) and
   `policy_chunks` (`vector(384)`, HNSW `vector_cosine_ops`), queried with plain SQL through `JdbcClient`
   (`<=>` cosine distance). Not Spring AI's `PgVectorStore`, which creates and owns its own schema.
3. **Chunking:** one chunk per `## ` section, prefixed with the document title.
4. **Ingestion:** after startup on a single background thread (readiness never waits for the model); unchanged
   files (hash + model) are skipped; embed outside transactions; write under a per-document advisory lock with a
   re-check, so two instances never write the same document twice; files removed from the classpath are deleted.
5. **Switch:** `spring.ai.model.embedding` from `SEATLOCK_EMBEDDINGS`, default `none` → retrieval off, the assistant
   answers from its template. Tests use a deterministic hashing embedder (`seatlock.rag.fake-embeddings`).
6. **Measured by** a 30-question recall@4 eval (paraphrased questions, one expected section each).

7. **Robustness (W7·D6):** Spring AI's embedding auto-configuration is off (`spring.ai.model.embedding: none`);
   `RagConfig` builds `TransformersEmbeddingModel` itself (`seatlock.rag.embeddings`, `seatlock.rag.onnx.*`) inside
   a try/catch that also catches `LinkageError`: a model that fails to load disables RAG with an ERROR log instead
   of failing startup (and deploy rollbacks). One warm-up embedding at startup loads the native runtime early.
   Documents are tagged `all-MiniLM-L6-v2@<Spring AI version>` (jar manifest), so an upgrade re-indexes.

## Consequences
- Image grows by the ONNX Runtime and model (~150–200 MB). The model's native memory sits outside the JVM heap
  (`MaxRAMPercentage=70` of 900 MB), so prod stays `none` until memory with 2 instances is measured (W7·D6).
- Venue documents are deliberately similar ("Seating layout" ×4); retrieval must use the venue name, which the
  eval checks.

## Rejected
- Gemini embeddings (spend shared quota on every question and re-index); `PgVectorStore` (schema not under
  Flyway); one chunk per file (too coarse to quote one rule); fixed-size token windows (sections are already small
  and self-contained).
