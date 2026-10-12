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

/**
 * Turns an untrusted list of chosen IDs into the final track list.
 *
 * <p>The LLM may hallucinate IDs, repeat itself or return too few. Only IDs present in the
 * candidate list survive; duplicates are dropped; a short answer is topped up from the
 * candidates in their original (similarity) order. No artist (counting side projects, see
 * {@link ArtistNames#groupKey}) gets more than {@link #maxPerArtist} tracks, unless there
 * aren't enough other artists to fill the mix.
 */
final class TrackSelection {

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
        Set<TrackId> picked = new LinkedHashSet<>();
        for (TrackId id : order) {
            if (picked.size() == count) {
                break;
            }
            String artist = ArtistNames.groupKey(byId.get(id).artist());
            if (perArtist.getOrDefault(artist, 0) < cap) {
                picked.add(id);
                perArtist.merge(artist, 1, Integer::sum);
            }
        }
        // Not enough artists to respect the cap: fill up anyway rather than return a short mix.
        for (TrackId id : order) {
            if (picked.size() == count) {
                break;
            }
            picked.add(id);
        }

        List<Track> result = new ArrayList<>(picked.size());
        for (TrackId id : picked) {
            result.add(byId.get(id));
        }
        return result;
    }

    /** True if at least one chosen ID is a real candidate. */
    static boolean anyValid(List<Track> candidates, List<TrackId> chosen) {
        Set<TrackId> ids = new LinkedHashSet<>();
        candidates.forEach(c -> ids.add(c.id()));
        return chosen.stream().anyMatch(ids::contains);
    }
}
