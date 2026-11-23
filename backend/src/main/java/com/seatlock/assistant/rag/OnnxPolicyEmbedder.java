package com.seatlock.assistant.rag;

import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Spring AI's local ONNX model (all-MiniLM-L6-v2): runs in-process, no network, no quota.
 * {@link #model()} includes the Spring AI version that shipped the model ("all-MiniLM-L6-v2@2.0.2"): an upgrade
 * that swaps the bundled model re-indexes the policies instead of mixing vectors from two models (W7·D6).
 */
public class OnnxPolicyEmbedder implements PolicyEmbedder {

    static final String MODEL = "all-MiniLM-L6-v2";

    private final EmbeddingModel model;
    private final String modelId;

    public OnnxPolicyEmbedder(EmbeddingModel model) {
        this(model, versionOf(model));
    }

    OnnxPolicyEmbedder(EmbeddingModel model, String version) {
        this.model = model;
        this.modelId = MODEL + "@" + version;
    }

    /** Version from the jar manifest of the model's class; "unknown" when the manifest has none (e.g. in IDE runs). */
    static String versionOf(Object model) {
        String v = model.getClass().getPackage() == null ? null : model.getClass().getPackage().getImplementationVersion();
        return v == null || v.isBlank() ? "unknown" : v;
    }

    @Override
    public float[] embed(String text) {
        return model.embed(text);
    }

    @Override
    public String model() {
        return modelId;
    }

    @Override
    public boolean enabled() {
        return true;
    }
}
