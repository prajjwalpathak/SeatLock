# ADR-017: Policy retrieval (RAG) with local ONNX embeddings and pgvector

- **Status:** Accepted (W7·D4)
- **Context:** The Refund Assistant (W7·D5) must quote the policy rule that applies, not invent one. Policies are
  small (7 markdown files, 40 sections) and change rarely. Gemini's free tier is shared and rate-limited; the VM
  gives each app instance 900 MB.

## Decision
1. **Embeddings:** Spring AI `spring-ai-starter-model-transformers` (ONNX Runtime) with all-MiniLM-L6-v2,
   384 dimensions. Same model for documents and questions. No API key, no quota. ~~The model ships inside the
   Spring AI jar~~ **Corrected W8·D3 (item 8):** the jar only holds a Git LFS pointer; the image now bakes in a
   checksummed copy, so there is still no network at runtime.
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
8. **Model file (W8·D3, FAILURES F15):** `spring-ai-transformers` 2.0.2's `onnx/all-MiniLM-L6-v2/model.onnx` is a
   133-byte Git LFS pointer. The Dockerfile downloads the real file (LFS media URL pinned to tag v2.0.2, 90,387,630
   bytes) and checks SHA-256 `e3dde332c13808c718680e7bf74a574e7e5d06f55bd6e1527e51509dcb8206f3`, the hash the pointer
   itself names, into `/app/models/all-MiniLM-L6-v2/model.onnx` (`SEATLOCK_RAG_ONNX_MODEL_URI=file:…`). IDE runs use
   the same pinned URL as default and cache it. `RagConfig` rejects a local model under 1 MB with an explicit "Git
   LFS pointer" error. URL and hash move together with `spring-ai.version`.

## Consequences
- Image grows by the ONNX Runtime and model (~150–200 MB). The model's native memory sits outside the JVM heap
  (`MaxRAMPercentage=70` of 900 MB), so prod stays `none` until memory with 2 instances is measured (W7·D6).
- Venue documents are deliberately similar ("Seating layout" ×4); retrieval must use the venue name, which the
  eval checks.

## Rejected
- Gemini embeddings (spend shared quota on every question and re-index); `PgVectorStore` (schema not under
  Flyway); one chunk per file (too coarse to quote one rule); fixed-size token windows (sections are already small
  and self-contained).
