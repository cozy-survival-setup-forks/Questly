package dev.questly.util;

/** A time as 25:03 or 1:25:03. */
public final class Duration {

    private Duration() {
    }

    public static String clock(long millis) {
        long total = Math.max(0, (millis + 999) / 1000);
        long hours = total / 3600;
        long minutes = (total % 3600) / 60;
        long seconds = total % 60;
        if (hours > 0) return String.format("%d:%02d:%02d", hours, minutes, seconds);
        return String.format("%02d:%02d", minutes, seconds);
    }
}
