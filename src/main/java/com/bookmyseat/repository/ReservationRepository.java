package com.bookmyseat.repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReservationRepository {

    private final JdbcTemplate jdbc;

    public ReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertConfirmed(UUID id, UUID showId, String userId, List<String> seats, long amountPaise) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO reservations (id, show_id, user_id, seats, amount_paise, status) "
                            + "VALUES (?, ?, ?, ?, ?, 'confirmed')");
            ps.setObject(1, id);
            ps.setObject(2, showId);
            ps.setString(3, userId);
            ps.setArray(4, con.createArrayOf("text", seats.toArray(new String[0])));
            ps.setLong(5, amountPaise);
            return ps;
        });
    }
}
