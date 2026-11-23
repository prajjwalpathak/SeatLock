package com.seatlock.assistant.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

/** Embedder selection, the ONNX adapter and the background startup run, without Spring or the model. */
class RagConfigTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<EmbeddingModel> provider(EmbeddingModel model) {
        ObjectProvider<EmbeddingModel> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(model);
        return p;
    }

    @Test
    void realModelWinsThenFakeThenDisabled() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        RagConfig config = new RagConfig();

        assertThat(config.policyEmbedder(provider(model), true)).isInstanceOf(OnnxPolicyEmbedder.class);
        assertThat(config.policyEmbedder(provider(null), true)).isInstanceOf(HashingPolicyEmbedder.class);
        assertThat(config.policyEmbedder(provider(null), false)).isSameAs(PolicyEmbedder.DISABLED);
    }

    @Test
    void onnxAdapterDelegatesToTheModel() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        float[] vector = new float[PolicyEmbedder.DIMENSIONS];
        when(model.embed("refund")).thenReturn(vector);
        OnnxPolicyEmbedder embedder = new OnnxPolicyEmbedder(model);

        assertThat(embedder.embed("refund")).isSameAs(vector);
        assertThat(embedder.model()).isEqualTo("all-MiniLM-L6-v2");
        assertThat(embedder.enabled()).isTrue();
    }

    @Test
    void startupIngestionRunsInTheBackgroundAndSurvivesFailures() throws Exception {
        PolicyIngestionService ingestion = mock(PolicyIngestionService.class);
        when(ingestion.ingestAll()).thenThrow(new IllegalStateException("db down"));
        RagConfig.StartupIngestion startup = new RagConfig().startupIngestion(ingestion);

        startup.onReady();   // returns at once; the failure is logged on the rag-ingest thread

        verify(ingestion, timeout(2_000)).ingestAll();
        startup.destroy();
    }
}
