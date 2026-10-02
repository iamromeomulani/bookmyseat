package com.bookmyseat.service;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bookmyseat.dto.ReservationResponse;
import com.bookmyseat.dto.ReserveRequest;
import com.bookmyseat.exception.ApiException;
import com.bookmyseat.model.Show;
import com.bookmyseat.repository.HoldingRepository;
import com.bookmyseat.repository.ReservationRepository;
import com.bookmyseat.repository.SeatRepository;
import com.bookmyseat.repository.ShowRepository;

@Service
public class ReservationService {

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final HoldingRepository holdingRepository;

    public ReservationService(ShowRepository showRepository,
                              SeatRepository seatRepository,
                              ReservationRepository reservationRepository,
                              HoldingRepository holdingRepository) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
        this.reservationRepository = reservationRepository;
        this.holdingRepository = holdingRepository;
    }

    /**
     * Reserve one or more seats for the user, ALL-OR-NOTHING.
     *
     * Order of work inside ONE transaction:
     *   1. per-user limit  -> guarded counter upsert (HoldingRepository.tryAdd)
     *   2. seats           -> conditional UPDATE per seat, in sorted order
     *   3. reservation row
     * Any failure throws, the transaction rolls back, and BOTH the counter and
     * any seats grabbed so far are undone together.
     *
     * Lock order is always: this user's counter row first, then seats in sorted
     * order. A transaction waiting for a counter row has not locked any seat yet,
     * so the two kinds of lock can never form a cycle (no deadlock).
     */
    @Transactional
    public ReservationResponse reserve(UUID showId, String userId, ReserveRequest req) {
        List<String> requested = req.seats();
        if (new HashSet<>(requested).size() != requested.size()) {
            throw ApiException.badRequest("duplicate_seat", "Seat list must not contain duplicates");
        }

        Show show = showRepository.findById(showId)
                .orElseThrow(() -> ApiException.notFound("show_not_found", "No show with id " + showId));

        // 1. Per-user limit
        int limit = show.perUserLimit();
        if (requested.size() > limit
                || !holdingRepository.tryAdd(showId, userId, requested.size(), limit)) {
            throw ApiException.conflict("per_user_limit_exceeded",
                    "A user may hold at most " + limit + " seats for this show");
        }

        // 2. Seats (the atomic decision), locked in one fixed global order
        UUID reservationId = UUID.randomUUID();
        List<String> lockOrder = requested.stream().sorted().toList();
        for (String label : lockOrder) {
            if (!seatRepository.tryGrab(showId, label, userId, reservationId)) {
                if (seatRepository.findStatus(showId, label).isEmpty()) {
                    throw ApiException.notFound("seat_not_found", "Seat " + label + " does not exist in this show");
                }
                throw ApiException.conflict("seat_taken", "Seat " + label + " is already taken");
            }
        }

        // 3. Reservation record
        long amountPaise = Math.multiplyExact(show.pricePaise(), (long) requested.size());
        reservationRepository.insertConfirmed(reservationId, showId, userId, requested, amountPaise);

        return new ReservationResponse(reservationId, showId, userId, requested, amountPaise, "confirmed");
    }
}
