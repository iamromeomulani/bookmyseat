package com.bookmyseat.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.bookmyseat.dto.ReservationResponse;
import com.bookmyseat.dto.ReserveRequest;
import com.bookmyseat.exception.ApiException;
import com.bookmyseat.service.ReservationService;
import com.bookmyseat.service.ReserveResult;

import jakarta.validation.Valid;

@RestController
public class ReservationController {

    private static final int MAX_KEY_LENGTH = 128;

    private final ReservationService service;

    public ReservationController(ReservationService service) {
        this.service = service;
    }

    /**
     * The user is taken from the verified token (jwt.getSubject()), never from the body.
     * The idempotency key may come from the "Idempotency-Key" header or the body field.
     * A replay returns the original reservation with the same 201 and an
     * "Idempotent-Replayed: true" header.
     */
    @PostMapping("/shows/{id}/reserve")
    public ResponseEntity<ReservationResponse> reserve(
            @PathVariable("id") UUID showId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String headerKey,
            @Valid @RequestBody ReserveRequest request) {

        String key = resolveKey(headerKey, request.idempotencyKey());
        ReserveResult result = service.reserve(showId, jwt.getSubject(), request.seats(), key);

        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
        if (result.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(result.reservation());
    }

    private static String resolveKey(String header, String body) {
        String h = blankToNull(header);
        String b = blankToNull(body);
        if (h != null && b != null && !h.equals(b)) {
            throw ApiException.badRequest("idempotency_key_mismatch",
                    "Idempotency-Key header and idempotency_key body field differ");
        }
        String key = h != null ? h : b;
        if (key != null && key.length() > MAX_KEY_LENGTH) {
            throw ApiException.badRequest("invalid_idempotency_key",
                    "Idempotency key must be at most " + MAX_KEY_LENGTH + " characters");
        }
        return key;
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }
}
