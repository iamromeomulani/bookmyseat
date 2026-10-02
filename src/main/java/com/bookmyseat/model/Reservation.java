package com.bookmyseat.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A row of the 'reservations' table. */
public record Reservation(UUID id, UUID showId, String userId, List<String> seats,
                          long amountPaise, String status, Instant createdAt) {
}
