package com.bookmyseat.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bookmyseat.dto.ReservationResponse;
import com.bookmyseat.exception.ApiException;
import com.bookmyseat.model.IdempotencyRecord;
import com.bookmyseat.model.Reservation;
import com.bookmyseat.model.Show;
import com.bookmyseat.repository.HoldingRepository;
import com.bookmyseat.repository.IdempotencyRepository;
import com.bookmyseat.repository.ReservationRepository;
import com.bookmyseat.repository.SeatRepository;
import com.bookmyseat.repository.ShowRepository;

@Service
public class ReservationService {

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final HoldingRepository holdingRepository;
    private final IdempotencyRepository idempotencyRepository;

    public ReservationService(ShowRepository showRepository,
                              SeatRepository seatRepository,
                              ReservationRepository reservationRepository,
                              HoldingRepository holdingRepository,
                              IdempotencyRepository idempotencyRepository) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
        this.reservationRepository = reservationRepository;
        this.holdingRepository = holdingRepository;
        this.idempotencyRepository = idempotencyRepository;
    }

    /**
     * Reserve one or more seats for the user, ALL-OR-NOTHING and EXACTLY-ONCE per idempotency key.
     *
     * Order of work inside ONE transaction:
     *   0. idempotency key -> claim it, or return the original reservation (replay)
     *   1. per-user limit  -> guarded counter upsert
     *   2. seats           -> conditional UPDATE per seat, in sorted order
     *   3. reservation row
     * Any failure throws, the transaction rolls back, and the claimed key, the counter
     * and any grabbed seats are all undone together.
     *
     * Lock order is always: idempotency key, then this user's counter row, then seats in
     * sorted order. A transaction waiting for an earlier lock holds none of the later
     * ones, so the locks can never form a cycle (no deadlock).
     *
     * @param idempotencyKey may be null: the request is then simply not deduplicated
     */
    @Transactional
    public ReserveResult reserve(UUID showId, String userId, List<String> requested, String idempotencyKey) {
        if (new HashSet<>(requested).size() != requested.size()) {
            throw ApiException.badRequest("duplicate_seat", "Seat list must not contain duplicates");
        }

        Show show = showRepository.findById(showId)
                .orElseThrow(() -> ApiException.notFound("show_not_found", "No show with id " + showId));

        UUID reservationId = UUID.randomUUID();

        // 0. Idempotency
        if (idempotencyKey != null) {
            String hash = requestHash(showId, requested);
            if (!idempotencyRepository.tryClaim(userId, idempotencyKey, hash, reservationId)) {
                return replay(userId, idempotencyKey, hash);
            }
        }

        // 1. Per-user limit
        int limit = show.perUserLimit();
        if (requested.size() > limit
                || !holdingRepository.tryAdd(showId, userId, requested.size(), limit)) {
            throw ApiException.conflict("per_user_limit_exceeded",
                    "A user may hold at most " + limit + " seats for this show");
        }

        // 2. Seats (the atomic decision), locked in one fixed global order
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

        return new ReserveResult(
                new ReservationResponse(reservationId, showId, userId, requested, amountPaise, "confirmed"),
                false);
    }

    /** The key was already used: same request -> return the original; different request -> 409. */
    private ReserveResult replay(String userId, String key, String hash) {
        IdempotencyRecord stored = idempotencyRepository.find(userId, key)
                .orElseThrow(() -> new IllegalStateException("Idempotency key vanished: " + key));
        if (!stored.requestHash().equals(hash)) {
            throw ApiException.conflict("idempotency_key_reuse",
                    "This idempotency key was already used with a different request");
        }
        Reservation original = reservationRepository.findById(stored.reservationId())
                .orElseThrow(() -> new IllegalStateException("Reservation missing for key: " + key));
        return new ReserveResult(
                new ReservationResponse(original.id(), original.showId(), original.userId(), original.seats(),
                        original.amountPaise(), original.status()),
                true);
    }

    /**
     * Fingerprint of what the request asks for: the show plus the SET of seats.
     * Sorted, so ["A1","A2"] and ["A2","A1"] count as the same request.
     */
    private static String requestHash(UUID showId, List<String> seats) {
        String canonical = showId + "|" + String.join(",", seats.stream().sorted().toList());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is guaranteed on every JVM
        }
    }
}
