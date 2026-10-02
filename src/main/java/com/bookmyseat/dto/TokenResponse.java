package com.bookmyseat.dto;

public record TokenResponse(String accessToken, String tokenType, long expiresIn, String userId, String role) {
}
