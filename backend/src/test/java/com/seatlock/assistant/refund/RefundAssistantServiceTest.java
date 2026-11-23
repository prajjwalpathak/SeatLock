package com.seatlock.assistant.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.seatlock.assistant.ai.AiFeature;
import com.seatlock.assistant.ai.AiGateway;
import com.seatlock.assistant.ai.AiOutcome;
import com.seatlock.assistant.ai.AiResult;
import com.seatlock.assistant.rag.PolicyRetriever;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Orchestration with mocks: facts first, excerpts in the prompt, guard wired into the gateway, template on fallback. */
class RefundAssistantServiceTest {

    private static final String BOOKING = "00000000-0000-0000-0000-000000000001";
    private static final RefundFacts FACTS = new RefundFacts(RefundSituation.PAID_CANCELLABLE, 100_000, 100_000L, 100,
            100_000L, Instant.parse("2026-12-24T14:00:00Z"), 50, 50_000L, Instant.parse("2026-12-26T14:00:00Z"), null,
            "100% back", Set.of(100, 50));

    private final RefundFactsService factsService = mock(RefundFactsService.class);
    private final PolicyRetriever retriever = mock(PolicyRetriever.class);
    private final AiGateway gateway = mock(AiGateway.class);
    private final RefundAssistantService service = new RefundAssistantService(factsService, retriever, gateway);

    @BeforeEach
    void setUp() {
        when(factsService.factsFor(7L, BOOKING)).thenReturn(FACTS);
        when(retriever.search(anyString(), anyInt())).thenReturn(List.of(new PolicyRetriever.Match("refund-policy",
                "How much you get back", "Cancellation and refund policy: How much you get back. At least 48 hours...", 0.8)));
    }

    @Test
    void modelAnswerThatPassesTheGuardIsReturnedWithItsSources() {
        when(gateway.complete(eq(AiFeature.REFUND_ASSISTANT), eq(7L), anyString(), eq("Can I cancel?"), any(AiGateway.Options.class)))
                .thenReturn(new AiResult.Answer("Yes, cancel now for 100% back: ₹1,000.", "m", 900));

        RefundAssistantService.Answer a = service.ask(7L, BOOKING, "Can I cancel?");

        assertThat(a.source()).isEqualTo("AI");
        assertThat(a.answer()).contains("₹1,000");
        assertThat(a.fallbackReason()).isNull();
        assertThat(a.sources()).extracting(RefundAssistantService.Source::heading).containsExactly("How much you get back");
    }

    @Test
    void theGuardIsWhatTheGatewayValidatesWith() {
        when(gateway.complete(any(), anyLong(), anyString(), anyString(), any(AiGateway.Options.class)))
                .thenReturn(new AiResult.Fallback(AiOutcome.INVALID_OUTPUT));
        service.ask(7L, BOOKING, "How much back?");

        ArgumentCaptor<AiGateway.Options> options = ArgumentCaptor.forClass(AiGateway.Options.class);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(gateway).complete(eq(AiFeature.REFUND_ASSISTANT), eq(7L), prompt.capture(), eq("How much back?"), options.capture());
        assertThat(options.getValue().accept().test("You get ₹1,000 (100%).")).isTrue();
        assertThat(options.getValue().accept().test("You get ₹1,200 back.")).isFalse();
        assertThat(options.getValue().chargeUserQuota()).isTrue();
        assertThat(prompt.getValue()).contains("Cancelling NOW refunds 100%: ₹1,000.")
                .contains("[1] Cancellation and refund policy: How much you get back.");
    }

    @Test
    void fallbackGivesTheTemplateWithTheReason() {
        when(gateway.complete(any(), anyLong(), anyString(), anyString(), any(AiGateway.Options.class)))
                .thenReturn(new AiResult.Fallback(AiOutcome.QUOTA));

        RefundAssistantService.Answer a = service.ask(7L, BOOKING, "Can I cancel?");

        assertThat(a.source()).isEqualTo("TEMPLATE");
        assertThat(a.fallbackReason()).isEqualTo(AiOutcome.QUOTA);
        assertThat(a.answer()).isEqualTo(RefundTemplate.answer(FACTS));
        assertThat(a.sources()).hasSize(1);
    }

    @Test
    void retrievalFailureStillAnswers() {
        when(retriever.search(anyString(), anyInt())).thenThrow(new IllegalStateException("pgvector down"));
        when(gateway.complete(any(), anyLong(), anyString(), anyString(), any(AiGateway.Options.class)))
                .thenReturn(new AiResult.Fallback(AiOutcome.DISABLED));

        RefundAssistantService.Answer a = service.ask(7L, BOOKING, "Can I cancel?");

        assertThat(a.sources()).isEmpty();
        assertThat(a.situation()).isEqualTo(RefundSituation.PAID_CANCELLABLE);
        assertThat(RefundAssistantService.systemPrompt(FACTS, List.of())).contains("(none)");
    }
}
