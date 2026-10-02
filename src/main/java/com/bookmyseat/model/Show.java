package com.bookmyseat.model;

import java.time.Instant;
import java.util.UUID;

/** A row of the 'shows' table. */
public record Show(UUID id, String name, long pricePaise, int perUserLimit, int totalSeats, Instant createdAt) {
}
