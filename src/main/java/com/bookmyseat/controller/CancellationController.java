package com.bookmyseat.controller;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bookmyseat.dto.ReservationResponse;
import com.bookmyseat.service.CancellationService;

@RestController
public class CancellationController {

    private final CancellationService service;

    public CancellationController(CancellationService service) {
        this.service = service;
    }

    /** Only the owner (the token's user) can cancel; anyone else gets 403. */
    @PostMapping("/reservations/{id}/cancel")
    public ReservationResponse cancel(@PathVariable("id") UUID reservationId,
                                      @AuthenticationPrincipal Jwt jwt) {
        return service.cancel(reservationId, jwt.getSubject());
    }
}
