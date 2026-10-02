package com.bookmyseat.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.bookmyseat.model.IdempotencyRecord;

@Repository
public class IdempotencyRepository {

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claim the key for this (user, key) pair. EXACTLY-ONCE lives here.
     *
     * The primary key (user_id, idem_key) is unique, so only one transaction can ever
     * insert it. If a concurrent transaction is still in flight with the same key,
     * Postgres makes us WAIT until it commits or rolls back:
     *   - it committed   -> our insert does nothing (returns false): this is a replay
     *   - it rolled back -> our insert succeeds (returns true): we do the booking
     *
     * The insert is part of the booking transaction, so the key only becomes visible
     * if the reservation itself commits. A declined booking leaves no key behind.
     *
     * @return true if we now own the key and must perform the booking
     */
    public boolean tryClaim(String userId, String key, String requestHash, UUID reservationId) {
        int rows = jdbc.update(
                "INSERT INTO idempotency_keys (user_id, idem_key, request_hash, reservation_id) "
                        + "VALUES (?, ?, ?, ?) ON CONFLICT (user_id, idem_key) DO NOTHING",
                userId, key, requestHash, reservationId);
        return rows == 1;
    }

    public Optional<IdempotencyRecord> find(String userId, String key) {
        return jdbc.query(
                        "SELECT request_hash, reservation_id FROM idempotency_keys WHERE user_id = ? AND idem_key = ?",
                        (rs, i) -> new IdempotencyRecord(
                                rs.getString("request_hash"),
                                rs.getObject("reservation_id", UUID.class)),
                        userId, key)
                .stream()
                .findFirst();
    }
}
