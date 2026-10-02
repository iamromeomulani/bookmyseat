package com.bookmyseat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * POST /auth/token body. adminKey is optional: with the correct key the token gets role ADMIN,
 * without it the token is a normal USER.
 */
public record TokenRequest(
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9_.@-]{1,64}", message = "must be 1-64 chars of letters, digits, _ . @ or -")
        String userId,

        String adminKey) {
}
