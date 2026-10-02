package com.bookmyseat.model;

/** A row of the 'seats' table (only the columns we need for show state). */
public record Seat(String label, String status) {
}
