package com.bookmyseat.repository;

import java.sql.PreparedStatement;
import java.util.List;
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
}
