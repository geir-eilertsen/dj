package dj.domain.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/**
 * An upcoming concert.
 *
 * @param artist      the artist as DJ knows them (the name that was looked up)
 * @param title       the event's own name, often the tour's
 * @param time        local start time, or null if not announced
 * @param countryCode ISO 3166-1 alpha-2, upper case
 * @param url         where to buy tickets, or empty
 */
public record Concert(String artist, String title, LocalDate date, LocalTime time, String venue, String city,
                      String countryCode, String url) {

    public Concert {
        Objects.requireNonNull(date, "date");
        artist = Objects.requireNonNullElse(artist, "");
        title = Objects.requireNonNullElse(title, "");
        venue = Objects.requireNonNullElse(venue, "");
        city = Objects.requireNonNullElse(city, "");
        countryCode = Objects.requireNonNullElse(countryCode, "").toUpperCase();
        url = Objects.requireNonNullElse(url, "");
    }
}
