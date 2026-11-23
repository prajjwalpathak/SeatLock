package com.seatlock.assistant.rag;

/** pgvector text format "[0.1,0.2,...]", bound as a parameter and cast with {@code CAST(:v AS vector)}. */
final class PolicyVectors {

    private PolicyVectors() {
    }

    static String literal(float[] v) {
        if (v.length != PolicyEmbedder.DIMENSIONS) {
            throw new IllegalStateException("Expected " + PolicyEmbedder.DIMENSIONS + " dimensions, got " + v.length);
        }
        StringBuilder sb = new StringBuilder(v.length * 10).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
