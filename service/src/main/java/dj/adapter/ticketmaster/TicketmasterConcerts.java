package dj.adapter.ticketmaster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dj.domain.model.ArtistNames;
import dj.domain.model.Concert;
import dj.domain.port.ConcertSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Upcoming concerts from the Ticketmaster Discovery API: finds the artist's attraction by exact
 * name ({@code attractions.json}), then lists its music events ({@code events.json}).
 *
 * <p>Requests are spaced {@code minInterval} apart: the API allows 5 per second and 5000 a day.
 */
public class TicketmasterConcerts implements ConcertSource {

    public static final URI DEFAULT_BASE_URL = URI.create("https://app.ticketmaster.com/discovery/v2/");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_EVENTS = 200;

    private final HttpClient http;
    private final URI baseUrl;
    private final String apiKey;
    private final Duration minInterval;
    private final Clock clock;
    private long nextRequestAt;

    public TicketmasterConcerts(String apiKey) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                DEFAULT_BASE_URL, apiKey, Duration.ofMillis(250), Clock.systemUTC());
    }

    TicketmasterConcerts(HttpClient http, URI baseUrl, String apiKey, Duration minInterval, Clock clock) {
        this.http = http;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.minInterval = minInterval;
        this.clock = clock;
    }

    @Override
    public List<Concert> upcoming(String artistName) {
        JsonNode attractions = call("attractions.json", "keyword=" + encode(artistName)
                + "&classificationName=music&size=20");
        Optional<JsonNode> attraction = bestMatch(attractions, artistName);
        if (attraction.isEmpty() || attraction.get().path("upcomingEvents").path("_total").asInt(0) == 0) {
            return List.of();
        }
        JsonNode events = call("events.json", "attractionId=" + encode(attraction.get().path("id").asText())
                + "&classificationName=music&sort=date,asc&size=" + MAX_EVENTS);
        return parseEvents(events, artistName);
    }

    /**
     * The attraction named exactly like the artist ("Opeth Tribute" is not Opeth). If two acts
     * share the name, the one with the most upcoming events wins.
     */
    static Optional<JsonNode> bestMatch(JsonNode response, String artistName) {
        String key = ArtistNames.key(artistName);
        JsonNode best = null;
        for (JsonNode attraction : response.path("_embedded").path("attractions")) {
            if (!ArtistNames.key(attraction.path("name").asText()).equals(key)) {
                continue;
            }
            if (best == null || attraction.path("upcomingEvents").path("_total").asInt(0)
                    > best.path("upcomingEvents").path("_total").asInt(0)) {
                best = attraction;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Skips cancelled events, ones without a date, and extra listings (VIP packages) of the same show. */
    static List<Concert> parseEvents(JsonNode response, String artistName) {
        List<Concert> concerts = new ArrayList<>();
        Set<String> shows = new HashSet<>();
        for (JsonNode event : response.path("_embedded").path("events")) {
            JsonNode start = event.path("dates").path("start");
            if ("cancelled".equalsIgnoreCase(event.path("dates").path("status").path("code").asText())) {
                continue;
            }
            LocalDate date;
            try {
                date = LocalDate.parse(start.path("localDate").asText());
            } catch (DateTimeParseException e) {
                continue;
            }
            LocalTime time = null;
            if (start.hasNonNull("localTime")) {
                try {
                    time = LocalTime.parse(start.path("localTime").asText());
                } catch (DateTimeParseException e) {
                    // announced without a usable time
                }
            }
            JsonNode venue = event.path("_embedded").path("venues").path(0);
            String venueName = venue.path("name").asText("");
            if (!shows.add(date + "|" + ArtistNames.key(venueName))) {
                continue;
            }
            concerts.add(new Concert(artistName, event.path("name").asText(""), date, time, venueName,
                    venue.path("city").path("name").asText(""), venue.path("country").path("countryCode").asText(""),
                    event.path("url").asText("")));
        }
        return concerts;
    }

    private JsonNode call(String path, String params) {
        URI uri = URI.create(baseUrl + path + "?" + params + "&apikey=" + encode(apiKey));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "dj (+https://github.com/geir-eilertsen/dj)")
                .GET()
                .build();
        try {
            pace();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                // The key is in the URI: never put that in the message
                throw new IllegalStateException("Ticketmaster " + path + " returned HTTP " + response.statusCode());
            }
            return JSON.readTree(response.body());
        } catch (IOException e) {
            throw new UncheckedIOException("Ticketmaster " + path + " request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private synchronized void pace() throws InterruptedException {
        long now = clock.millis();
        if (now < nextRequestAt) {
            Thread.sleep(nextRequestAt - now);
            now = nextRequestAt;
        }
        nextRequestAt = now + minInterval.toMillis();
    }
}
