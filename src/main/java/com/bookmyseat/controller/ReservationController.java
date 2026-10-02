package com.bookmyseat.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.bookmyseat.dto.ReservationResponse;
import com.bookmyseat.dto.ReserveRequest;
import com.bookmyseat.service.ReservationService;

import jakarta.validation.Valid;

@RestController
public class ReservationController {

    private final ReservationService service;

    public ReservationController(ReservationService service) {
        this.service = service;
    }

    /**
     * The user is taken from the verified token (jwt.getSubject()), never from the body.
     */
    @PostMapping("/shows/{id}/reserve")
    public ResponseEntity<ReservationResponse> reserve(@PathVariable("id") UUID showId,
                                                       @AuthenticationPrincipal Jwt jwt,
                                                       @Valid @RequestBody ReserveRequest request) {
        ReservationResponse created = service.reserve(showId, jwt.getSubject(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
