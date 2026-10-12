package dj.domain;

import dj.domain.model.Play;
import dj.domain.port.PlayHistory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Test fake for {@link PlayHistory}. */
final class InMemoryPlayHistory implements PlayHistory {

    final List<Play> plays = new ArrayList<>();

    @Override
    public void record(Play play) {
        plays.add(play);
    }

    @Override
    public List<Play> playsOn(String playerId, Instant since) {
        return plays.stream()
                .filter(p -> p.playerId().equals(playerId) && !p.startedAt().isBefore(since))
                .sorted(Comparator.comparing(Play::startedAt))
                .toList();
    }

    @Override
    public Optional<Play> lastPlayOn(String playerId) {
        return plays.stream().filter(p -> p.playerId().equals(playerId)).max(Comparator.comparing(Play::startedAt));
    }
}
