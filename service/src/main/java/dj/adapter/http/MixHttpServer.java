package dj.adapter.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dj.domain.ConcertFinder;
import dj.domain.ConcertProviders;
import dj.domain.MixService;
import dj.domain.MoodService;
import dj.domain.PhotoMoods;
import dj.domain.model.ArtistConcerts;
import dj.domain.model.Concert;
import dj.domain.model.Mix;
import dj.domain.model.MixRequest;
import dj.domain.model.Mood;
import dj.domain.model.Photo;
import dj.domain.model.Track;
import dj.domain.model.TrackId;
import dj.domain.port.FeedbackStore;
import dj.domain.port.LibraryUnavailableException;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * HTTP API used by the LMS plugin.
 *
 * <ul>
 *   <li>{@code GET /mix?player=<id>&seed=<id,id,...>&count=<n>&fresh=1}: {@code fresh} starts a new queue</li>
 *   <li>{@code GET /mood?player=<id>}: the player's mood and recent moods</li>
 *   <li>{@code POST /mood} with {@code player} and {@code text} (query or form body): sets the mood</li>
 *   <li>{@code POST /mood/clear?player=<id>}</li>
 *   <li>{@code POST /mood/forget} with {@code player} and {@code text}: takes it off the recent moods</li>
 *   <li>{@code POST /mood/photo} with JSON {@code {"player", "photos": ["data:image/jpeg;base64,...", ...]}}:
 *       sets the mood the photos show; answers {@code {"mood", "why"}}</li>
 *   <li>{@code GET /why?player=<id>&url=<track url>}: why DJ suggested that track to that player</li>
 *   <li>{@code GET /concerts?artist=<name>}: that artist's upcoming concerts in the chosen countries (looked up if need be)</li>
 *   <li>{@code GET /concerts}: every known artist with upcoming concerts in the chosen countries</li>
 *   <li>{@code GET /concerts/providers}: every concert provider and whether it's on</li>
 *   <li>{@code POST /concerts/providers} with {@code enabled=<id,id,...>} (query or form body): turns
 *       those on and the others off</li>
 *   <li>{@code GET /health}</li>
 *   <li>{@code GET /status}: diagnostics from the {@code status} supplier</li>
 * </ul>
 */
public class MixHttpServer {

    private static final Logger LOG = System.getLogger(MixHttpServer.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    static final int DEFAULT_COUNT = 5;
    private static final int MAX_FORM_BYTES = 16 * 1024;
    /** Claude takes pictures of up to 5 MB; the plugin's page sends them scaled down. */
    private static final int MAX_PHOTO_BYTES = 5 * 1024 * 1024;
    private static final int MAX_PHOTO_REQUEST_BYTES = PhotoMoods.MAX_PHOTOS * MAX_PHOTO_BYTES * 4 / 3 + 16 * 1024;

    private final HttpServer server;
    private final MixService mixService;
    private final FeedbackStore feedback;
    private final MoodService moods;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile ConcertFinder concerts;
    private volatile Duration concertLookupTimeout;
    private volatile Runnable onProvidersChanged;
    private volatile PhotoMoods photoMoods;

    public MixHttpServer(MixService mixService, FeedbackStore feedback, MoodService moods, int port,
                         Supplier<Map<String, Object>> status) throws IOException {
        this.mixService = mixService;
        this.feedback = feedback;
        this.moods = moods;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(executor);
        server.createContext("/mix", this::handleMix);
        server.createContext("/why", this::handleWhy);
        server.createContext("/mood", this::handleMood);
        server.createContext("/mood/clear", this::handleClearMood);
        server.createContext("/mood/forget", this::handleForgetMood);
        server.createContext("/mood/photo", this::handlePhotoMood);
        server.createContext("/concerts", this::handleConcerts);
        server.createContext("/concerts/providers", this::handleConcertProviders);
        server.createContext("/health", exchange -> respond(exchange, 200, Map.of("status", "ok")));
        server.createContext("/status", exchange -> respond(exchange, 200, status.get()));
    }

    /**
     * Answers {@code /concerts}; without this it says concerts aren't set up.
     *
     * @param onProvidersChanged run after the listener changes which providers are on
     */
    public void enableConcerts(ConcertFinder finder, Duration lookupTimeout, Runnable onProvidersChanged) {
        this.concertLookupTimeout = lookupTimeout;
        this.onProvidersChanged = onProvidersChanged;
        this.concerts = finder;
    }

    /** Answers {@code /mood/photo}; without this, or without a photo reader, it says photos can't be read. */
    public void enablePhotoMoods(PhotoMoods photoMoods) {
        this.photoMoods = photoMoods;
    }

    public void start() {
        server.start();
        LOG.log(Level.INFO, "Listening on port {0,number,#}", server.getAddress().getPort());
    }

    public void stop() {
        server.stop(1);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handleMix(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, Map.of("error", "GET only"));
            return;
        }
        MixRequest request;
        try {
            request = parse(exchange.getRequestURI().getRawQuery());
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, Map.of("error", e.getMessage()));
            return;
        }

        try {
            long start = System.nanoTime();
            Mix mix = mixService.mix(request);
            LOG.log(Level.INFO, "Mix for {0}: {1} tracks ({2}) in {3,number,#} ms", request.playerId(),
                    mix.tracks().size(), mix.source(), (System.nanoTime() - start) / 1_000_000);
            respond(exchange, 200, MixResponse.from(mix));
        } catch (LibraryUnavailableException e) {
            LOG.log(Level.ERROR, "Library unavailable", e);
            respond(exchange, 503, Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "Mix failed", e);
            respond(exchange, 500, Map.of("error", "internal error"));
        }
    }

    private void handleWhy(HttpExchange exchange) throws IOException {
        Map<String, List<String>> params = queryParams(exchange.getRequestURI().getRawQuery());
        String player = first(params, "player");
        String url = first(params, "url");
        if (player == null || url == null) {
            respond(exchange, 400, Map.of("error", "need 'player' and 'url'"));
            return;
        }
        try {
            Optional<String> reason = feedback.reasonFor(player, url);
            if (reason.isPresent()) {
                respond(exchange, 200, Map.of("reason", reason.get()));
            } else {
                respond(exchange, 404, Map.of("error", "no reason known for that track"));
            }
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "Could not look up reason", e);
            respond(exchange, 500, Map.of("error", "internal error"));
        }
    }

    private void handleConcerts(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, Map.of("error", "GET only"));
            return;
        }
        ConcertFinder finder = concerts;
        if (finder == null) {
            respond(exchange, 404, Map.of("error", "concerts are not set up"));
            return;
        }
        String artist = first(queryParams(exchange.getRequestURI().getRawQuery()), "artist");
        if (artist == null) {
            respond(exchange, 200, Map.of("artists", finder.upcoming().stream().map(ArtistConcertsJson::from).toList()));
            return;
        }
        // A slow lookup goes on in the background and is cached for the next request
        CompletableFuture<List<Concert>> lookup = CompletableFuture.supplyAsync(() -> finder.forArtist(artist), executor);
        try {
            List<Concert> found = lookup.get(concertLookupTimeout.toMillis(), TimeUnit.MILLISECONDS);
            respond(exchange, 200, new ArtistConcertsJson(artist, found.stream().map(ConcertJson::from).toList()));
        } catch (TimeoutException e) {
            respond(exchange, 504, Map.of("error", "concert lookup is taking too long"));
        } catch (ExecutionException e) {
            LOG.log(Level.WARNING, "Concert lookup for " + artist + " failed", e.getCause());
            respond(exchange, 502, Map.of("error", "concert lookup failed"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            respond(exchange, 503, Map.of("error", "interrupted"));
        }
    }

    private void handleConcertProviders(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equals(method) && !"POST".equals(method)) {
            respond(exchange, 405, Map.of("error", "GET or POST only"));
            return;
        }
        ConcertFinder finder = concerts;
        if (finder == null) {
            respond(exchange, 404, Map.of("error", "concerts are not set up"));
            return;
        }
        ConcertProviders providers = finder.providers();
        try {
            if ("POST".equals(method)) {
                List<String> enabled = params(exchange).get("enabled");
                if (enabled == null) {
                    respond(exchange, 400, Map.of("error", "missing 'enabled' (comma-separated provider ids, may be empty)"));
                    return;
                }
                Set<String> ids = new LinkedHashSet<>();
                for (String value : enabled) {
                    Arrays.stream(value.split(",")).map(String::strip).filter(id -> !id.isEmpty()).forEach(ids::add);
                }
                providers.choose(ids);
                LOG.log(Level.INFO, "Concert providers on: {0}", ids);
                Runnable changed = onProvidersChanged;
                if (changed != null) {
                    changed.run();
                }
            }
            respond(exchange, 200, Map.of("providers", providers.all().stream().map(ProviderJson::from).toList()));
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "Concert provider request failed", e);
            respond(exchange, 500, Map.of("error", "internal error"));
        }
    }

    private void handleMood(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equals(method) && !"POST".equals(method)) {
            respond(exchange, 405, Map.of("error", "GET or POST only"));
            return;
        }
        Map<String, List<String>> params = params(exchange);
        String player = first(params, "player");
        if (player == null || player.isBlank()) {
            respond(exchange, 400, Map.of("error", "missing 'player'"));
            return;
        }
        try {
            if ("POST".equals(method)) {
                Mood mood = moods.set(player, Optional.ofNullable(first(params, "text")).orElse(""));
                respond(exchange, 200, MoodJson.from(mood));
                return;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("current", moods.current(player).map(MoodJson::from).orElse(null));
            body.put("recent", moods.recent(player).stream().map(Mood::text).toList());
            respond(exchange, 200, body);
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "Mood request failed", e);
            respond(exchange, 500, Map.of("error", "internal error"));
        }
    }

    private void handleClearMood(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, Map.of("error", "POST only"));
            return;
        }
        String player = first(params(exchange), "player");
        if (player == null || player.isBlank()) {
            respond(exchange, 400, Map.of("error", "missing 'player'"));
            return;
        }
        try {
            moods.clear(player);
            respond(exchange, 200, Map.of("cleared", true));
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "Could not clear mood", e);
            respond(exchange, 500, Map.of("error", "internal error"));
        }
    }

    private void handleForgetMood(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, Map.of("error", "POST only"));
            return;
        }
        Map<String, List<String>> params = params(exchange);
        String player = first(params, "player");
        if (player == null || player.isBlank()) {
            respond(exchange, 400, Map.of("error", "missing 'player'"));
            return;
        }
        try {
            moods.forget(player, first(params, "text"));
            respond(exchange, 200, Map.of("recent", moods.recent(player).stream().map(Mood::text).toList()));
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "Could not forget mood", e);
            respond(exchange, 500, Map.of("error", "internal error"));
        }
    }

    private void handlePhotoMood(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, Map.of("error", "POST only"));
            return;
        }
        PhotoMoods reader = photoMoods;
        if (reader == null || !reader.available()) {
            respond(exchange, 503, Map.of("error", "reading photos needs ANTHROPIC_API_KEY"));
            return;
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_PHOTO_REQUEST_BYTES + 1);
        if (body.length > MAX_PHOTO_REQUEST_BYTES) {
            respond(exchange, 413, Map.of("error", "photos too large"));
            return;
        }
        String player;
        List<Photo> photos = new ArrayList<>();
        try {
            JsonNode request = JSON.readTree(body);
            player = request.path("player").asText("");
            for (JsonNode photo : request.path("photos")) {
                photos.add(photo(photo.asText("")));
            }
        } catch (IOException | IllegalArgumentException e) {
            respond(exchange, 400, Map.of("error", "expected {\"player\", \"photos\": [data URLs]}: " + e.getMessage()));
            return;
        }
        if (player.isBlank()) {
            respond(exchange, 400, Map.of("error", "missing 'player'"));
            return;
        }
        try {
            PhotoMoods.Result result = reader.fromPhotos(player, photos);
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("mood", MoodJson.from(result.mood()));
            answer.put("why", result.why());
            respond(exchange, 200, answer);
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            // Never log the request: it holds the listener's photos
            LOG.log(Level.WARNING, "Could not read the mood from photos: {0}", e.toString());
            respond(exchange, 502, Map.of("error", "could not read the photos"));
        }
    }

    /** A data URL ({@code data:image/jpeg;base64,...}), or bare base64 taken as a JPEG. */
    static Photo photo(String dataUrl) {
        String type = "image/jpeg";
        String data = dataUrl;
        if (dataUrl.startsWith("data:")) {
            int comma = dataUrl.indexOf(',');
            if (comma < 0 || !dataUrl.substring(0, comma).endsWith(";base64")) {
                throw new IllegalArgumentException("photos must be base64 data URLs");
            }
            type = dataUrl.substring(5, comma - ";base64".length());
            data = dataUrl.substring(comma + 1);
        }
        byte[] bytes = Base64.getMimeDecoder().decode(data);
        if (bytes.length > MAX_PHOTO_BYTES) {
            throw new IllegalArgumentException("a photo is larger than 5 MB");
        }
        return new Photo(bytes, type);
    }

    /** Query parameters, plus those of a form-encoded body. */
    private static Map<String, List<String>> params(HttpExchange exchange) throws IOException {
        Map<String, List<String>> params = queryParams(exchange.getRequestURI().getRawQuery());
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        if (type != null && type.startsWith("application/x-www-form-urlencoded")) {
            String body = new String(exchange.getRequestBody().readNBytes(MAX_FORM_BYTES), StandardCharsets.UTF_8);
            queryParams(body).forEach((k, v) -> params.computeIfAbsent(k, x -> new ArrayList<>()).addAll(v));
        }
        return params;
    }

    static MixRequest parse(String rawQuery) {
        Map<String, List<String>> params = queryParams(rawQuery);

        String player = first(params, "player");
        if (player == null || player.isBlank()) {
            throw new IllegalArgumentException("missing 'player'");
        }

        List<TrackId> seeds = new ArrayList<>();
        for (String value : params.getOrDefault("seed", List.of())) {
            Arrays.stream(value.split(","))
                    .map(String::strip)
                    .filter(s -> !s.isEmpty())
                    .map(TrackId::of)
                    .forEach(seeds::add);
        }

        int count = DEFAULT_COUNT;
        String countParam = first(params, "count");
        if (countParam != null) {
            try {
                count = Integer.parseInt(countParam.strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'count' must be a number");
            }
        }
        String fresh = first(params, "fresh");
        return new MixRequest(player, seeds, count, "1".equals(fresh) || "true".equalsIgnoreCase(fresh));
    }

    private static Map<String, List<String>> queryParams(String rawQuery) {
        Map<String, List<String>> params = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = decode(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
            params.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }
        return params;
    }

    private static String first(Map<String, List<String>> params, String key) {
        List<String> values = params.get(key);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    record MixResponse(String source, List<TrackJson> tracks) {

        static MixResponse from(Mix mix) {
            return new MixResponse(mix.source().name().toLowerCase(),
                    mix.tracks().stream().map(t -> TrackJson.from(t, mix.reasonFor(t.id()).orElse(null))).toList());
        }
    }

    record MoodJson(String text, List<String> tags, List<String> artists, int fromYear, int toYear, String setAt,
                    String expiresAt) {

        static MoodJson from(Mood mood) {
            Mood.Style s = mood.style();
            return new MoodJson(mood.text(), s.tags(), s.artists(), s.fromYear(), s.toYear(), mood.setAt().toString(),
                    mood.setAt().plus(MoodService.LASTS).toString());
        }
    }

    record ArtistConcertsJson(String artist, List<ConcertJson> concerts) {

        static ArtistConcertsJson from(ArtistConcerts a) {
            return new ArtistConcertsJson(a.artist(), a.concerts().stream().map(ConcertJson::from).toList());
        }
    }

    /** {@code unavailable}: why it can't be asked, if it can't. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ProviderJson(String id, String name, String coverage, boolean enabled, String unavailable) {

        static ProviderJson from(ConcertProviders.Status s) {
            return new ProviderJson(s.provider().id(), s.provider().name(), s.provider().coverage(), s.enabled(),
                    s.available() ? null : s.unavailable());
        }
    }

    /** Dates and times are the venue's local ones: {@code 2026-11-14}, {@code 20:00}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ConcertJson(String date, String time, String title, String venue, String city, String country, String url) {

        static ConcertJson from(Concert c) {
            return new ConcertJson(c.date().toString(), c.time() == null ? null : c.time().toString(), c.title(),
                    c.venue(), c.city(), c.countryCode(), c.url().isEmpty() ? null : c.url());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record TrackJson(String id, String url, String title, String artist, String album, String reason) {

        static TrackJson from(Track track, String reason) {
            return new TrackJson(track.id().value(), track.url(), track.title(), track.artist(), track.album(), reason);
        }
    }
}
