package com.bookmyseat.service;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bookmyseat.dto.CreateShowRequest;
import com.bookmyseat.dto.SeatCounts;
import com.bookmyseat.dto.SeatView;
import com.bookmyseat.dto.ShowResponse;
import com.bookmyseat.exception.ApiException;
import com.bookmyseat.model.Seat;
import com.bookmyseat.model.Show;
import com.bookmyseat.repository.SeatRepository;
import com.bookmyseat.repository.ShowRepository;
import com.bookmyseat.util.NaturalOrder;

@Service
public class ShowService {

    private static final int DEFAULT_PER_USER_LIMIT = 4;

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;

    public ShowService(ShowRepository showRepository, SeatRepository seatRepository) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
    }

    /**
     * Creates a show and all its seats (all 'available') in ONE transaction:
     * either the show exists with every seat, or nothing is created.
     */
    @Transactional
    public ShowResponse create(CreateShowRequest req) {
        List<String> labels = req.seats();
        if (new HashSet<>(labels).size() != labels.size()) {
            throw ApiException.badRequest("duplicate_seat", "Seat labels must be unique within a show");
        }

        UUID id = UUID.randomUUID();
        int limit = req.perUserLimit() == null ? DEFAULT_PER_USER_LIMIT : req.perUserLimit();

        showRepository.insert(id, req.name(), req.pricePaise(), limit, labels.size());
        seatRepository.insertAll(id, labels);

        return getState(id);
    }

    /**
     * Counts are computed from the very same rows we return (one SELECT = one
     * consistent snapshot), so available + held + confirmed == total_seats
     * holds by construction.
     */
    @Transactional(readOnly = true)
    public ShowResponse getState(UUID id) {
        Show show = showRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("show_not_found", "No show with id " + id));

        List<Seat> rows = seatRepository.findByShowId(id);

        int available = 0;
        int held = 0;
        int confirmed = 0;
        for (Seat s : rows) {
            switch (s.status()) {
                case "available" -> available++;
                case "held" -> held++;
                case "confirmed" -> confirmed++;
                default -> throw new IllegalStateException("Unknown seat status: " + s.status());
            }
        }

        List<SeatView> seats = rows.stream()
                .sorted((x, y) -> NaturalOrder.compare(x.label(), y.label()))
                .map(s -> new SeatView(s.label(), s.status()))
                .toList();

        return new ShowResponse(
                show.id(), show.name(), show.pricePaise(), show.perUserLimit(), show.totalSeats(),
                available, held, confirmed, new SeatCounts(available, held, confirmed),
                seats, show.createdAt());
    }
}
