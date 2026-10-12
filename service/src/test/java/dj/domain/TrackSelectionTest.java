package dj.domain;

import dj.domain.model.Track;
import dj.domain.model.TrackId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dj.domain.Tracks.ids;
import static dj.domain.Tracks.track;
import static dj.domain.Tracks.tracks;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackSelectionTest {

    private final List<Track> candidates = tracks("1", "2", "3", "4", "5");

    @Test
    void keepsTheChosenOrder() {
        List<Track> selected = TrackSelection.select(candidates, ids("4", "1", "3"), 3);

        assertEquals(ids("4", "1", "3"), ids(selected));
    }

    @Test
    void discardsIdsThatAreNotCandidates() {
        List<Track> selected = TrackSelection.select(candidates, ids("99", "2", "invented", "5"), 2);

        assertEquals(ids("2", "5"), ids(selected));
    }

    @Test
    void neverReturnsATrackOutsideTheCandidateList() {
        List<Track> selected = TrackSelection.select(candidates, ids("98", "99"), 5);

        assertTrue(candidates.containsAll(selected));
    }

    @Test
    void dropsDuplicateChoices() {
        List<Track> selected = TrackSelection.select(candidates, ids("2", "2", "3", "2"), 2);

        assertEquals(ids("2", "3"), ids(selected));
    }

    @Test
    void capsAtTheRequestedCount() {
        List<Track> selected = TrackSelection.select(candidates, ids("5", "4", "3", "2", "1"), 2);

        assertEquals(ids("5", "4"), ids(selected));
    }

    @Test
    void topsUpAShortAnswerFromCandidateOrder() {
        List<Track> selected = TrackSelection.select(candidates, ids("4"), 3);

        assertEquals(ids("4", "1", "2"), ids(selected));
    }

    @Test
    void usesCandidateOrderWhenNothingWasChosen() {
        List<Track> selected = TrackSelection.select(candidates, List.of(), 3);

        assertEquals(ids("1", "2", "3"), ids(selected));
    }

    @Test
    void returnsFewerTracksWhenThereAreFewerCandidates() {
        List<Track> selected = TrackSelection.select(tracks("1", "2"), ids("2"), 5);

        assertEquals(ids("2", "1"), ids(selected));
    }

    @Test
    void ignoresDuplicateCandidates() {
        List<Track> duplicated = List.of(track("1"), track("1"), track("2"));

        List<Track> selected = TrackSelection.select(duplicated, List.of(), 3);

        assertEquals(ids("1", "2"), ids(selected));
    }

    @Test
    void anyValidIsFalseWhenEveryChoiceIsInvented() {
        assertFalse(TrackSelection.anyValid(candidates, ids("98", "99")));
        assertTrue(TrackSelection.anyValid(candidates, ids("98", "3")));
    }

    @Test
    void trackIdsAreComparedByValue() {
        List<Track> selected = TrackSelection.select(candidates, List.of(TrackId.of(" 3 ")), 1);

        assertEquals(ids("3"), ids(selected));
    }
}
