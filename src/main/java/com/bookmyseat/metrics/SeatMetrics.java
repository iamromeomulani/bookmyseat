package com.bookmyseat.metrics;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.bookmyseat.config.MonitoringDb;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Seat gauges read straight from the database (the source of truth), across all shows:
 *   bookmyseat_seats_available / bookmyseat_seats_held / bookmyseat_seats_confirmed
 *
 * They are computed when Prometheus scrapes, using the monitoring pool, with a 200 ms cache so
 * the three gauges of one scrape share a single query. On failure the last good value is kept.
 * Because they come from the same table as GET /shows/{id}, they reconcile with the API.
 */
@Component
public class SeatMetrics {

    private static final Logger log = LoggerFactory.getLogger(SeatMetrics.class);
    private static final long TTL_MS = 200;

    private record Snapshot(long available, long held, long confirmed, long takenAtMs) {
    }

    private final JdbcTemplate jdbc;
    private volatile Snapshot snapshot = new Snapshot(0, 0, 0, 0);

    public SeatMetrics(MonitoringDb monitoringDb, MeterRegistry registry) {
        this.jdbc = monitoringDb.jdbcTemplate();
        Gauge.builder("bookmyseat.seats.available", () -> current().available())
                .description("Seats currently available, all shows").register(registry);
        Gauge.builder("bookmyseat.seats.held", () -> current().held())
                .description("Seats currently held, all shows").register(registry);
        Gauge.builder("bookmyseat.seats.confirmed", () -> current().confirmed())
                .description("Seats currently confirmed, all shows").register(registry);
    }

    private Snapshot current() {
        Snapshot s = snapshot;
        if (System.currentTimeMillis() - s.takenAtMs() < TTL_MS) {
            return s;
        }
        synchronized (this) {
            s = snapshot;
            if (System.currentTimeMillis() - s.takenAtMs() < TTL_MS) {
                return s;
            }
            try {
                long available = 0;
                long held = 0;
                long confirmed = 0;
                for (Map<String, Object> row : jdbc.queryForList(
                        "SELECT status, count(*) AS c FROM seats GROUP BY status")) {
                    String status = (String) row.get("status");
                    long count = ((Number) row.get("c")).longValue();
                    switch (status) {
                        case "available" -> available = count;
                        case "held" -> held = count;
                        case "confirmed" -> confirmed = count;
                        default -> log.warn("Unknown seat status in metrics: {}", status);
                    }
                }
                snapshot = new Snapshot(available, held, confirmed, System.currentTimeMillis());
            } catch (Exception e) {
                log.warn("Could not refresh seat gauges, serving last value: {}", e.getMessage());
            }
            return snapshot;
        }
    }
}
