package dj.adapter.claude;

import dj.domain.model.ArtistNames;
import dj.domain.model.ArtistProfile;
import dj.domain.model.Pick;
import dj.domain.model.PlaybackContext;
import dj.domain.model.Track;
import dj.domain.model.TrackId;
import dj.domain.port.ArtistProfileStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The prompt and response handling; no network. */
class ClaudeLlmTest {

    private static final LocalDateTime SUNDAY_MORNING = LocalDateTime.of(2026, 10, 11, 9, 5);

    private final ArtistProfileStore profiles = new ArtistProfileStore() {
        private final Map<String, ArtistProfile> byKey = Map.of(
                "kim larsen", new ArtistProfile("Kim Larsen", List.of(new ArtistProfile.Tag("danish", 1),
                        new ArtistProfile.Tag("rock", 0.8)), List.of(), Instant.EPOCH));

        @Override
        public Optional<ArtistProfile> find(String artistName) {
            return Optional.ofNullable(byKey.get(ArtistNames.key(artistName)));
        }

        @Override
        public void save(ArtistProfile profile) {
        }

        @Override
        public int size() {
            return byKey.size();
        }
    };

    private final ClaudeLlm llm = new ClaudeLlm(null, "claude-haiku-5-5", "English", profiles);

    private final Track playing = new Track(TrackId.of("-104704481239800"), "tidal://105920863.flc", "Sommer",
            "Kim Larsen", "Midt Om Natten", 1983, 0);
    private final Track library = new Track(TrackId.of("551"), "spotify:track:x", "This Is My Life", "Gasolin'",
            "Gas 5", 1975, 3);
    private final Track discovered = new Track(TrackId.of("tidal://5893422.flc"), "tidal://5893422.flc",
            "Bag Duggede Ruder", "TV-2", "", 0, 0);

    @Test
    void describesTheMomentTheQueueAndTheCandidates() {
        PlaybackContext context = new PlaybackContext("p1", "Kjøkken", true, List.of(playing), SUNDAY_MORNING);

        String message = llm.userMessage(context, List.of(library, discovered), 5);

        assertTrue(message.startsWith("Now: Sunday 09:05, player \"Kjøkken\"."), message);
        assertTrue(message.contains("- Kim Larsen - Sommer (1983) [danish, rock]"), message);
        assertTrue(message.contains("Pick 5 tracks"), message);
        assertTrue(message.contains("551 | Gasolin' | This Is My Life | Gas 5 | 1975 |  | library"), message);
        assertTrue(message.contains("tidal://5893422.flc | TV-2 | Bag Duggede Ruder |  |  |  | new"), message);
    }

    @Test
    void saysAStoppedPlayerIsStartingFresh() {
        PlaybackContext context = new PlaybackContext("p1", "Kjøkken", false, List.of(playing), SUNDAY_MORNING);

        String message = llm.userMessage(context, List.of(library), 5);

        assertTrue(message.contains("starting fresh"), message);
        assertFalse(message.contains("Just played"), message);
    }

    @Test
    void readsPicksAndReasons() {
        List<Pick> picks = ClaudeLlm.parse("""
                {"picks":[{"id":"551","reason":"Kim Larsen's own band, same era"},
                          {"id":" tidal://5893422.flc ","reason":"Danish 80s pop-rock, a gentle lift"},
                          {"id":"","reason":"blank ids are dropped"}]}""");

        assertEquals(List.of(new Pick(TrackId.of("551"), "Kim Larsen's own band, same era"),
                new Pick(TrackId.of("tidal://5893422.flc"), "Danish 80s pop-rock, a gentle lift")), picks);
    }

    @Test
    void rejectsAnswersThatAreNotJson() {
        assertThrows(IllegalStateException.class, () -> ClaudeLlm.parse("Sure! Here are some tracks"));
    }

    @Test
    void asksForReasonsInTheConfiguredLanguage() {
        assertTrue(ClaudeLlm.SYSTEM_PROMPT.formatted("Norwegian").contains("Write the reasons in Norwegian."));
    }
}
