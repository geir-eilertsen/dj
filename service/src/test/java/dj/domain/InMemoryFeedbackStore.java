package dj.domain;

import dj.domain.model.Skip;
import dj.domain.model.Track;
import dj.domain.port.FeedbackStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Test fake for {@link FeedbackStore}. */
final class InMemoryFeedbackStore implements FeedbackStore {

    record Suggestion(String playerId, String url, Instant at) {
    }

    final List<Suggestion> suggestions = new ArrayList<>();
    final List<Skip> skips = new ArrayList<>();

    @Override
    public void recordSuggested(String playerId, List<Track> tracks, Instant at) {
        tracks.forEach(t -> suggestions.add(new Suggestion(playerId, t.url(), at)));
    }

    @Override
    public boolean wasSuggested(String playerId, String url, Instant since) {
        return suggestions.stream().anyMatch(s -> s.playerId().equals(playerId) && s.url().equals(url) && !s.at().isBefore(since));
    }

    @Override
    public void recordSkip(Skip skip) {
        skips.add(skip);
    }

    @Override
    public List<Skip> skipsSince(Instant since) {
        return skips.stream().filter(s -> !s.at().isBefore(since)).toList();
    }
}
