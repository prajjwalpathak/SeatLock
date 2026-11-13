package com.seatlock.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatlock.catalog.domain.Event;
import com.seatlock.catalog.domain.EventCategory;
import com.seatlock.catalog.domain.EventRepository;
import com.seatlock.catalog.domain.Seat;
import com.seatlock.catalog.domain.SeatRepository;
import com.seatlock.catalog.domain.Show;
import com.seatlock.catalog.domain.ShowRepository;
import com.seatlock.catalog.domain.ShowSeat;
import com.seatlock.catalog.domain.ShowSeatRepository;
import com.seatlock.catalog.domain.Venue;
import com.seatlock.catalog.domain.VenueRepository;
import com.seatlock.common.config.JpaAuditingConfig;
import com.seatlock.identity.domain.AuthProvider;
import com.seatlock.identity.domain.Role;
import com.seatlock.identity.domain.User;
import com.seatlock.identity.domain.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
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

/**
 * Runs against the real local Postgres (Testcontainers from W1·D5).
 * Every test runs in a transaction that is rolled back.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
@ActiveProfiles("test")
class BookingPersistenceTest {

    static final Instant FIXED = Instant.parse("2026-11-11T10:00:00Z");
    static final long PRICE = 50_000; // ₹500.00 in paise

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
    @Autowired EntityManager em;

    @Test
    void savesBookingWithSeatsAndAuditTimestampsFromOurClock() {
        ShowSeat showSeat = seedShowSeat();
        User buyer = seedUser("buyer@seatlock.dev");

        Booking booking = newBooking(buyer, showSeat);
        bookings.saveAndFlush(booking);
        em.clear(); // force a real reload from the database

        Booking loaded = bookings.findByPublicId(booking.getPublicId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(BookingStatus.HELD);
        assertThat(loaded.getTotalPaise()).isEqualTo(PRICE);
        assertThat(loaded.getCurrency()).isEqualTo("INR");
        assertThat(loaded.getSeats()).hasSize(1);
        assertThat(loaded.getSeats().getFirst().isActive()).isTrue();
        assertThat(loaded.getCreatedAt()).isEqualTo(FIXED); // came from our Clock, not the JVM
        assertThat(loaded.getVersion()).isZero();
    }

    @Test
    void databaseRejectsSecondLiveBookingForTheSameSeat() {
        ShowSeat showSeat = seedShowSeat();
        User alice = seedUser("alice@seatlock.dev");
        User bob = seedUser("bob@seatlock.dev");

        bookings.saveAndFlush(newBooking(alice, showSeat));
        Booking oversell = newBooking(bob, showSeat);

        assertThatThrownBy(() -> bookings.saveAndFlush(oversell))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("ux_booking_seats_live_seat");
    }

    @Test
    void storesEmailInLowerCase() {
        seedUser("Mixed.Case@SeatLock.dev");
        assertThat(users.findByEmail("mixed.case@seatlock.dev")).isPresent();
    }

    // ---------- fixtures ----------

    private User seedUser(String email) {
        return users.save(new User(email, "not-a-real-hash", "Test User", Role.USER, AuthProvider.LOCAL));
    }

    private ShowSeat seedShowSeat() {
        User organizer = users.save(new User("organizer@seatlock.dev", "not-a-real-hash",
                "Organizer", Role.ORGANIZER, AuthProvider.LOCAL));
        Venue venue = venues.save(new Venue("Test Hall", "Delhi", "Test address"));
        Seat seat = seats.save(new Seat(venue, "GOLD", "A", 1, 0, 0, false));
        Event event = events.save(new Event(organizer.getId(), "Test Event", "A test event",
                EventCategory.CONCERT, "Hindi", 120));
        Show show = shows.save(new Show(event, venue, FIXED.plus(Duration.ofDays(7))));
        return showSeats.save(new ShowSeat(show, seat, PRICE));
    }

    private Booking newBooking(User user, ShowSeat showSeat) {
        Booking booking = new Booking(user.getId(), showSeat.getShow().getId(), UUID.randomUUID(),
                FIXED.plus(Duration.ofMinutes(5)));
        booking.addSeat(showSeat.getSeat().getId(), showSeat.getPricePaise());
        return booking;
    }
}