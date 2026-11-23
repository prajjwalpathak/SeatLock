package com.seatlock.assistant.api;

import com.seatlock.assistant.ai.AiFeature;
import com.seatlock.assistant.ai.AiGateway;
import com.seatlock.assistant.ai.AiResult;
import com.seatlock.assistant.rag.PolicyEmbedder;
import com.seatlock.assistant.rag.PolicyIngestionService;
import com.seatlock.assistant.rag.PolicyRetriever;
import com.seatlock.assistant.seatfinder.SeatIntentParser;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.util.Set;
import com.seatlock.common.config.OpenApiConfig;
import com.seatlock.common.ratelimit.RateLimited;
import com.seatlock.identity.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin checks for the AI guardrails: is a model configured, what state is the breaker in, does a call work. */
@Tag(name = "Admin", description = "Admin-only endpoints")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@PreAuthorize("hasRole('ADMIN')")
@RestController
@RequestMapping("/api/v1/admin/ai")
public class AdminAiController {

    private static final String PING_SYSTEM_PROMPT =
            "You are a connectivity check for the SeatLock ticketing app. Answer in one short sentence.";

    /** Seeded venues all have these; the eval set is written against them. */
    static final Set<String> EVAL_SECTIONS = Set.of("PLATINUM", "GOLD", "SILVER");

    private final AiGateway gateway;
    private final SeatIntentParser parser;
    private final PolicyEmbedder embedder;
    private final PolicyRetriever retriever;
    private final PolicyIngestionService ingestion;
    private final JdbcClient jdbc;

    public AdminAiController(AiGateway gateway, SeatIntentParser parser, PolicyEmbedder embedder,
                             PolicyRetriever retriever, PolicyIngestionService ingestion, JdbcClient jdbc) {
        this.gateway = gateway;
        this.parser = parser;
        this.embedder = embedder;
        this.retriever = retriever;
        this.ingestion = ingestion;
        this.jdbc = jdbc;
    }

    @Operation(summary = "AI configuration and circuit-breaker state (ADMIN)")
    @GetMapping("/status")
    public AiStatusResponse status() {
        long chunks = jdbc.sql("""
                        SELECT count(*) FROM policy_chunks c JOIN policy_documents d ON d.doc = c.doc
                        WHERE d.embedding_model = :model""")
                .param("model", embedder.model()).query(Long.class).single();
        return new AiStatusResponse(gateway.enabled(), gateway.model(), gateway.circuitState().name(),
                embedder.model(), chunks);
    }

    @Operation(summary = "Send one message through the AI gateway (ADMIN); returns the answer or the fallback reason")
    @RateLimited("ai")
    @PostMapping("/ping")
    public AiPingResponse ping(@CurrentUserId long userId, @Valid @RequestBody AiPingRequest request) {
        AiResult result = gateway.complete(AiFeature.PING, userId, PING_SYSTEM_PROMPT, request.message());
        return switch (result) {
            case AiResult.Answer a -> new AiPingResponse(a.outcome(), a.text(), a.model(), a.latencyMs());
            case AiResult.Fallback f -> new AiPingResponse(f.outcome(), null, null, null);
        };
    }

    /**
     * One phrase of the seat-intent eval (load-tests/ai-intent-eval.ps1): no cache, no per-user daily quota
     * (40 phrases > 30/day), global caps still apply, so the script paces itself. Not rate-limited per minute.
     */
    @Operation(summary = "Parse one seat request without cache or user quota (ADMIN, for the intent eval)")
    @PostMapping("/seat-intent")
    public SeatAskResponse parseSeatIntent(@CurrentUserId long userId, @Valid @RequestBody SeatAskRequest request) {
        return switch (parser.parse(userId, request.text(), EVAL_SECTIONS, true)) {
            case SeatIntentParser.Outcome.Unavailable u -> SeatAskResponse.fallback(u.reason());
            case SeatIntentParser.Outcome.Parsed p -> new SeatAskResponse(true, null, false,
                    SeatAskResponse.filtersOf(p.preferences()), p.assumptions(), null);
        };
    }

    /** Nearest policy sections for a question (local embeddings, no LLM, no quota); used by the recall@4 eval. */
    @Operation(summary = "Top-k policy sections for a question (ADMIN, RAG check / recall eval)")
    @PostMapping("/policy-search")
    public List<PolicyRetriever.Match> policySearch(@Valid @RequestBody PolicySearchRequest request) {
        return retriever.search(request.question(), request.k() == null ? 4 : request.k());
    }

    /** Re-index now (unchanged files are skipped). Normally runs by itself after startup. */
    @Operation(summary = "Index classpath policies now; unchanged files are skipped (ADMIN)")
    @PostMapping("/policy-reindex")
    public PolicyIngestionService.Report reindex() {
        return ingestion.ingestAll();
    }
}
