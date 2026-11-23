package com.seatlock.assistant.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

/** Embedder selection (incl. a model that fails to load), the ONNX adapter and the background startup run. */
class RagConfigTest {

    @Test
    void transformersLoadsTheModelThenFakeThenDisabled() {
        EmbeddingModel model = mock(EmbeddingModel.class);

        assertThat(RagConfig.choose("transformers", false, () -> model)).isInstanceOf(OnnxPolicyEmbedder.class);
        assertThat(RagConfig.choose(" Transformers ", true, () -> model)).isInstanceOf(OnnxPolicyEmbedder.class);
        assertThat(RagConfig.choose("none", true, () -> model)).isInstanceOf(HashingPolicyEmbedder.class);
        assertThat(RagConfig.choose("none", false, () -> model)).isSameAs(PolicyEmbedder.DISABLED);
    }

    @Test
    void aModelThatFailsToLoadDisablesRagInsteadOfFailingStartup() {
        assertThat(RagConfig.choose("transformers", false, () -> {
            throw new IllegalStateException("model.onnx not found");
        })).isSameAs(PolicyEmbedder.DISABLED);
        assertThat(RagConfig.choose("transformers", false, () -> {
            throw new UnsatisfiedLinkError("no onnxruntime in java.library.path");
        })).isSameAs(PolicyEmbedder.DISABLED);
    }

    @Test
    void onnxAdapterDelegatesAndTagsTheModelWithItsVersion() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        float[] vector = new float[PolicyEmbedder.DIMENSIONS];
        when(model.embed("refund")).thenReturn(vector);

        assertThat(new OnnxPolicyEmbedder(model, "2.0.2").model()).isEqualTo("all-MiniLM-L6-v2@2.0.2");
        OnnxPolicyEmbedder embedder = new OnnxPolicyEmbedder(model);
        assertThat(embedder.embed("refund")).isSameAs(vector);
        assertThat(embedder.model()).startsWith("all-MiniLM-L6-v2@");   // "unknown" for a mock (no jar manifest)
        assertThat(embedder.enabled()).isTrue();
        assertThat(OnnxPolicyEmbedder.versionOf(new Object())).isNotBlank();
    }

    @Test
    void startupIngestionRunsInTheBackgroundAndSurvivesFailures() {
        PolicyIngestionService ingestion = mock(PolicyIngestionService.class);
        when(ingestion.ingestAll()).thenThrow(new IllegalStateException("db down"));
        RagConfig.StartupIngestion startup = new RagConfig().startupIngestion(ingestion);

        startup.onReady();   // returns at once; the failure is logged on the rag-ingest thread

        verify(ingestion, timeout(2_000)).ingestAll();
        startup.destroy();
    }
}
