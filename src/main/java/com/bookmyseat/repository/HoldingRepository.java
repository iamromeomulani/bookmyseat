package com.bookmyseat.repository;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Per-user, per-show seat counter (table user_show_holdings).
 * It is the single place that decides "has this user hit the booking limit?".
 */
@Repository
public class HoldingRepository {

    private final JdbcTemplate jdbc;

    public HoldingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Race-free limit check + increment in ONE statement (an upsert with a guard).
     *
     * - First time this user books in this show: the row is inserted with 'seats'.
     *   (The caller has already checked seats <= limit.)
     * - Otherwise the row is updated ONLY IF seat_count + seats <= limit.
     *   Postgres locks the row for the update, so parallel requests from the same
     *   user are serialized, and each one re-checks the guard against the latest
     *   committed count. 10 parallel requests can never push the count past the limit.
     *
     * The increment lives inside the caller's transaction: if the booking later
     * fails (e.g. a seat is taken) the rollback undoes it automatically.
     *
     * @return true if the user still fits under the limit (counter was incremented)
     */
    public boolean tryAdd(UUID showId, String userId, int seats, int limit) {
        int rows = jdbc.update(
                "INSERT INTO user_show_holdings (show_id, user_id, seat_count) VALUES (?, ?, ?) "
                        + "ON CONFLICT (show_id, user_id) DO UPDATE "
                        + "SET seat_count = user_show_holdings.seat_count + EXCLUDED.seat_count "
                        + "WHERE user_show_holdings.seat_count + EXCLUDED.seat_count <= ?",
                showId, userId, seats, limit);
        return rows == 1;
    }
}
