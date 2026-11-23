package com.seatlock.assistant.rag;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.CRC32;

/**
 * Tests only ({@code seatlock.rag.fake-embeddings=true}): words and word pairs hashed into 384 buckets, then
 * L2-normalized. Purely lexical, but deterministic and good enough to exercise ingestion, pgvector SQL and ranking
 * without the ONNX model in CI.
 */
public class HashingPolicyEmbedder implements PolicyEmbedder {

    @Override
    public float[] embed(String text) {
        float[] v = new float[DIMENSIONS];
        String[] words = text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+");
        String previous = null;
        for (String word : words) {
            if (word.isBlank()) {
                continue;
            }
            add(v, word, 1f);
            if (previous != null) {
                add(v, previous + " " + word, 0.5f);
            }
            previous = word;
        }
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        if (norm > 0) {
            float scale = (float) (1 / Math.sqrt(norm));
            for (int i = 0; i < v.length; i++) {
                v[i] *= scale;
            }
        }
        return v;
    }

    private static void add(float[] v, String token, float weight) {
        CRC32 crc = new CRC32();
        crc.update(token.getBytes(StandardCharsets.UTF_8));
        long h = crc.getValue();
        v[(int) (h % DIMENSIONS)] += ((h >> 16) & 1) == 0 ? weight : -weight;
    }

    @Override
    public String model() {
        return "hashing-test";
    }

    @Override
    public boolean enabled() {
        return true;
    }
}
