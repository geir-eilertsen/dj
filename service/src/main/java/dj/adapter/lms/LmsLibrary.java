package dj.adapter.lms;

import com.fasterxml.jackson.databind.JsonNode;
import dj.domain.model.Track;
import dj.domain.model.TrackId;
import dj.domain.port.LibraryPort;
import dj.domain.port.LibraryUnavailableException;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Library backed by the LMS JSON-RPC API.
 *
 * <p>LMS's {@code titles} query has no random sort, so the whole track list is loaded into a
 * snapshot (a few hundred bytes per track) and sampled locally. The snapshot is refreshed after
 * {@code ttl}; if a refresh fails, the previous snapshot keeps being used.
 */
public class LmsLibrary implements LibraryPort {

    private static final Logger LOG = System.getLogger(LmsLibrary.class.getName());
    private static final int PAGE_SIZE = 500;

    private final LmsJsonRpcClient lms;
    private final Duration ttl;
    private final Clock clock;
    private final Random random;

    private List<Track> snapshot;
    private Instant loadedAt = Instant.MIN;

    public LmsLibrary(LmsJsonRpcClient lms, Duration ttl) {
        this(lms, ttl, Clock.systemUTC(), new Random());
    }

    LmsLibrary(LmsJsonRpcClient lms, Duration ttl, Clock clock, Random random) {
        this.lms = lms;
        this.ttl = ttl;
        this.clock = clock;
        this.random = random;
    }

    @Override
    public List<Track> randomTracks(int count, Set<TrackId> exclude) {
        List<Track> tracks = new ArrayList<>(snapshot());
        tracks.removeIf(track -> exclude.contains(track.id()));
        Collections.shuffle(tracks, random);
        return List.copyOf(tracks.subList(0, Math.min(count, tracks.size())));
    }

    /** Loads the snapshot now, e.g. at startup so the first mix is fast. */
    public void refresh() {
        snapshot();
    }

    private synchronized List<Track> snapshot() {
        Instant now = clock.instant();
        if (snapshot != null && now.isBefore(loadedAt.plus(ttl))) {
            return snapshot;
        }
        try {
            snapshot = load();
            loadedAt = now;
            LOG.log(Level.INFO, "Loaded {0,number,#} tracks from LMS", snapshot.size());
            return snapshot;
        } catch (IOException e) {
            return staleOrFail(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return staleOrFail(e);
        }
    }

    private List<Track> staleOrFail(Exception e) {
        if (snapshot == null) {
            throw new LibraryUnavailableException("Could not load the library from LMS", e);
        }
        LOG.log(Level.WARNING, "Could not refresh the library from LMS; using the previous snapshot", e);
        return snapshot;
    }

    private List<Track> load() throws IOException, InterruptedException {
        List<Track> tracks = new ArrayList<>();
        int offset = 0;
        int total;
        do {
            JsonNode result = lms.request("", List.of("titles", offset, PAGE_SIZE, "tags:alu"));
            total = result.path("count").asInt(0);
            JsonNode page = result.path("titles_loop");
            for (JsonNode item : page) {
                toTrack(item).ifPresent(tracks::add);
            }
            if (page.isEmpty()) {
                break;
            }
            offset += page.size();
        } while (offset < total);
        return List.copyOf(tracks);
    }

    static Optional<Track> toTrack(JsonNode item) {
        String id = item.path("id").asText("");
        String url = item.path("url").asText("");
        if (id.isBlank() || url.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new Track(
                TrackId.of(id),
                url,
                item.path("title").asText(""),
                item.path("artist").asText(""),
                item.path("album").asText("")));
    }
}
