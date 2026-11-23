package com.seatlock.assistant.rag;

import org.springframework.ai.embedding.EmbeddingModel;

/** Spring AI's local ONNX model (all-MiniLM-L6-v2): runs in-process, no network, no quota. */
public class OnnxPolicyEmbedder implements PolicyEmbedder {

    static final String MODEL = "all-MiniLM-L6-v2";

    private final EmbeddingModel model;

    public OnnxPolicyEmbedder(EmbeddingModel model) {
        this.model = model;
    }

    @Override
    public float[] embed(String text) {
        return model.embed(text);
    }

    @Override
    public String model() {
        return MODEL;
    }

    @Override
    public boolean enabled() {
        return true;
    }
}
