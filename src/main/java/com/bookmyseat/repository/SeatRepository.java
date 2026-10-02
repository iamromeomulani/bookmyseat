package com.bookmyseat.repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.bookmyseat.model.Seat;

@Repository
public class SeatRepository {

    private final JdbcTemplate jdbc;

    public SeatRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One round trip for all seats: INSERT ... SELECT unnest(array). All start 'available'. */
    public void insertAll(UUID showId, List<String> labels) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO seats (show_id, label) SELECT ?, unnest(?::text[])");
            ps.setObject(1, showId);
            ps.setArray(2, con.createArrayOf("text", labels.toArray(new String[0])));
            return ps;
        });
    }

    /** A single SELECT = a single consistent snapshot of every seat in the show. */
    public List<Seat> findByShowId(UUID showId) {
        return jdbc.query(
                "SELECT label, status FROM seats WHERE show_id = ?",
                (rs, i) -> new Seat(rs.getString("label"), rs.getString("status")),
                showId);
    }

    /**
     * THE ATOMIC DECISION.
     *
     * One conditional UPDATE: "take this seat only if it is still available".
     * Postgres locks the row, and if another transaction holds it we WAIT, then
     * re-check the WHERE clause against the committed result. So of N concurrent
     * callers exactly one sees status='available' and updates 1 row; everybody
     * else updates 0 rows. There is no read-then-write gap to race through.
     *
     * @return true if we won the seat, false if it was not available
     */
    public boolean tryGrab(UUID showId, String label, String userId, UUID reservationId) {
        int updated = jdbc.update(
                "UPDATE seats SET status = 'confirmed', user_id = ?, reservation_id = ?, updated_at = now() "
                        + "WHERE show_id = ? AND label = ? AND status = 'available'",
                userId, reservationId, showId, label);
        return updated == 1;
    }

    /** Used only on the failure path, to tell "seat taken" apart from "no such seat". */
    public Optional<String> findStatus(UUID showId, String label) {
        return jdbc.query(
                        "SELECT status FROM seats WHERE show_id = ? AND label = ?",
                        (rs, i) -> rs.getString("status"),
                        showId, label)
                .stream()
                .findFirst();
    }
}
