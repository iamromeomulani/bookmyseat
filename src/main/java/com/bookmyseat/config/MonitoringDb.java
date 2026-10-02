package com.bookmyseat.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import jakarta.annotation.PreDestroy;

/**
 * A tiny, separate connection pool (2 connections) used ONLY for health checks and metrics.
 *
 * Why separate: under a burst the main pool is saturated and requests queue for a connection.
 * If readiness shared that pool it would queue too, time out, and the platform would
 * restart a perfectly healthy service in the middle of the load. Monitoring must not
 * compete with the traffic it is monitoring.
 */
@Component
public class MonitoringDb {

    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public MonitoringDb(@Value("${spring.datasource.url}") String url,
                        @Value("${spring.datasource.username}") String username,
                        @Value("${spring.datasource.password}") String password) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(username);
        cfg.setPassword(password);
        cfg.setPoolName("monitoring-pool");
        cfg.setMaximumPoolSize(2);
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(3000);
        cfg.setInitializationFailTimeout(-1); // start even if the DB is not reachable yet (cold start)
        this.dataSource = new HikariDataSource(cfg);
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.jdbcTemplate.setQueryTimeout(3);
    }

    public JdbcTemplate jdbcTemplate() {
        return jdbcTemplate;
    }

    @PreDestroy
    void close() {
        dataSource.close();
    }
}
