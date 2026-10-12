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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps artist profiles in the local store fresh.
 *
 * <p>{@link #enrich()} runs off the playback path over all library artists. {@link #ensure}
 * looks up seed artists that aren't in the library (music played straight from a streaming
 * service) on demand; callers bound how long they wait for it. Mixes only ever read the local
 * store, so they keep working when the online source is unreachable.
 */
public final class ArtistEnricher {

    private static final Logger LOG = System.getLogger(ArtistEnricher.class.getName());

    /** Give up on a run after this many failures in a row (no network, bad API key, ...). */
    static final int MAX_CONSECUTIVE_FAILURES = 5;

    /** After a failed on-demand lookup, don't try that artist again for this long. */
    static final Duration RETRY_AFTER = Duration.ofMinutes(10);

    public record Result(int fetched, int unknown, int failed, int upToDate, boolean aborted) {
    }

    private final LibraryPort library;
    private final ArtistInfoSource source;
    private final ArtistProfileStore store;
    private final Clock clock;
    private final Duration maxAge;
    private final Map<String, Instant> failedLookups = new ConcurrentHashMap<>();

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
            if (isFresh(artist, now)) {
                upToDate++;
                continue;
            }
            try {
                if (fetchAndSave(artist, now)) {
                    fetched++;
                } else {
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

    /**
     * Makes sure the given artists have profiles, fetching those that are missing or stale.
     * Artists whose lookup failed recently are skipped. Blocks while fetching; never throws.
     */
    public synchronized void ensure(Collection<String> artistNames) {
        Map<String, String> artists = new LinkedHashMap<>();
        artistNames.forEach(name -> {
            String key = ArtistNames.key(name);
            if (!key.isEmpty()) {
                artists.putIfAbsent(key, name);
            }
        });
        for (Map.Entry<String, String> artist : artists.entrySet()) {
            Instant now = clock.instant();
            if (isFresh(artist.getValue(), now)) {
                continue;
            }
            Instant failedAt = failedLookups.get(artist.getKey());
            if (failedAt != null && failedAt.plus(RETRY_AFTER).isAfter(now)) {
                continue;
            }
            try {
                fetchAndSave(artist.getValue(), now);
                failedLookups.remove(artist.getKey());
            } catch (RuntimeException e) {
                failedLookups.put(artist.getKey(), now);
                LOG.log(Level.WARNING, "Could not fetch artist info for " + artist.getValue(), e);
            }
        }
    }

    private boolean isFresh(String artist, Instant now) {
        return store.find(artist).map(p -> p.fetchedAt().plus(maxAge).isAfter(now)).orElse(false);
    }

    /** @return true if the source knew the artist */
    private boolean fetchAndSave(String artist, Instant now) {
        Optional<ArtistProfile> profile = source.fetch(artist);
        if (profile.isPresent()) {
            // Keep the caller's spelling: the source may autocorrect the name.
            ArtistProfile p = profile.get();
            store.save(new ArtistProfile(artist, p.tags(), p.similar(), p.fetchedAt()));
            return true;
        }
        store.save(ArtistProfile.unknown(artist, now));
        return false;
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
