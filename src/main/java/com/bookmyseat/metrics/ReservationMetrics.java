package com.bookmyseat.metrics;

import java.util.List;

import org.springframework.stereotype.Component;

import com.bookmyseat.exception.ApiException;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Business counters, recorded AFTER the transaction has finished, so they only ever
 * count outcomes the client actually saw:
 *   bookmyseat_reservations_confirmed_total                      new bookings (201, not a replay)
 *   bookmyseat_reservations_declined_total{reason="..."}         seat_taken | per_user_limit |
 *                                                                idempotent_replay | idempotency_key_reuse | ...
 */
@Component
public class ReservationMetrics {

    private static final List<String> KNOWN_REASONS = List.of(
            "seat_taken", "per_user_limit", "idempotent_replay",
            "idempotency_key_reuse", "seat_not_found", "show_not_found", "duplicate_seat");

    private final MeterRegistry registry;
    private final Counter confirmed;

    public ReservationMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.confirmed = Counter.builder("bookmyseat.reservations.confirmed")
                .description("Reservations confirmed (new bookings)")
                .register(registry);
        // Pre-create the known series so they show up as 0 before the first decline.
        KNOWN_REASONS.forEach(this::declinedCounter);
    }

    public void confirmed() {
        confirmed.increment();
    }

    public void declined(String reason) {
        declinedCounter(reason).increment();
    }

    /** A replay is answered with the original reservation, but it created nothing new. */
    public void replayed() {
        declined("idempotent_replay");
    }

    public static String reasonFor(ApiException e) {
        return "per_user_limit_exceeded".equals(e.code()) ? "per_user_limit" : e.code();
    }

    private Counter declinedCounter(String reason) {
        return Counter.builder("bookmyseat.reservations.declined")
                .description("Reservation requests that did not create a new booking, by reason")
                .tag("reason", reason)
                .register(registry);
    }
}
