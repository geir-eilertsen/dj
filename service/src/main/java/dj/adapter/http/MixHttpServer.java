package dj.adapter.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dj.domain.MixService;
import dj.domain.model.Mix;
import dj.domain.model.MixRequest;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * HTTP API used by the LMS plugin.
 *
 * <ul>
 *   <li>{@code GET /mix?player=<id>&seed=<id,id,...>&count=<n>}</li>
 *   <li>{@code GET /why?player=<id>&url=<track url>}: why DJ suggested that track to that player</li>
 *   <li>{@code GET /health}</li>
 *   <li>{@code GET /status}: diagnostics from the {@code status} supplier</li>
 * </ul>
 */
public class MixHttpServer {

    private static final Logger LOG = System.getLogger(MixHttpServer.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    static final int DEFAULT_COUNT = 5;

    private final HttpServer server;
    private final MixService mixService;
    private final FeedbackStore feedback;

    public MixHttpServer(MixService mixService, FeedbackStore feedback, int port, Supplier<Map<String, Object>> status)
            throws IOException {
        this.mixService = mixService;
        this.feedback = feedback;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/mix", this::handleMix);
        server.createContext("/why", this::handleWhy);
        server.createContext("/health", exchange -> respond(exchange, 200, Map.of("status", "ok")));
        server.createContext("/status", exchange -> respond(exchange, 200, status.get()));
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
        return new MixRequest(player, seeds, count);
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

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record TrackJson(String id, String url, String title, String artist, String album, String reason) {

        static TrackJson from(Track track, String reason) {
            return new TrackJson(track.id().value(), track.url(), track.title(), track.artist(), track.album(), reason);
        }
    }
}
