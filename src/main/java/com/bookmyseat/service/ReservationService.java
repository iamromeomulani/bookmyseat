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
import com.bookmyseat.repository.ReservationRepository;
import com.bookmyseat.repository.SeatRepository;
import com.bookmyseat.repository.ShowRepository;

@Service
public class ReservationService {

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;

    public ReservationService(ShowRepository showRepository,
                              SeatRepository seatRepository,
                              ReservationRepository reservationRepository) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
        this.reservationRepository = reservationRepository;
    }

    /**
     * Reserve one or more seats for the user, ALL-OR-NOTHING.
     *
     * If any requested seat is unavailable the whole request is declined with 409
     * and NOTHING is kept: the exception rolls the transaction back, undoing any
     * seats we had already grabbed in this request.
     *
     * Deadlock avoidance: seats are always locked in ONE fixed global order
     * (sorted by label). Two requests for {A1,A2} and {A2,A1} both lock A1 first,
     * so they can never wait on each other in a cycle.
     */
    @Transactional
    public ReservationResponse reserve(UUID showId, String userId, ReserveRequest req) {
        List<String> requested = req.seats();
        if (new HashSet<>(requested).size() != requested.size()) {
            throw ApiException.badRequest("duplicate_seat", "Seat list must not contain duplicates");
        }

        Show show = showRepository.findById(showId)
                .orElseThrow(() -> ApiException.notFound("show_not_found", "No show with id " + showId));

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

        long amountPaise = Math.multiplyExact(show.pricePaise(), (long) requested.size());
        reservationRepository.insertConfirmed(reservationId, showId, userId, requested, amountPaise);

        return new ReservationResponse(reservationId, showId, userId, requested, amountPaise, "confirmed");
    }
}
