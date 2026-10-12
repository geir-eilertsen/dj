package dj.domain;

import dj.domain.model.ArtistNames;
import dj.domain.model.Track;
import dj.domain.port.DiscoveryStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Test fake for {@link DiscoveryStore}. */
final class InMemoryDiscoveryStore implements DiscoveryStore {

    private final Map<String, List<Track>> tracks = new LinkedHashMap<>();
    private final Map<String, Instant> discoveredAt = new LinkedHashMap<>();
    private final Map<String, String> listened = new LinkedHashMap<>();

    @Override
    public List<Track> allTracks() {
        List<Track> all = new ArrayList<>();
        tracks.values().forEach(all::addAll);
        return all;
    }

    @Override
    public Optional<Instant> discoveredAt(String artist) {
        return Optional.ofNullable(discoveredAt.get(ArtistNames.key(artist)));
    }

    @Override
    public void save(String artist, List<Track> found, Instant at) {
        tracks.put(ArtistNames.key(artist), List.copyOf(found));
        discoveredAt.put(ArtistNames.key(artist), at);
    }

    @Override
    public void rememberListened(String artist, Instant heardAt) {
        listened.put(ArtistNames.key(artist), artist);
    }

    @Override
    public List<String> listenedArtists() {
        return List.copyOf(listened.values());
    }

    List<Track> tracksBy(String artist) {
        return tracks.getOrDefault(ArtistNames.key(artist), List.of());
    }
}
