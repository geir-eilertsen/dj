package dj.adapter.lastfm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dj.domain.model.ArtistNames;
import dj.domain.model.ArtistProfile;
import dj.domain.port.ArtistInfoSource;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Artist tags and similar artists from the Last.fm API
 * ({@code artist.getSimilar} and {@code artist.getTopTags}).
 *
 * <p>Requests are spaced at least {@code minInterval} apart to stay well inside Last.fm's rate limits.
 */
public class LastFmArtistInfo implements ArtistInfoSource {

    public static final URI DEFAULT_BASE_URL = URI.create("https://ws.audioscrobbler.com/2.0/");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int ERROR_ARTIST_NOT_FOUND = 6;
    private static final int MAX_TAGS = 20;
    private static final int MIN_TAG_COUNT = 5;
    private static final int MAX_SIMILAR = 100;
    /** Tags that say something about listeners, not music. */
    private static final Set<String> IGNORED_TAGS = Set.of(
            "seen live", "favorites", "favourites", "favorite", "favourite", "albums i own",
            "my favorite", "awesome", "love", "beautiful", "under 2000 listeners", "spotify");

    private final HttpClient http;
    private final URI baseUrl;
    private final String apiKey;
    private final Duration minInterval;
    private final Clock clock;
    private long nextRequestAt;

    public LastFmArtistInfo(String apiKey) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                DEFAULT_BASE_URL, apiKey, Duration.ofMillis(250), Clock.systemUTC());
    }

    LastFmArtistInfo(HttpClient http, URI baseUrl, String apiKey, Duration minInterval, Clock clock) {
        this.http = http;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.minInterval = minInterval;
        this.clock = clock;
    }

    @Override
    public Optional<ArtistProfile> fetch(String artistName) {
        Optional<JsonNode> similar = call("artist.getsimilar", artistName, "&limit=" + MAX_SIMILAR);
        if (similar.isEmpty()) {
            return Optional.empty();
        }
        Optional<JsonNode> tags = call("artist.gettoptags", artistName, "");
        if (tags.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ArtistProfile(artistName,
                parseTags(tags.get(), artistName),
                parseSimilar(similar.get()),
                clock.instant()));
    }

    static List<ArtistProfile.Tag> parseTags(JsonNode response, String artistName) {
        String artistKey = ArtistNames.key(artistName);
        List<ArtistProfile.Tag> tags = new ArrayList<>();
        for (JsonNode tag : asArray(response.path("toptags").path("tag"))) {
            String name = tag.path("name").asText("").strip().toLowerCase();
            int count = tag.path("count").asInt(0);
            if (name.isEmpty() || count < MIN_TAG_COUNT || IGNORED_TAGS.contains(name)
                    || ArtistNames.key(name).equals(artistKey)) {
                continue;
            }
            tags.add(new ArtistProfile.Tag(name, Math.min(count, 100) / 100.0));
            if (tags.size() == MAX_TAGS) {
                break;
            }
        }
        return tags;
    }

    static List<ArtistProfile.SimilarArtist> parseSimilar(JsonNode response) {
        List<ArtistProfile.SimilarArtist> similar = new ArrayList<>();
        for (JsonNode artist : asArray(response.path("similarartists").path("artist"))) {
            String name = artist.path("name").asText("");
            if (!name.isBlank()) {
                similar.add(new ArtistProfile.SimilarArtist(name, artist.path("match").asDouble(0)));
            }
        }
        return similar;
    }

    /** Last.fm returns a single object instead of a one-element array. */
    private static List<JsonNode> asArray(JsonNode node) {
        if (node.isArray()) {
            List<JsonNode> items = new ArrayList<>();
            node.forEach(items::add);
            return items;
        }
        return node.isObject() ? List.of(node) : List.of();
    }

    /** @return the response, or empty if Last.fm doesn't know the artist */
    private Optional<JsonNode> call(String method, String artistName, String extraParams) {
        URI uri = URI.create(baseUrl + "?method=" + method
                + "&artist=" + URLEncoder.encode(artistName, StandardCharsets.UTF_8)
                + "&autocorrect=1&format=json&api_key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
                + extraParams);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "dj/0.1 (+https://github.com/geir-eilertsen/dj)")
                .GET()
                .build();
        try {
            pace();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = parse(response.body());
            if (body.has("error")) {
                if (body.path("error").asInt() == ERROR_ARTIST_NOT_FOUND) {
                    return Optional.empty();
                }
                throw new IllegalStateException("Last.fm " + method + " failed: error " + body.path("error").asInt()
                        + " " + body.path("message").asText());
            }
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Last.fm " + method + " returned HTTP " + response.statusCode());
            }
            return Optional.of(body);
        } catch (IOException e) {
            throw new UncheckedIOException("Last.fm " + method + " request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }

    private static JsonNode parse(String body) {
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            return JSON.createObjectNode();
        }
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
