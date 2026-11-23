package com.seatlock.assistant.refund;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks a model answer before anyone sees it: every money amount and every percentage in it must be one the engine
 * computed for this booking ({@link RefundFacts#allowedAmounts()}, {@link RefundFacts#allowedPercents()}), and the
 * answer must be short. One wrong number → the answer is discarded and the template is shown instead.
 */
public final class AnswerGuard {

    static final int MAX_CHARS = 900;

    /** ₹1,23,456.50 · Rs. 1234 · INR 500 · 1,200 rupees · 800 rs */
    private static final Pattern AMOUNT_BEFORE = Pattern.compile(
            "(?:₹|\\bRs\\.?|\\bINR)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMOUNT_AFTER = Pattern.compile(
            "\\b([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*(?:rupees|rupee|rs\\b|inr\\b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PERCENT = Pattern.compile(
            "\\b([0-9]{1,3}(?:\\.[0-9]+)?)\\s*(?:%|per\\s?cent)", Pattern.CASE_INSENSITIVE);

    private AnswerGuard() {
    }

    /** Empty = acceptable; otherwise the first problem found (logged, never shown to the user). */
    public static Optional<String> check(String answer, RefundFacts facts) {
        if (answer == null || answer.isBlank()) {
            return Optional.of("empty answer");
        }
        if (answer.length() > MAX_CHARS) {
            return Optional.of("too long: " + answer.length() + " chars");
        }
        for (long paise : amounts(answer)) {
            if (paise < 0 || !facts.allowedAmounts().contains(paise)) {
                return Optional.of("amount not from the engine: " + paise + " paise");
            }
        }
        Matcher p = PERCENT.matcher(answer);
        while (p.find()) {
            BigDecimal value = new BigDecimal(p.group(1));
            if (value.stripTrailingZeros().scale() > 0 || !facts.allowedPercents().contains(value.intValue())) {
                return Optional.of("percentage not from the engine: " + p.group(1) + "%");
            }
        }
        return Optional.empty();
    }

    /** Every money amount in the text, in paise (-1 for one that can't be read). */
    static List<Long> amounts(String text) {
        List<Long> out = new ArrayList<>();
        for (Pattern pattern : List.of(AMOUNT_BEFORE, AMOUNT_AFTER)) {
            Matcher m = pattern.matcher(text);
            while (m.find()) {
                out.add(toPaise(m.group(1)));
            }
        }
        return out;
    }

    private static long toPaise(String number) {
        try {
            return new BigDecimal(number.replace(",", "")).movePointRight(2).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            return -1;
        }
    }
}
