package dj.adapter.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;

/** GETs pages of a site that has no API: one request per {@code minInterval}, saying who's asking. */
public class PoliteHttp {

    public static final String USER_AGENT = "dj (+https://github.com/geir-eilertsen/dj)";

    private final HttpClient http;
    private final String site;
    private final Duration minInterval;
    private final Clock clock;
    private long nextRequestAt;

    /** @param site the site's name, for error messages */
    public PoliteHttp(HttpClient http, String site, Duration minInterval, Clock clock) {
        this.http = http;
        this.site = site;
        this.minInterval = minInterval;
        this.clock = clock;
    }

    public static HttpClient defaultClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** @throws RuntimeException if the request fails or the answer isn't HTTP 200 */
    public synchronized String get(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        try {
            pace();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(site + " " + uri.getPath() + " returned HTTP " + response.statusCode());
            }
            return response.body();
        } catch (IOException e) {
            throw new UncheckedIOException(site + " request for " + uri.getPath() + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }

    private void pace() throws InterruptedException {
        long now = clock.millis();
        if (now < nextRequestAt) {
            Thread.sleep(nextRequestAt - now);
            now = nextRequestAt;
        }
        nextRequestAt = now + minInterval.toMillis();
    }
}
