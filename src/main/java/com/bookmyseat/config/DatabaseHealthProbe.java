package com.bookmyseat.config;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Pings the database every 2 seconds (SELECT 1) on the monitoring pool and remembers the result.
 *
 * FAILS CLOSED: the state starts as DOWN and only becomes UP after a successful ping, and
 * any failure flips it back to DOWN within seconds. Reading the state is instant, so health
 * endpoints answer immediately even while the main pool is saturated.
 */
@Component
public class DatabaseHealthProbe {

    private static final Logger log = LoggerFactory.getLogger(DatabaseHealthProbe.class);

    private final MonitoringDb monitoringDb;
    private volatile boolean up = false;
    private ScheduledExecutorService scheduler;

    public DatabaseHealthProbe(MonitoringDb monitoringDb) {
        this.monitoringDb = monitoringDb;
    }

    @PostConstruct
    void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "db-health-probe");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::check, 0, 2, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }

    public boolean isUp() {
        return up;
    }

    private void check() {
        boolean nowUp;
        try {
            Integer one = monitoringDb.jdbcTemplate().queryForObject("SELECT 1", Integer.class);
            nowUp = Integer.valueOf(1).equals(one);
        } catch (Exception e) {
            nowUp = false;
            if (up) {
                log.error("Database health check failed: {}", e.getMessage());
            }
        }
        if (nowUp != up) {
            log.info("Database state changed: {}", nowUp ? "UP" : "DOWN");
        }
        up = nowUp;
    }
}
