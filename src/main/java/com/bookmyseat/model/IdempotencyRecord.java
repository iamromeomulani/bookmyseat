package com.bookmyseat.model;

import java.util.UUID;

/** What we stored the first time an idempotency key was used. */
public record IdempotencyRecord(String requestHash, UUID reservationId) {
}
