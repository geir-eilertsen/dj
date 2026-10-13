package dj.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * What the listener asked for in their own words ("mellow prog for Sunday morning"), and the
 * musical style DJ took that to mean.
 *
 * @param setAt when the listener asked
 */
public record Mood(String text, Style style, Instant setAt) {

    public Mood {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(setAt, "setAt");
    }

    /**
     * @param tags     style tags in the vocabulary artists are tagged with (Last.fm), lower case
     * @param artists  artists the request names or implies
     * @param fromYear first release year wanted, 0 for no limit
     * @param toYear   last release year wanted, 0 for no limit
     */
    public record Style(List<String> tags, List<String> artists, int fromYear, int toYear) {

        public static final Style NONE = new Style(List.of(), List.of(), 0, 0);

        public Style {
            tags = tags.stream().map(t -> t.strip().toLowerCase(Locale.ROOT)).filter(t -> !t.isEmpty()).distinct().toList();
            artists = artists.stream().map(String::strip).filter(a -> !a.isEmpty()).distinct().toList();
            fromYear = Math.max(0, fromYear);
            toYear = Math.max(0, toYear);
            if (fromYear > 0 && toYear > 0 && fromYear > toYear) {
                int swap = fromYear;
                fromYear = toYear;
                toYear = swap;
            }
        }

        public boolean isEmpty() {
            return tags.isEmpty() && artists.isEmpty() && !hasEra();
        }

        public boolean hasEra() {
            return fromYear > 0 || toYear > 0;
        }

        /** Whether a track from {@code year} fits the era; unknown years (0) always fit. */
        public boolean fitsEra(int year) {
            return year <= 0 || ((fromYear == 0 || year >= fromYear) && (toYear == 0 || year <= toYear));
        }
    }
}
