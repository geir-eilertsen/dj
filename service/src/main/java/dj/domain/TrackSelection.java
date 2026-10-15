package dj.domain;

import dj.domain.model.ArtistNames;
import dj.domain.model.Track;
import dj.domain.model.TrackId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Turns an untrusted list of chosen IDs into the final track list.
 *
 * <p>The LLM may hallucinate IDs, repeat itself or return too few. Only IDs present in the
 * candidate list survive; duplicates are dropped; a short answer is topped up from the
 * candidates in their original (similarity) order. No artist (counting side projects, see
 * {@link ArtistNames#groupKey}) gets more than {@link #maxPerArtist} tracks, and an artist's
 * tracks are at least {@link #ARTIST_GAP} places apart, unless there aren't enough other artists
 * to fill the mix: then the gap goes first, the cap last.
 */
final class TrackSelection {

    /** Places between two tracks of one artist: one per artist in any 5 tracks in a row. */
    static final int ARTIST_GAP = 5;

    private TrackSelection() {
    }

    /** One track per artist per 5 in the mix: 1 in a 5-track top-up, 2 in a 10-track start. */
    static int maxPerArtist(int count) {
        return Math.max(1, count / 5);
    }

    static List<Track> select(List<Track> candidates, List<TrackId> chosen, int count) {
        Map<TrackId, Track> byId = new LinkedHashMap<>();
        for (Track candidate : candidates) {
            byId.putIfAbsent(candidate.id(), candidate);
        }

        // Preference order: valid choices first, then the remaining candidates.
        Set<TrackId> order = new LinkedHashSet<>();
        for (TrackId id : chosen) {
            if (byId.containsKey(id)) {
                order.add(id);
            }
        }
        order.addAll(byId.keySet());

        int cap = maxPerArtist(count);
        Map<String, Integer> perArtist = new HashMap<>();
        Map<String, Integer> lastPlace = new HashMap<>();
        Set<TrackId> picked = new LinkedHashSet<>();
        while (picked.size() < count) {
            TrackId next = first(order, picked, byId, a -> perArtist.getOrDefault(a, 0) < cap
                    && picked.size() - lastPlace.getOrDefault(a, -ARTIST_GAP) >= ARTIST_GAP);
            if (next == null) {
                next = first(order, picked, byId, a -> perArtist.getOrDefault(a, 0) < cap);
            }
            if (next == null) {
                next = first(order, picked, byId, a -> true);
            }
            if (next == null) {
                break;
            }
            String artist = ArtistNames.groupKey(byId.get(next).artist());
            perArtist.merge(artist, 1, Integer::sum);
            lastPlace.put(artist, picked.size());
            picked.add(next);
        }

        List<Track> result = new ArrayList<>(picked.size());
        for (TrackId id : picked) {
            result.add(byId.get(id));
        }
        return result;
    }

    /** The first track in preference order not yet picked whose artist (group key) is allowed. */
    private static TrackId first(Set<TrackId> order, Set<TrackId> picked, Map<TrackId, Track> byId,
                                 Predicate<String> artistAllowed) {
        for (TrackId id : order) {
            if (!picked.contains(id) && artistAllowed.test(ArtistNames.groupKey(byId.get(id).artist()))) {
                return id;
            }
        }
        return null;
    }

    /** True if at least one chosen ID is a real candidate. */
    static boolean anyValid(List<Track> candidates, List<TrackId> chosen) {
        Set<TrackId> ids = new LinkedHashSet<>();
        candidates.forEach(c -> ids.add(c.id()));
        return chosen.stream().anyMatch(ids::contains);
    }
}
