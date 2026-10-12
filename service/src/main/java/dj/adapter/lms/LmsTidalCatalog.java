package dj.adapter.lms;

import com.fasterxml.jackson.databind.JsonNode;
import dj.domain.model.ArtistNames;
import dj.domain.model.SongTitles;
import dj.domain.model.Track;
import dj.domain.model.TrackId;
import dj.domain.port.CatalogPort;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;

/**
 * Finds songs on TIDAL through LMS's TIDAL plugin, so DJ needs no TIDAL credentials of its own
 * and gets back URLs every player can already play.
 *
 * <p>The plugin is an OPML menu: Search → (query) → Songs. Its searches need a player, so any
 * connected player is used; nothing is played on it. Results must match both the artist and
 * the song title, which rules out covers and karaoke versions ("… (Originally Performed By …)"
 * by someone else).
 */
public class LmsTidalCatalog implements CatalogPort {

    private static final String APP = "tidal";
    private static final int RESULTS = 15;

    private final LmsJsonRpcClient lms;
    private volatile String searchItemId;

    public LmsTidalCatalog(LmsJsonRpcClient lms) {
        this.lms = lms;
    }

    @Override
    public Optional<Track> find(String artist, String title) {
        try {
            String player = anyPlayer();
            JsonNode search = lms.request(player,
                    List.of(APP, "items", 0, 10, "item_id:" + searchItemId(player), "search:" + artist + " " + title));
            Optional<String> songs = childId(search, "Songs");
            if (songs.isEmpty()) {
                return Optional.empty();
            }
            JsonNode results = lms.request(player, List.of(APP, "items", 0, RESULTS, "item_id:" + songs.get(), "menu:1"));
            return bestMatch(results, artist, title);
        } catch (IOException e) {
            throw new UncheckedIOException("TIDAL search failed for " + artist + " - " + title, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }

    /** Picks the first result whose artist and title both match. Items read "Title\nArtist". */
    static Optional<Track> bestMatch(JsonNode results, String artist, String title) {
        String artistKey = ArtistNames.key(artist);
        String titleKey = SongTitles.key(title);
        for (JsonNode item : results.path("item_loop")) {
            String[] lines = item.path("text").asText("").split("\n", 2);
            String url = item.path("presetParams").path("favorites_url").asText("");
            if (lines.length < 2 || url.isBlank()) {
                continue;
            }
            if (ArtistNames.key(lines[1]).equals(artistKey) && SongTitles.key(lines[0]).equals(titleKey)) {
                return Optional.of(new Track(TrackId.of(url), url, lines[0], artist, "", 0, 0));
            }
        }
        return Optional.empty();
    }

    private String searchItemId(String player) throws IOException, InterruptedException {
        if (searchItemId == null) {
            JsonNode top = lms.request(player, List.of(APP, "items", 0, 50));
            searchItemId = childId(top, "Search")
                    .orElseThrow(() -> new IOException("The TIDAL plugin has no Search menu"));
        }
        return searchItemId;
    }

    private String anyPlayer() throws IOException, InterruptedException {
        JsonNode players = lms.request("", List.of("players", 0, 1));
        String id = players.path("players_loop").path(0).path("playerid").asText("");
        if (id.isEmpty()) {
            throw new IOException("No player connected to LMS; TIDAL can't be searched");
        }
        return id;
    }

    private static Optional<String> childId(JsonNode menu, String name) {
        for (JsonNode item : menu.path("loop_loop")) {
            if (name.equalsIgnoreCase(item.path("name").asText())) {
                return Optional.of(item.path("id").asText());
            }
        }
        return Optional.empty();
    }
}
