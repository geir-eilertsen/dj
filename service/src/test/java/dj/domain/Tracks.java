package dj.domain;

import dj.domain.model.Track;
import dj.domain.model.TrackId;

import java.util.Arrays;
import java.util.List;

/** Test helpers for building tracks and IDs. */
final class Tracks {

    private Tracks() {
    }

    static Track track(String id) {
        return track(id, "Artist " + id);
    }

    static Track track(String id, String artist) {
        return track(id, artist, 0, 0);
    }

    static Track track(String id, String artist, int year, int playCount) {
        return new Track(TrackId.of(id), "file:///music/" + id + ".flac", "Title " + id, artist, "Album " + id,
                year, playCount);
    }

    static List<Track> tracks(String... ids) {
        return Arrays.stream(ids).map(Tracks::track).toList();
    }

    static List<TrackId> ids(String... ids) {
        return Arrays.stream(ids).map(TrackId::of).toList();
    }

    static List<TrackId> ids(List<Track> tracks) {
        return tracks.stream().map(Track::id).toList();
    }
}
