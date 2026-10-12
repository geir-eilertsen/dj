package dj.adapter.lms;

import com.fasterxml.jackson.databind.JsonNode;
import dj.domain.model.NowPlaying;
import dj.domain.model.Track;
import dj.domain.port.PlayerActivityPort;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Asks LMS what each connected player is playing ({@code players}, then {@code status} per player). */
public class LmsPlayerActivity implements PlayerActivityPort {

    private final LmsJsonRpcClient lms;

    public LmsPlayerActivity(LmsJsonRpcClient lms) {
        this.lms = lms;
    }

    @Override
    public List<NowPlaying> nowPlaying() {
        try {
            List<NowPlaying> result = new ArrayList<>();
            for (JsonNode player : lms.request("", List.of("players", 0, 100)).path("players_loop")) {
                if (player.path("connected").asInt(0) != 1) {
                    continue;
                }
                String id = player.path("playerid").asText();
                JsonNode status = lms.request(id, List.of("status", "-", 1, "tags:aluy"));
                parse(id, status).ifPresent(result::add);
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not ask LMS what is playing", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }

    static Optional<NowPlaying> parse(String playerId, JsonNode status) {
        List<Track> current = LmsPlaybackContext.queue(status);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new NowPlaying(playerId,
                "play".equals(status.path("mode").asText()),
                current.getFirst(),
                status.path("time").asDouble(0),
                status.path("duration").asDouble(0)));
    }
}
