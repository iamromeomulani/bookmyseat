package com.bookmyseat.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bookmyseat.dto.CreateShowRequest;
import com.bookmyseat.dto.ShowResponse;
import com.bookmyseat.service.ShowService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/shows")
public class ShowController {

    private final ShowService service;

    public ShowController(ShowService service) {
        this.service = service;
    }

    // NOTE: admin-only in Step 4 (auth). Open for now so we can test.
    @PostMapping
    public ResponseEntity<ShowResponse> create(@Valid @RequestBody CreateShowRequest request) {
        ShowResponse created = service.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/shows/" + created.id()))
                .body(created);
    }

    @GetMapping("/{id}")
    public ShowResponse get(@PathVariable UUID id) {
        return service.getState(id);
    }
}
