package dj.domain;

import dj.domain.model.Track;
import dj.domain.model.TrackId;
import dj.domain.port.CatalogPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static dj.domain.Tracks.track;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscovererTest {

    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");

    private final InMemoryProfileStore profiles = new InMemoryProfileStore()
            .with("Gasolin'", List.of("danish"), "Kim Larsen:0.9", "TV-2:0.6", "Shu-bi-dua:0.5", "Sneakers:0.4",
                    "Bamses Venner:0.2")
            .with("Kim Larsen", List.of("danish"), "Gasolin':0.9", "TV-2:0.8");
    private final InMemoryDiscoveryStore store = new InMemoryDiscoveryStore();
    private final List<String> searched = new ArrayList<>();
    private List<Track> library = List.of(new Track(TrackId.of("1"), "file://1", "This Is My Life", "Gasolin'", "", 1975, 3));

    private final Map<String, List<String>> topTracks = Map.of(
            "Gasolin'", List.of("This Is My Life", "Kvinde Min", "Rabalderstræde", "Langebro", "Masser af Succes",
                    "Det Var En Lørdag Aften", "Bingo"),
            "Kim Larsen", List.of("Om Lidt", "Papirsklip", "Joanna"),
            "TV-2", List.of("Bag Duggede Ruder", "Rejsen til Rom", "De Første Kærester På Månen"),
            "Shu-bi-dua", List.of("Den Røde Tråd"),
            "Sneakers", List.of("Bø' i Rødovre"));

    /** Has everything except songs titled "Bingo". */
    private final CatalogPort tidal = (artist, title) -> {
        searched.add(artist + " - " + title);
        return title.equals("Bingo") ? Optional.empty() : Optional.of(tidalTrack(artist, title));
    };

    @Test
    void findsMoreByLibraryArtistsAndTheirClosestSimilarArtistsNotInTheLibrary() {
        Discoverer.Result result = discoverer(tidal).discoverAll();

        // More by Gasolin': top 5, without the song already in the library.
        assertEquals(List.of("Kvinde Min", "Rabalderstræde", "Langebro", "Masser af Succes", "Det Var En Lørdag Aften"),
                titles(store.tracksBy("Gasolin'")));
        // Top 3 similar artists (match >= 0.3), 3 songs each at most.
        assertEquals(List.of("Om Lidt", "Papirsklip", "Joanna"), titles(store.tracksBy("Kim Larsen")));
        assertEquals(3, store.tracksBy("TV-2").size());
        assertEquals(1, store.tracksBy("Shu-bi-dua").size());
        assertTrue(store.tracksBy("Sneakers").isEmpty(), "only the 3 closest similar artists");
        assertEquals(4, result.artists());
        assertTrue(searched.stream().noneMatch(s -> s.contains("This Is My Life")), "library songs aren't searched");
    }

    @Test
    void skipsSongsTheCatalogueDoesNotHave() {
        library = List.of(new Track(TrackId.of("1"), "file://1", "Other", "Gasolin'", "", 0, 0));
        CatalogPort withoutKvindeMin = (artist, title) ->
                title.equals("Kvinde Min") ? Optional.empty() : tidal.find(artist, title);

        discoverer(withoutKvindeMin).discoverAll();

        assertEquals(List.of("This Is My Life", "Rabalderstræde", "Langebro", "Masser af Succes", "Det Var En Lørdag Aften"),
                titles(store.tracksBy("Gasolin'")));
    }

    @Test
    void leavesRecentlyDiscoveredArtistsAlone() {
        store.save("TV-2", List.of(), NOW.minus(Duration.ofDays(1)));

        discoverer(tidal).discoverAll();

        assertTrue(searched.stream().noneMatch(s -> s.startsWith("TV-2")));
    }

    @Test
    void discoversAroundArtistsPlayedFromAStreamingService() {
        library = List.of();

        Discoverer.Result result = discoverer(tidal).discoverAround(List.of("Kim Larsen", "Kim Larsen"));

        assertEquals(List.of("Kim Larsen"), store.listenedArtists());
        assertEquals(Set.of("Om Lidt", "Papirsklip", "Joanna"), Set.copyOf(titles(store.tracksBy("Kim Larsen"))));
        assertEquals(3, store.tracksBy("TV-2").size());
        assertEquals(3, store.tracksBy("Gasolin'").size(), "Gasolin' isn't in this library, so it's a similar artist");
        assertEquals(3, result.artists());
    }

    @Test
    void stopsAfterTooManyFailuresInARowAndBacksOff() {
        library = java.util.stream.IntStream.rangeClosed(1, 6).mapToObj(i -> track(String.valueOf(i), "Band " + i)).toList();
        CatalogPort down = (artist, title) -> {
            searched.add(artist);
            throw new IllegalStateException("TIDAL unavailable");
        };
        Discoverer discoverer = discoverer(down);

        assertTrue(discoverer.discoverAll().aborted());
        assertEquals(Discoverer.MAX_CONSECUTIVE_FAILURES, searched.size());

        discoverer.discoverAll();
        assertEquals(List.of("Band 6"), searched.subList(Discoverer.MAX_CONSECUTIVE_FAILURES, searched.size()),
                "only the artist not tried yet; failed ones back off");
    }

    private Discoverer discoverer(CatalogPort catalog) {
        ArtistEnricher enricher = new ArtistEnricher(() -> library, name -> Optional.empty(), profiles,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofDays(3650));
        return new Discoverer(() -> library, profiles, enricher,
                (artist, limit) -> topTracks.getOrDefault(artist, List.of("Some Song")).stream().limit(limit).toList(),
                catalog, store, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofDays(30));
    }

    private static Track tidalTrack(String artist, String title) {
        String url = "tidal://" + Math.abs((artist + title).hashCode()) + ".flc";
        return new Track(TrackId.of(url), url, title, artist, "", 0, 0);
    }

    private static List<String> titles(List<Track> tracks) {
        return tracks.stream().map(Track::title).toList();
    }
}
