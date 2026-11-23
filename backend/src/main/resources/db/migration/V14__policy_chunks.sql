-- V14 (W7·D4): policy text for RAG (Refund Assistant), embedded with all-MiniLM-L6-v2 (384 dimensions).
--
-- policy_documents: one row per markdown file under classpath:policies/, with the SHA-256 of its content and the
--   embedding model used. Ingestion re-embeds a file only when either changed (startup on 2 instances is cheap).
-- policy_chunks: one row per "## " section; cosine distance (<=>) on an HNSW index for nearest-neighbour search.
-- pgvector ships in the pgvector/pgvector:pg16 image used locally, in tests and in prod.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE policy_documents (
    doc              varchar(64)  PRIMARY KEY,
    content_sha256   char(64)     NOT NULL,
    embedding_model  varchar(64)  NOT NULL,
    chunk_count      integer      NOT NULL CONSTRAINT ck_policy_documents_chunks CHECK (chunk_count >= 0),
    indexed_at       timestamptz  NOT NULL
);

CREATE TABLE policy_chunks (
    id           bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    doc          varchar(64)   NOT NULL CONSTRAINT fk_policy_chunks_doc REFERENCES policy_documents (doc) ON DELETE CASCADE,
    chunk_index  integer       NOT NULL,
    heading      varchar(200)  NOT NULL,
    content      text          NOT NULL,
    embedding    vector(384)   NOT NULL,
    CONSTRAINT uq_policy_chunks_doc_index UNIQUE (doc, chunk_index)
);

-- Approximate nearest neighbours by cosine distance. At 40 rows the planner prefers a seq scan (cheaper);
-- the index is what keeps search fast if policies grow (docs/CORRECTNESS.md section 2.4).
CREATE INDEX ix_policy_chunks_embedding ON policy_chunks USING hnsw (embedding vector_cosine_ops);
