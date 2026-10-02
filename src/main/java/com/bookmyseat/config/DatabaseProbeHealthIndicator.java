package com.bookmyseat.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Plugs the DB probe into Spring's health system. application.yml adds it ("databaseProbe")
 * to the readiness group, so /actuator/health/readiness returns 503 when the DB is down.
 */
@Component
public class DatabaseProbeHealthIndicator implements HealthIndicator {

    private final DatabaseHealthProbe probe;

    public DatabaseProbeHealthIndicator(DatabaseHealthProbe probe) {
        this.probe = probe;
    }

    @Override
    public Health health() {
        return probe.isUp() ? Health.up().build() : Health.down().build();
    }
}
