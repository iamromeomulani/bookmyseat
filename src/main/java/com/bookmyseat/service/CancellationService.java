package com.bookmyseat.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bookmyseat.dto.ReservationResponse;
import com.bookmyseat.exception.ApiException;
import com.bookmyseat.model.Reservation;
import com.bookmyseat.repository.HoldingRepository;
import com.bookmyseat.repository.ReservationRepository;
import com.bookmyseat.repository.SeatRepository;

@Service
public class CancellationService {

    private final ReservationRepository reservationRepository;
    private final SeatRepository seatRepository;
    private final HoldingRepository holdingRepository;

    public CancellationService(ReservationRepository reservationRepository,
                               SeatRepository seatRepository,
                               HoldingRepository holdingRepository) {
        this.reservationRepository = reservationRepository;
        this.seatRepository = seatRepository;
        this.holdingRepository = holdingRepository;
    }

    /**
     * Cancels a reservation (owner only) and gives its seats back, all in ONE transaction.
     *
     *  1. Claim the cancel: UPDATE reservations ... WHERE id AND user_id = caller AND status = 'confirmed'.
     *     Only the owner can match, and only once, so double cancels are harmless.
     *  2. Give the seats back to the user's quota.
     *  3. Release each seat, guarded by reservation_id, in sorted order (same lock order as reserve).
     *
     * Outcomes: 200 cancelled | 200 already cancelled (idempotent) | 403 not the owner | 404 unknown id.
     */
    @Transactional
    public ReservationResponse cancel(UUID reservationId, String userId) {
        Optional<Reservation> claimed = reservationRepository.markCancelled(reservationId, userId);

        if (claimed.isEmpty()) {
            // Nothing was cancelled by us. Find out why.
            Reservation existing = reservationRepository.findById(reservationId)
                    .orElseThrow(() -> ApiException.notFound("reservation_not_found",
                            "No reservation with id " + reservationId));
            if (!existing.userId().equals(userId)) {
                throw ApiException.forbidden("not_reservation_owner",
                        "Only the owner can cancel this reservation");
            }
            return toResponse(existing); // owner, already cancelled: same answer again
        }

        Reservation reservation = claimed.get();

        if (!holdingRepository.release(reservation.showId(), userId, reservation.seats().size())) {
            throw new IllegalStateException("Holding counter missing for reservation " + reservationId);
        }

        List<String> lockOrder = reservation.seats().stream().sorted().toList();
        for (String label : lockOrder) {
            if (!seatRepository.release(reservation.showId(), label, reservationId)) {
                // Cannot happen unless data is corrupted. Fail loudly and roll everything back.
                throw new IllegalStateException(
                        "Seat " + label + " is not confirmed to reservation " + reservationId);
            }
        }

        return toResponse(reservation);
    }

    private static ReservationResponse toResponse(Reservation r) {
        return new ReservationResponse(r.id(), r.showId(), r.userId(), r.seats(), r.amountPaise(), r.status());
    }
}
