package com.bookmyseat.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bookmyseat.config.DatabaseHealthProbe;

@RestController
public class HealthController {

    private final DatabaseHealthProbe probe;

    public HealthController(DatabaseHealthProbe probe) {
        this.probe = probe;
    }

    /** Liveness: "is the process running?" Touches no dependency, so it never fails because of the DB. */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    /** Readiness: "can this instance serve traffic?" 200 only if the database is reachable, else 503 (fails closed). */
    @GetMapping("/ready")
    public ResponseEntity<Map<String, String>> ready() {
        boolean up = probe.isUp();
        String state = up ? "UP" : "DOWN";
        return ResponseEntity.status(up ? 200 : 503).body(Map.of("status", state, "database", state));
    }
}
