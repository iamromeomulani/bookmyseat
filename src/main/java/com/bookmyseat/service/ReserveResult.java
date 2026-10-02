package com.bookmyseat.service;

import com.bookmyseat.dto.ReservationResponse;

/** The reservation plus whether it was a replay of an earlier request with the same idempotency key. */
public record ReserveResult(ReservationResponse reservation, boolean replayed) {
}
