package com.bookmyseat.util;

import java.util.Comparator;

/** Natural ordering so seats read A1, A2, ..., A9, A10 (not A1, A10, A2). */
public final class NaturalOrder {

    public static final Comparator<String> COMPARATOR = NaturalOrder::compare;

    private NaturalOrder() {
    }

    public static int compare(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int startA = i;
                int startB = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) {
                    i++;
                }
                while (j < b.length() && Character.isDigit(b.charAt(j))) {
                    j++;
                }
                String numA = stripLeadingZeros(a.substring(startA, i));
                String numB = stripLeadingZeros(b.substring(startB, j));
                if (numA.length() != numB.length()) {
                    return Integer.compare(numA.length(), numB.length());
                }
                int c = numA.compareTo(numB);
                if (c != 0) {
                    return c;
                }
            } else {
                if (ca != cb) {
                    return Character.compare(ca, cb);
                }
                i++;
                j++;
            }
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    private static String stripLeadingZeros(String s) {
        return s.replaceFirst("^0+(?!$)", "");
    }
}
