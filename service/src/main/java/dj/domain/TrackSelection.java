package dj.domain;

import dj.domain.model.Track;
import dj.domain.model.TrackId;

import java.util.ArrayList;
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
 * candidates in their original (similarity) order.
 */
final class TrackSelection {

    private TrackSelection() {
    }

    static List<Track> select(List<Track> candidates, List<TrackId> chosen, int count) {
        Map<TrackId, Track> byId = new LinkedHashMap<>();
        for (Track candidate : candidates) {
            byId.putIfAbsent(candidate.id(), candidate);
        }

        Set<TrackId> picked = new LinkedHashSet<>();
        for (TrackId id : chosen) {
            if (picked.size() == count) {
                break;
            }
            if (byId.containsKey(id)) {
                picked.add(id);
            }
        }
        for (TrackId id : byId.keySet()) {
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
