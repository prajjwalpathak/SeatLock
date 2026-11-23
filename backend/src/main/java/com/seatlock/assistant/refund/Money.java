package com.seatlock.assistant.refund;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** The exact strings the assistant may use: amounts in Indian grouping ("₹1,23,456.50") and times in IST. */
final class Money {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("EEE d MMM yyyy, h:mm a 'IST'", Locale.ENGLISH).withZone(IST);

    private Money() {
    }

    /** "₹1,23,456" or "₹1,23,456.50": paise shown only when not zero. */
    static String rupees(long paise) {
        long rupees = paise / 100;
        long rest = Math.abs(paise % 100);
        String digits = Long.toString(Math.abs(rupees));
        StringBuilder grouped = new StringBuilder();
        if (digits.length() <= 3) {
            grouped.append(digits);
        } else {
            String head = digits.substring(0, digits.length() - 3);
            for (int i = 0; i < head.length(); i++) {
                if (i > 0 && (head.length() - i) % 2 == 0) {
                    grouped.append(',');
                }
                grouped.append(head.charAt(i));
            }
            grouped.append(',').append(digits.substring(digits.length() - 3));
        }
        String sign = paise < 0 ? "-" : "";
        return sign + "₹" + grouped + (rest == 0 ? "" : String.format(Locale.ROOT, ".%02d", rest));
    }

    static String time(Instant instant) {
        return TIME.format(instant).replace("AM", "am").replace("PM", "pm");
    }
}
