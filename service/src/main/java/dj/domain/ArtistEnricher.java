package dj.domain;

import dj.domain.model.ArtistNames;
import dj.domain.model.ArtistProfile;
import dj.domain.model.Track;
import dj.domain.port.ArtistInfoSource;
import dj.domain.port.ArtistProfileStore;
import dj.domain.port.LibraryPort;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Background job: fetches profiles for library artists that have none, or a stale one.
 *
 * <p>Runs off the playback path. Mixes only ever read the local store, so they keep working
 * when the online source is unreachable.
 */
public final class ArtistEnricher {

    private static final Logger LOG = System.getLogger(ArtistEnricher.class.getName());

    /** Give up on a run after this many failures in a row (no network, bad API key, ...). */
    static final int MAX_CONSECUTIVE_FAILURES = 5;

    public record Result(int fetched, int unknown, int failed, int upToDate, boolean aborted) {
    }

    private final LibraryPort library;
    private final ArtistInfoSource source;
    private final ArtistProfileStore store;
    private final Clock clock;
    private final Duration maxAge;

    public ArtistEnricher(LibraryPort library, ArtistInfoSource source, ArtistProfileStore store,
                          Clock clock, Duration maxAge) {
        this.library = Objects.requireNonNull(library);
        this.source = Objects.requireNonNull(source);
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
        this.maxAge = Objects.requireNonNull(maxAge);
    }

    public Result enrich() {
        int fetched = 0;
        int unknown = 0;
        int failed = 0;
        int upToDate = 0;
        int consecutiveFailures = 0;

        for (String artist : libraryArtists().values()) {
            Instant now = clock.instant();
            Optional<ArtistProfile> existing = store.find(artist);
            if (existing.isPresent() && existing.get().fetchedAt().plus(maxAge).isAfter(now)) {
                upToDate++;
                continue;
            }
            try {
                Optional<ArtistProfile> profile = source.fetch(artist);
                if (profile.isPresent()) {
                    // Keep the library's spelling: the source may autocorrect the name.
                    ArtistProfile p = profile.get();
                    store.save(new ArtistProfile(artist, p.tags(), p.similar(), p.fetchedAt()));
                    fetched++;
                } else {
                    store.save(ArtistProfile.unknown(artist, now));
                    unknown++;
                }
                consecutiveFailures = 0;
            } catch (RuntimeException e) {
                failed++;
                consecutiveFailures++;
                LOG.log(Level.WARNING, "Could not fetch artist info for " + artist, e);
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    LOG.log(Level.WARNING, "Too many failures in a row; stopping this enrichment run");
                    return new Result(fetched, unknown, failed, upToDate, true);
                }
            }
        }
        Result result = new Result(fetched, unknown, failed, upToDate, false);
        LOG.log(Level.INFO, "Artist enrichment done: {0}", result);
        return result;
    }

    /** One display name per distinct artist, keyed by normalised name. */
    private Map<String, String> libraryArtists() {
        Map<String, String> artists = new LinkedHashMap<>();
        for (Track track : library.allTracks()) {
            String key = ArtistNames.key(track.artist());
            if (!key.isEmpty()) {
                artists.putIfAbsent(key, track.artist());
            }
        }
        return artists;
    }
}
