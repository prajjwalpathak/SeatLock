package com.seatlock.payment.application;

import com.seatlock.booking.application.BookingService;
import com.seatlock.identity.application.UserService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly reset of the shared demo account (W8·D1). Everyone who clicks "Try the demo" signs in as the same user,
 * so its CONFIRMED bookings pile up (seats stay BOOKED, "My bookings" fills with strangers' tickets).
 * <p>Rules: only that one account; only CONFIRMED bookings confirmed at least {@code booking-age} ago; only when the
 * policy gives a <b>full</b> refund right now; each goes through {@link PaidCancellationService#cancel} (payment lock
 * → booking lock, seats back on sale, refund queued in the outbox) exactly like the user pressing Cancel. Bookings
 * being held or paid (HELD, PAYMENT_PENDING) are never selected; the sweeper expires those as usual. Bookings too
 * close to their show (partial or no refund) are left to pass. Running it twice, or on both instances at once, is
 * safe: a cancellation is idempotent and a concurrent duplicate waits on the payment lock.
 */
@Component
public class DemoBookingResetJob {

    private static final Logger log = LoggerFactory.getLogger(DemoBookingResetJob.class);

    private final UserService users;
    private final BookingService bookings;
    private final PaidCancellationService cancellations;
    private final Clock clock;
    private final boolean enabled;
    private final String demoEmail;
    private final Duration bookingAge;
    private final int batch;

    public DemoBookingResetJob(UserService users, BookingService bookings, PaidCancellationService cancellations,
                               Clock clock,
                               @Value("${seatlock.demo.reset-enabled:true}") boolean enabled,
                               @Value("${seatlock.demo.user-email:demo.user@seatlock.test}") String demoEmail,
                               @Value("${seatlock.demo.booking-age:PT12H}") Duration bookingAge,
                               @Value("${seatlock.demo.booking-batch:50}") int batch) {
        this.users = users;
        this.bookings = bookings;
        this.cancellations = cancellations;
        this.clock = clock;
        this.enabled = enabled;
        this.demoEmail = demoEmail;
        this.bookingAge = bookingAge;
        this.batch = batch;
    }

    @Scheduled(cron = "${seatlock.demo.reset-cron:0 30 3 * * *}", zone = "Asia/Kolkata")
    public void nightly() {
        if (!enabled) {
            return;
        }
        try {
            users.findIdByEmail(demoEmail).ifPresentOrElse(this::resetBookingsOf,
                    () -> log.info("Demo booking reset skipped: no account {}", demoEmail));
        } catch (RuntimeException e) {
            log.warn("Demo booking reset failed; will retry tomorrow ({})", e.toString());
        }
    }

    /** @return bookings cancelled with a full refund */
    public int resetBookingsOf(long userId) {
        Instant cutOff = clock.instant().minus(bookingAge);
        List<String> ids = bookings.confirmedBookingIds(userId, cutOff, batch);
        int reset = 0;
        int kept = 0;
        for (String id : ids) {
            try {
                PaidCancellationService.QuoteView quote = cancellations.quote(userId, id);
                if (quote.cancellable() && quote.percent() == 100) {
                    cancellations.cancel(userId, id);
                    reset++;
                } else {
                    kept++; // too close to the show: left to pass, never a partial refund from a job
                }
            } catch (RuntimeException e) {
                log.warn("Demo booking reset: booking {} skipped ({})", id, e.toString());
            }
        }
        if (!ids.isEmpty()) {
            log.info("Demo booking reset: {} cancelled with a full refund, {} kept (partial or no refund)", reset, kept);
        }
        return reset;
    }
}
