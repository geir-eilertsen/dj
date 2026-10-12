package dj.domain;

import dj.domain.model.ArtistNames;
import dj.domain.model.ArtistProfile;
import dj.domain.model.SongTitles;
import dj.domain.model.Track;
import dj.domain.port.ArtistProfileStore;
import dj.domain.port.CatalogPort;
import dj.domain.port.DiscoveryStore;
import dj.domain.port.LibraryPort;
import dj.domain.port.TopTracksSource;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds playable music outside the library, ahead of time.
 *
 * <p>"Own" artists are those in the library plus those the user plays straight from a
 * streaming service. For each, it looks up their most popular songs that aren't in the library
 * ("more by your artists"), and the most popular songs of their closest similar artists that
 * aren't in the library at all ("similar artists you lack"). Songs are resolved to playable
 * tracks through the catalogue and stored locally, so mixes never wait for any of this.
 */
public final class Discoverer {

    private static final Logger LOG = System.getLogger(Discoverer.class.getName());

    static final int TOP_TRACKS_OWN = 5;
    static final int TOP_TRACKS_SIMILAR = 3;
    static final int SIMILAR_PER_ARTIST = 3;
    static final double MIN_SIMILAR_MATCH = 0.3;
    static final int MAX_CONSECUTIVE_FAILURES = 5;
    /** How many top songs to check against the library when guarding against namesakes. */
    static final int NAMESAKE_CHECK_TRACKS = 20;
    /** After a failed lookup of an artist, don't try again for this long. */
    static final Duration RETRY_AFTER = Duration.ofMinutes(10);

    public record Result(int artists, int tracks, int failed, boolean aborted) {
    }

    private record Target(String name, int tracks) {
    }

    private record Library(Set<String> songs, Map<String, Integer> songsPerArtist) {
    }

    private final LibraryPort library;
    private final ArtistProfileStore profiles;
    private final ArtistEnricher enricher;
    private final TopTracksSource topTracks;
    private final CatalogPort catalog;
    private final DiscoveryStore store;
    private final Clock clock;
    private final Duration maxAge;
    private final Map<String, Instant> failedArtists = new ConcurrentHashMap<>();

    public Discoverer(LibraryPort library, ArtistProfileStore profiles, ArtistEnricher enricher,
                      TopTracksSource topTracks, CatalogPort catalog, DiscoveryStore store,
                      Clock clock, Duration maxAge) {
        this.library = Objects.requireNonNull(library);
        this.profiles = Objects.requireNonNull(profiles);
        this.enricher = Objects.requireNonNull(enricher);
        this.topTracks = Objects.requireNonNull(topTracks);
        this.catalog = Objects.requireNonNull(catalog);
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
        this.maxAge = Objects.requireNonNull(maxAge);
    }

    /** Background job over all library and listened artists. */
    public synchronized Result discoverAll() {
        List<String> own = new ArrayList<>();
        library.allTracks().forEach(t -> own.add(t.artist()));
        own.addAll(store.listenedArtists());
        Result result = discover(own);
        LOG.log(Level.INFO, "Discovery done: {0}", result);
        return result;
    }

    /**
     * Called with the artists of queued tracks that aren't in the library: remembers them as
     * listened and discovers music around them. Slow; run it off the mix path.
     */
    public synchronized Result discoverAround(Collection<String> listenedArtists) {
        Instant now = clock.instant();
        List<String> artists = listenedArtists.stream().filter(a -> !ArtistNames.key(a).isEmpty()).distinct().toList();
        artists.forEach(a -> store.rememberListened(a, now));
        return discover(artists);
    }

    private Result discover(List<String> ownArtists) {
        Set<String> librarySongs = new HashSet<>();
        Map<String, Integer> songsPerArtist = new HashMap<>();
        for (Track track : library.allTracks()) {
            if (librarySongs.add(songKey(track.artist(), track.title()))) {
                songsPerArtist.merge(ArtistNames.key(track.artist()), 1, Integer::sum);
            }
        }
        Set<String> libraryArtists = songsPerArtist.keySet();
        Library lib = new Library(librarySongs, songsPerArtist);

        Map<String, Target> targets = new LinkedHashMap<>();
        Map<String, String> own = new LinkedHashMap<>();
        ownArtists.forEach(a -> {
            String key = ArtistNames.key(a);
            if (!key.isEmpty()) {
                own.putIfAbsent(key, a);
            }
        });
        own.forEach((key, name) -> targets.put(key, new Target(name, TOP_TRACKS_OWN)));

        enricher.ensure(own.values());
        List<String> similarArtists = new ArrayList<>();
        for (String artist : own.values()) {
            profiles.find(artist).stream()
                    .flatMap(p -> p.similar().stream())
                    .filter(s -> s.match() >= MIN_SIMILAR_MATCH)
                    .filter(s -> !libraryArtists.contains(ArtistNames.key(s.name())))
                    .sorted(Comparator.comparingDouble(ArtistProfile.SimilarArtist::match).reversed())
                    .limit(SIMILAR_PER_ARTIST)
                    .forEach(s -> {
                        if (targets.putIfAbsent(ArtistNames.key(s.name()), new Target(s.name(), TOP_TRACKS_SIMILAR)) == null) {
                            similarArtists.add(s.name());
                        }
                    });
        }
        // Tags for the new artists, so they can be scored on style too.
        enricher.ensure(similarArtists);

        int artists = 0;
        int tracks = 0;
        int failed = 0;
        int consecutiveFailures = 0;
        for (Map.Entry<String, Target> entry : targets.entrySet()) {
            Instant now = clock.instant();
            Target target = entry.getValue();
            if (isFresh(target.name(), now) || recentlyFailed(entry.getKey(), now)) {
                continue;
            }
            try {
                List<Track> found = resolve(target, lib);
                store.save(target.name(), found, now);
                failedArtists.remove(entry.getKey());
                artists++;
                tracks += found.size();
                consecutiveFailures = 0;
            } catch (RuntimeException e) {
                failed++;
                consecutiveFailures++;
                failedArtists.put(entry.getKey(), now);
                LOG.log(Level.WARNING, "Could not discover music by " + target.name(), e);
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    LOG.log(Level.WARNING, "Too many failures in a row; stopping this discovery run");
                    return new Result(artists, tracks, failed, true);
                }
            }
        }
        return new Result(artists, tracks, failed, false);
    }

    /**
     * The artist's popular songs that aren't in the library, as playable tracks.
     *
     * <p>Online sources merge artists that share a name ("TNT" the Norwegian rock band and
     * "TNT" the dance act). If the library has at least two songs by the artist and none of them
     * is among the source's top songs, the source probably means someone else: nothing is found.
     */
    private List<Track> resolve(Target target, Library lib) {
        String artistKey = ArtistNames.key(target.name());
        int inLibrary = lib.songsPerArtist().getOrDefault(artistKey, 0);
        List<String> titles = topTracks.topTracks(target.name(),
                Math.max(target.tracks() * 3, inLibrary >= 2 ? NAMESAKE_CHECK_TRACKS : 0));
        if (inLibrary >= 2 && titles.stream().noneMatch(t -> lib.songs().contains(songKey(target.name(), t)))) {
            LOG.log(Level.INFO, "Skipping {0}: none of its {1} library songs are among the top songs online, "
                    + "so that is probably a different artist with the same name", target.name(), inLibrary);
            return List.of();
        }

        List<Track> found = new ArrayList<>();
        Set<String> songs = new HashSet<>();
        for (String title : titles) {
            if (found.size() == target.tracks()) {
                break;
            }
            String song = songKey(target.name(), title);
            if (lib.songs().contains(song) || !songs.add(song)) {
                continue;
            }
            // Ask the catalogue only for songs the library lacks.
            catalog.find(target.name(), title).ifPresent(found::add);
        }
        return found;
    }

    private boolean isFresh(String artist, Instant now) {
        return store.discoveredAt(artist).map(at -> at.plus(maxAge).isAfter(now)).orElse(false);
    }

    private boolean recentlyFailed(String artistKey, Instant now) {
        Instant failedAt = failedArtists.get(artistKey);
        return failedAt != null && failedAt.plus(RETRY_AFTER).isAfter(now);
    }

    private static String songKey(String artist, String title) {
        return ArtistNames.key(artist) + "|" + SongTitles.key(title);
    }
}
