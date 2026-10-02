package com.bookmyseat.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * POST /shows/{id}/reserve body.
 * There is deliberately NO user field: identity comes only from the token.
 * Unknown JSON fields (e.g. a spoofed "user_id") are ignored.
 * idempotencyKey is accepted now and enforced in Step 7.
 */
public record ReserveRequest(
        @NotNull
        @Size(min = 1, max = 100)
        List<@NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,32}", message = "must be 1-32 chars of letters, digits, _ or -") String> seats,

        @Size(max = 128) String idempotencyKey) {
}
