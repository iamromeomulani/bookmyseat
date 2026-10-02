package com.bookmyseat.repository;

import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.bookmyseat.model.Reservation;

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

    public Optional<Reservation> findById(UUID id) {
        return jdbc.query(
                        "SELECT id, show_id, user_id, seats, amount_paise, status, created_at "
                                + "FROM reservations WHERE id = ?",
                        (rs, i) -> new Reservation(
                                rs.getObject("id", UUID.class),
                                rs.getObject("show_id", UUID.class),
                                rs.getString("user_id"),
                                Arrays.asList((String[]) rs.getArray("seats").getArray()),
                                rs.getLong("amount_paise"),
                                rs.getString("status"),
                                rs.getObject("created_at", OffsetDateTime.class).toInstant()),
                        id)
                .stream()
                .findFirst();
    }
}
