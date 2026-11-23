package com.seatlock.assistant.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Chunking, the hashing stand-in, vector literals and the real policy files: no Spring, no database. */
class RagUnitTest {

    @Test
    void chunksAreSectionsPrefixedWithTheTitle() {
        String md = "# Refunds\r\nIntro line.\r\n\r\n## When\r\nLess than 24 hours\r\nbefore.\r\n## Empty\r\n\r\n## How\r\nFull refund.";

        List<PolicyChunk> chunks = MarkdownChunker.chunk("refund-policy", md);

        assertThat(chunks).extracting(PolicyChunk::heading).containsExactly("Refunds", "When", "How");   // empty section skipped
        assertThat(chunks).extracting(PolicyChunk::index).containsExactly(0, 1, 2);
        assertThat(chunks.get(1).text()).isEqualTo("Refunds: When. Less than 24 hours before.");
        assertThat(chunks).allSatisfy(c -> assertThat(c.doc()).isEqualTo("refund-policy"));
    }

    @Test
    void shippedPoliciesGiveFortySectionsWithUniqueHeadingsPerFile() {
        Map<String, String> files = PolicyIngestionService.readPolicies();

        assertThat(files.keySet()).containsExactly("faq", "refund-policy", "terms", "venue-aurora-arena",
                "venue-banyan-hall", "venue-monsoon-theatre", "venue-riverside-stage");
        int total = 0;
        for (Map.Entry<String, String> f : files.entrySet()) {
            List<PolicyChunk> chunks = MarkdownChunker.chunk(f.getKey(), f.getValue());
            assertThat(chunks.stream().map(PolicyChunk::heading).collect(Collectors.toSet())).hasSize(chunks.size());
            total += chunks.size();
        }
        assertThat(total).isEqualTo(40);
    }

    @Test
    void hashingEmbedderIsDeterministicUnitLengthAndLexical() {
        HashingPolicyEmbedder e = new HashingPolicyEmbedder();
        float[] a = e.embed("When does cancellation close?");
        float[] b = e.embed("When does cancellation close?");
        float[] near = e.embed("cancellation close time");
        float[] far = e.embed("metro station parking");

        assertThat(a).hasSize(PolicyEmbedder.DIMENSIONS).containsExactly(b);
        assertThat(dot(a, a)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
        assertThat(dot(a, near)).isGreaterThan(dot(a, far));
        assertThat(e.embed("  ")).containsOnly(0f);   // nothing to hash, no NaN
    }

    @Test
    void vectorLiteralNeedsTheRightDimensions() {
        assertThat(PolicyVectors.literal(new float[PolicyEmbedder.DIMENSIONS])).startsWith("[0.0,0.0").endsWith("0.0]");
        assertThatThrownBy(() -> PolicyVectors.literal(new float[3])).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void disabledEmbedderRefuses() {
        assertThat(PolicyEmbedder.DISABLED.enabled()).isFalse();
        assertThat(PolicyEmbedder.DISABLED.model()).isEqualTo("none");
        assertThatThrownBy(() -> PolicyEmbedder.DISABLED.embed("x")).isInstanceOf(IllegalStateException.class);
        assertThat(new PolicyRetriever(PolicyEmbedder.DISABLED, null, 4).search("refund")).isEmpty();
    }

    private static double dot(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }
}
