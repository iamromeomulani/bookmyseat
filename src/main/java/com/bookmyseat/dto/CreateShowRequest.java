package com.bookmyseat.dto;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * POST /shows body. Money is a whole number of paise: Long, never a float
 * (floats like 250.5 are rejected, see accept-float-as-int in application.yml).
 */
public record CreateShowRequest(
        @NotBlank @Size(max = 200) String name,

        @NotNull
        @Size(min = 1, max = 100000)
        List<@NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,32}", message = "must be 1-32 chars of letters, digits, _ or -") String> seats,

        @NotNull @PositiveOrZero Long pricePaise,

        @Min(1) @Max(100) Integer perUserLimit) {
}
