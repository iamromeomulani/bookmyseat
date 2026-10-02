package com.bookmyseat.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Show state. Invariant: available + held + confirmed == totalSeats.
 * Counts are derived from the SAME rows as the seats list, so they can never disagree.
 */
public record ShowResponse(
        UUID id,
        String name,
        long pricePaise,
        int perUserLimit,
        int totalSeats,
        int available,
        int held,
        int confirmed,
        SeatCounts counts,
        List<SeatView> seats,
        Instant createdAt) {
}
