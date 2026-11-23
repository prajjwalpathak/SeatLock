package com.seatlock.assistant.rag;

/**
 * Text → 384-dimension vector. {@link OnnxPolicyEmbedder} uses the local all-MiniLM-L6-v2 model;
 * {@link HashingPolicyEmbedder} is a deterministic stand-in for tests. {@link #DISABLED} = retrieval off.
 */
public interface PolicyEmbedder {

    int DIMENSIONS = 384;

    float[] embed(String text);

    /** Stored with each indexed document: a different model means re-embedding. */
    String model();

    boolean enabled();

    PolicyEmbedder DISABLED = new PolicyEmbedder() {
        @Override
        public float[] embed(String text) {
            throw new IllegalStateException("Embeddings are disabled");
        }

        @Override
        public String model() {
            return "none";
        }

        @Override
        public boolean enabled() {
            return false;
        }
    };
}
