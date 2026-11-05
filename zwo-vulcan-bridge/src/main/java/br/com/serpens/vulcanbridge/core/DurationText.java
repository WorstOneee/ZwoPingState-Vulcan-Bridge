package br.com.serpens.vulcanbridge.core;

import java.util.Locale;

/** Durações do config: "24h", "7d", "1d12h", "90m", "30s", "2w". */
public final class DurationText {

    private DurationText() {
    }

    /** Millis, ou {@code -1} se o texto for inválido. */
    public static long parse(String text) {
        if (text == null) {
            return -1;
        }
        String s = text.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (s.isEmpty()) {
            return -1;
        }
        long total = 0;
        long number = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                number = (number < 0 ? 0 : number) * 10 + (c - '0');
                if (number > 1_000_000) {
                    return -1;
                }
                continue;
            }
            long unit = switch (c) {
                case 's' -> 1_000L;
                case 'm' -> 60_000L;
                case 'h' -> 3_600_000L;
                case 'd' -> 86_400_000L;
                case 'w' -> 604_800_000L;
                default -> -1L;
            };
            if (unit < 0 || number < 0) {
                return -1;
            }
            total += number * unit;
            number = -1;
        }
        return number >= 0 || total <= 0 ? -1 : total;
    }

    public static String format(long millis) {
        long m = millis / 60_000L;
        long d = m / 1440;
        long h = (m % 1440) / 60;
        long min = m % 60;
        StringBuilder b = new StringBuilder();
        if (d > 0) b.append(d).append('d');
        if (h > 0) b.append(h).append('h');
        if (min > 0 || b.isEmpty()) b.append(min).append("min");
        return b.toString();
    }
}
