package com.seatlock.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatlock.booking.domain.Booking;
import com.seatlock.booking.domain.BookingRepository;
import com.seatlock.catalog.domain.*;
import com.seatlock.common.config.JpaAuditingConfig;
import com.seatlock.identity.domain.AuthProvider;
import com.seatlock.identity.domain.Role;
import com.seatlock.identity.domain.User;
import com.seatlock.identity.domain.UserRepository;
import com.seatlock.payment.gateway.GatewayName;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import testsupport.TestcontainersConfiguration;

/** V8 constraints against real Postgres: one live payment per booking, unique gateway order IDs. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, TestcontainersConfiguration.class})
@ActiveProfiles("test")
class PaymentPersistenceTest {

    static final Instant FIXED = Instant.parse("2026-12-01T10:00:00Z");
    static final long PRICE = 75_000;

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneOffset.UTC);
        }
    }

    @Autowired UserRepository users;
    @Autowired VenueRepository venues;
    @Autowired SeatRepository seats;
    @Autowired EventRepository events;
    @Autowired ShowRepository shows;
    @Autowired ShowSeatRepository showSeats;
    @Autowired BookingRepository bookings;
    @Autowired PaymentRepository payments;
    @Autowired EntityManager em;

    Booking booking;

    @BeforeEach
    void seedBooking() {
        booking = bookings.saveAndFlush(newBooking());
    }

    @Test
    void savesAndReloadsAPayment() {
        Payment saved = payments.saveAndFlush(payment(1, "order_mock_A"));
        em.clear();

        Payment loaded = payments.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getBookingId()).isEqualTo(booking.getId());
        assertThat(loaded.getAttempt()).isEqualTo(1);
        assertThat(loaded.getGateway()).isEqualTo(GatewayName.MOCK);
        assertThat(loaded.getGatewayOrderId()).isEqualTo("order_mock_A");
        assertThat(loaded.getAmountPaise()).isEqualTo(PRICE);
        assertThat(loaded.getCurrency()).isEqualTo("INR");
        assertThat(loaded.getStatus()).isEqualTo(PaymentStatus.CREATED);
        assertThat(loaded.getCreatedAt()).isEqualTo(FIXED);
        assertThat(payments.findByGatewayAndGatewayOrderId(GatewayName.MOCK, "order_mock_A")).isPresent();
        assertThat(payments.findFirstByBookingIdAndStatusIn(booking.getId(), EnumSet.of(PaymentStatus.CREATED)))
                .isPresent();
    }

    @Test
    void databaseRejectsASecondOpenPaymentForTheSameBooking() {
        payments.saveAndFlush(payment(1, "order_mock_A"));

        assertThatThrownBy(() -> payments.saveAndFlush(payment(2, "order_mock_B")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("ux_payments_live_booking");
    }

    @Test
    void aFailedAttemptAllowsANewAttempt() {
        Payment first = payments.saveAndFlush(payment(1, "order_mock_A"));
        first.markFailed("card declined", FIXED);
        payments.saveAndFlush(first);

        payments.saveAndFlush(payment(2, "order_mock_B"));

        assertThat(payments.findTopByBookingIdOrderByAttemptDesc(booking.getId()))
                .get().extracting(Payment::getAttempt).isEqualTo(2);
        assertThat(payments.findByBookingIdOrderByAttemptAsc(booking.getId()))
                .extracting(Payment::getStatus)
                .containsExactly(PaymentStatus.FAILED, PaymentStatus.CREATED);
    }

    @Test
    void databaseRejectsADuplicateGatewayOrderId() {
        Payment first = payments.saveAndFlush(payment(1, "order_mock_A"));
        first.markFailed("timeout", FIXED);
        payments.saveAndFlush(first);

        assertThatThrownBy(() -> payments.saveAndFlush(payment(2, "order_mock_A")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("uq_payments_gateway_order");
    }

    @Test
    void databaseRejectsADuplicateAttemptNumber() {
        Payment first = payments.saveAndFlush(payment(1, "order_mock_A"));
        first.markFailed("timeout", FIXED);
        payments.saveAndFlush(first);

        assertThatThrownBy(() -> payments.saveAndFlush(payment(1, "order_mock_B")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("uq_payments_booking_attempt");
    }

    // ---------- fixtures ----------

    private Payment payment(int attempt, String orderId) {
        return new Payment(booking.getId(), attempt, GatewayName.MOCK, orderId, PRICE, "INR");
    }

    private Booking newBooking() {
        User organizer = users.save(new User("organizer@seatlock.dev", "not-a-real-hash",
                "Organizer", Role.ORGANIZER, AuthProvider.LOCAL));
        User buyer = users.save(new User("buyer@seatlock.dev", "not-a-real-hash",
                "Buyer", Role.USER, AuthProvider.LOCAL));
        Venue venue = venues.save(new Venue("Test Hall", "Delhi", "Test address"));
        Seat seat = seats.save(new Seat(venue, "GOLD", "A", 1, 0, 0, false));
        Event event = events.save(new Event(organizer.getId(), "Test Event", "A test event",
                EventCategory.CONCERT, "Hindi", 120));
        Show show = shows.save(new Show(event, venue, FIXED.plus(Duration.ofDays(7))));
        ShowSeat showSeat = showSeats.save(new ShowSeat(show, seat, PRICE));

        Booking b = new Booking(buyer.getId(), show.getId(), UUID.randomUUID(), FIXED.plus(Duration.ofMinutes(8)));
        b.addSeat(showSeat.getSeat().getId(), showSeat.getPricePaise());
        return b;
    }
}
