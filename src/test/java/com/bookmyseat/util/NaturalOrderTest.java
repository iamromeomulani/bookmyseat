package com.bookmyseat.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class NaturalOrderTest {

    @Test
    void sortsNumbersNumerically() {
        List<String> seats = new ArrayList<>(List.of("A10", "A2", "B1", "A1", "A11", "A9"));
        seats.sort(NaturalOrder.COMPARATOR);
        assertEquals(List.of("A1", "A2", "A9", "A10", "A11", "B1"), seats);
    }

    @Test
    void sortsRowsBeforeNumbers() {
        List<String> seats = new ArrayList<>(List.of("B2", "A12", "A3", "B10"));
        seats.sort(NaturalOrder.COMPARATOR);
        assertEquals(List.of("A3", "A12", "B2", "B10"), seats);
    }
}
