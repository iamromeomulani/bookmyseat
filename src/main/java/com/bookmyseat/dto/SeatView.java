package com.bookmyseat.dto;

/** One seat and its status: available | held | confirmed. */
public record SeatView(String seat, String status) {
}
