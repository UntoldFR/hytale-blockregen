package com.nopefr.blockregen;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Parses a duration such as "90", "90s", "5m" or "2h" into a number of
 * seconds. When no unit suffix is given, the value is interpreted as
 * minutes.
 */
final class DurationParser {

    private DurationParser() {
    }

    /** Returns the duration in seconds, or null if the input is not a valid duration. */
    @Nullable
    static Integer parseToSeconds(@Nonnull String input) {
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        char lastChar = Character.toLowerCase(trimmed.charAt(trimmed.length() - 1));
        String numberPart;
        long multiplier;

        switch (lastChar) {
            case 'h':
                numberPart = trimmed.substring(0, trimmed.length() - 1);
                multiplier = 3600L;
                break;
            case 'm':
                numberPart = trimmed.substring(0, trimmed.length() - 1);
                multiplier = 60L;
                break;
            case 's':
                numberPart = trimmed.substring(0, trimmed.length() - 1);
                multiplier = 1L;
                break;
            default:
                // No unit specified: default to minutes.
                numberPart = trimmed;
                multiplier = 60L;
                break;
        }

        numberPart = numberPart.trim();
        if (numberPart.isEmpty()) {
            return null;
        }

        long value;
        try {
            value = Long.parseLong(numberPart);
        } catch (NumberFormatException e) {
            return null;
        }

        long totalSeconds = value * multiplier;
        if (totalSeconds <= 0 || totalSeconds > Integer.MAX_VALUE) {
            return null;
        }

        return (int) totalSeconds;
    }

    /** Formats a number of seconds back into a compact human-readable duration (e.g. "2h", "5m", "45s"). */
    @Nonnull
    static String formatSeconds(int totalSeconds) {
        if (totalSeconds % 3600 == 0) {
            return (totalSeconds / 3600) + "h";
        } else if (totalSeconds % 60 == 0) {
            return (totalSeconds / 60) + "m";
        } else {
            return totalSeconds + "s";
        }
    }
}
