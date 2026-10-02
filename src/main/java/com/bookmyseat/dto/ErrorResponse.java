package com.bookmyseat.dto;

/** Standard error body: {"error": "code", "message": "human text"}. */
public record ErrorResponse(String error, String message) {
}
