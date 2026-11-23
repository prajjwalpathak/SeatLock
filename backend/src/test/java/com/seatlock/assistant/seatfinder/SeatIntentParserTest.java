package com.seatlock.assistant.seatfinder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.seatlock.assistant.ai.AiFeature;
import com.seatlock.assistant.ai.AiGateway;
import com.seatlock.assistant.ai.AiOutcome;
import com.seatlock.assistant.ai.AiResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** Validation, code-side decisions (defaults, per-seat budget) and caching, with a mocked gateway and Redis. */
class SeatIntentParserTest {

    private static final Set<String> SECTIONS = Set.of("PLATINUM", "GOLD", "SILVER");
    private static final String GOOD = """
            {"count":2,"budgetRupees":800,"budgetIsTotal":false,"sections":["gold"],"aisle":true,"rows":"front"}""";

    private final AiGateway gateway = mock(AiGateway.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private SeatIntentParser parser;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        parser = new SeatIntentParser(gateway, redis, meters);
    }

    private void modelAnswers(String text) {
        when(gateway.complete(eq(AiFeature.SEAT_FINDER), anyLong(), anyString(), anyString(), any(AiGateway.Options.class)))
                .thenReturn(new AiResult.Answer(text, "m", 500));
    }

    // ---------- validate ----------

    @Test
    void validAnswerIsNormalized() {
        SeatIntent intent = SeatIntentParser.validate("```json\n" + GOOD + "\n```", SECTIONS).orElseThrow();

        assertThat(intent).isEqualTo(new SeatIntent(2, 800, false, List.of("GOLD"), true, "FRONT"));
    }

    @Test
    void missingFieldsGetSafeDefaults() {
        SeatIntent intent = SeatIntentParser.validate("{\"count\":null}", SECTIONS).orElseThrow();

        assertThat(intent).isEqualTo(new SeatIntent(null, null, false, List.of(), false, "ANY"));
    }

    @Test
    void outOfRangeOrUnknownValuesAreRejected() {
        for (String bad : List.of(
                "sure! two seats",                                    // not JSON
                "{\"count\":0}", "{\"count\":21}",                    // count range
                "{\"budgetRupees\":0}", "{\"budgetRupees\":1000001}", // budget range
                "{\"sections\":[\"BALCONY\"]}",                       // not at this venue
                "{\"rows\":\"SIDE\"}",                                // unknown row preference
                "{\"count\":\"two\"}",                                // wrong type
                "{\"count\":2")) {                                    // truncated
            assertThat(SeatIntentParser.validate(bad, SECTIONS)).as(bad).isEmpty();
        }
        assertThat(SeatIntentParser.validate(null, SECTIONS)).isEmpty();
    }

    // ---------- decisions in code ----------

    @Test
    void noCountMeansTwoSeatsAndSaysSo() {
        SeatIntentParser.Outcome.Parsed p = SeatIntentParser.parsed(new SeatIntent(null, null, false, List.of(), false, "ANY"), false);

        assertThat(p.preferences().count()).isEqualTo(2);
        assertThat(p.assumptions()).singleElement().asString().contains("2 seats");
    }

    @Test
    void totalBudgetIsSplitPerSeatInCodeRoundedDown() {
        SeatIntentParser.Outcome.Parsed p = SeatIntentParser.parsed(new SeatIntent(3, 1000, true, List.of(), false, "ANY"), false);

        assertThat(p.preferences().maxPricePaise()).isEqualTo(33_333L);   // ₹1000 / 3 = ₹333.33, floor in paise
        assertThat(p.assumptions()).singleElement().asString().contains("₹333.33 per seat");
    }

    @Test
    void perSeatBudgetIsUsedAsIs() {
        SeatIntentParser.Outcome.Parsed p = SeatIntentParser.parsed(new SeatIntent(2, 800, false, List.of("GOLD"), true, "FRONT"), true);

        assertThat(p.preferences()).isEqualTo(new SeatPreferences(2, 80_000L, Set.of("GOLD"), true, RowPreference.FRONT));
        assertThat(p.assumptions()).isEmpty();
        assertThat(p.cached()).isTrue();
    }

    // ---------- parse: gateway + cache ----------

    @Test
    void cacheMissCallsTheModelAndCachesTheValidatedIntent() {
        modelAnswers(GOOD);

        SeatIntentParser.Outcome outcome = parser.parse(7L, "2 Gold  aisle seats ", SECTIONS, false);

        assertThat(outcome).isInstanceOfSatisfying(SeatIntentParser.Outcome.Parsed.class, p -> {
            assertThat(p.cached()).isFalse();
            assertThat(p.preferences().maxPricePaise()).isEqualTo(80_000L);
        });
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(values).set(key.capture(), stored.capture(), eq(SeatIntentParser.CACHE_TTL));
        assertThat(key.getValue()).startsWith(SeatIntentParser.CACHE_PREFIX)
                .isEqualTo(SeatIntentParser.CACHE_PREFIX + AiGateway.sha256("2 gold aisle seats|GOLD,PLATINUM,SILVER"));
        assertThat(stored.getValue()).contains("\"GOLD\"").contains("\"FRONT\"");
        assertThat(meters.get(SeatIntentParser.METER_CACHE).tag("result", "miss").counter().count()).isEqualTo(1);
    }

    @Test
    void theGatewayValidatesWithTheSameRules() {
        modelAnswers(GOOD);
        parser.parse(7L, "2 seats", SECTIONS, false);

        ArgumentCaptor<AiGateway.Options> options = ArgumentCaptor.forClass(AiGateway.Options.class);
        verify(gateway).complete(eq(AiFeature.SEAT_FINDER), eq(7L), anyString(), eq("2 seats"), options.capture());
        assertThat(options.getValue().chargeUserQuota()).isTrue();
        assertThat(options.getValue().accept().test(GOOD)).isTrue();
        assertThat(options.getValue().accept().test("{\"sections\":[\"BALCONY\"]}")).isFalse();
    }

    @Test
    void cacheHitSkipsTheModel() {
        when(values.get(anyString())).thenReturn(GOOD);

        SeatIntentParser.Outcome outcome = parser.parse(7L, "2 gold aisle seats", SECTIONS, false);

        assertThat(outcome).isInstanceOfSatisfying(SeatIntentParser.Outcome.Parsed.class, p -> assertThat(p.cached()).isTrue());
        verify(gateway, never()).complete(any(), anyLong(), anyString(), anyString(), any(AiGateway.Options.class));
        assertThat(meters.get(SeatIntentParser.METER_CACHE).tag("result", "hit").counter().count()).isEqualTo(1);
    }

    @Test
    void corruptCacheEntryIsIgnored() {
        when(values.get(anyString())).thenReturn("{\"sections\":[\"BALCONY\"]}");
        modelAnswers(GOOD);

        assertThat(parser.parse(7L, "x", SECTIONS, false)).isInstanceOf(SeatIntentParser.Outcome.Parsed.class);
        verify(gateway).complete(any(), anyLong(), anyString(), anyString(), any(AiGateway.Options.class));
    }

    @Test
    void redisDownStillParses() {
        when(values.get(anyString())).thenThrow(new IllegalStateException("redis down"));
        org.mockito.Mockito.doThrow(new IllegalStateException("redis down")).when(values).set(anyString(), anyString(), any(java.time.Duration.class));
        modelAnswers(GOOD);

        assertThat(parser.parse(7L, "x", SECTIONS, false)).isInstanceOf(SeatIntentParser.Outcome.Parsed.class);
    }

    @Test
    void fallbackIsReportedWithItsReason() {
        when(gateway.complete(any(), anyLong(), anyString(), anyString(), any(AiGateway.Options.class)))
                .thenReturn(new AiResult.Fallback(AiOutcome.TIMEOUT));

        assertThat(parser.parse(7L, "x", SECTIONS, false)).isEqualTo(new SeatIntentParser.Outcome.Unavailable(AiOutcome.TIMEOUT));
        verify(values, never()).set(anyString(), anyString(), any(java.time.Duration.class));
    }

    @Test
    void evalRunBypassesCacheAndUserQuota() {
        modelAnswers(GOOD);

        parser.parse(7L, "x", SECTIONS, true);

        verify(values, never()).get(anyString());
        verify(values, never()).set(anyString(), anyString(), any(java.time.Duration.class));
        ArgumentCaptor<AiGateway.Options> options = ArgumentCaptor.forClass(AiGateway.Options.class);
        verify(gateway).complete(any(), anyLong(), anyString(), anyString(), options.capture());
        assertThat(options.getValue().chargeUserQuota()).isFalse();
    }

    @Test
    void promptListsOnlyTheVenueSectionsAndNormalizeCollapsesSpace() {
        assertThat(SeatIntentParser.systemPrompt(new java.util.TreeSet<>(SECTIONS))).contains("[GOLD, PLATINUM, SILVER]");
        assertThat(SeatIntentParser.normalize("  Two   AISLE\tseats ")).isEqualTo("two aisle seats");
    }
}
