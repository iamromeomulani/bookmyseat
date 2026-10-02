package com.bookmyseat.repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.bookmyseat.model.Show;

@Repository
public class ShowRepository {

    private final JdbcTemplate jdbc;

    public ShowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, String name, long pricePaise, int perUserLimit, int totalSeats) {
        jdbc.update(
                "INSERT INTO shows (id, name, price_paise, per_user_limit, total_seats) VALUES (?, ?, ?, ?, ?)",
                id, name, pricePaise, perUserLimit, totalSeats);
    }

    public Optional<Show> findById(UUID id) {
        return jdbc.query(
                        "SELECT id, name, price_paise, per_user_limit, total_seats, created_at FROM shows WHERE id = ?",
                        (rs, i) -> new Show(
                                rs.getObject("id", UUID.class),
                                rs.getString("name"),
                                rs.getLong("price_paise"),
                                rs.getInt("per_user_limit"),
                                rs.getInt("total_seats"),
                                rs.getObject("created_at", OffsetDateTime.class).toInstant()),
                        id)
                .stream()
                .findFirst();
    }
}
