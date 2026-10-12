package dj.adapter.claude;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dj.domain.model.ArtistProfile;
import dj.domain.model.Pick;
import dj.domain.model.PlaybackContext;
import dj.domain.model.Track;
import dj.domain.model.TrackId;
import dj.domain.port.ArtistProfileStore;
import dj.domain.port.LlmPort;

import java.io.IOException;
import java.time.Duration;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Asks Claude to pick and order tracks from the candidates, with a one-line reason each.
 *
 * <p>Claude sees only what's needed for musical judgment: time and weekday, the end of the queue,
 * and compact candidate lines with style tags. It answers with candidate ids in a JSON schema;
 * the domain discards any id that isn't a candidate. Any failure (network, refusal, bad output)
 * is thrown, and the mix falls back to candidate order.
 */
public class ClaudeLlm implements LlmPort {

    /** How many tracks at the end of the queue to describe. */
    static final int QUEUE_TAIL = 8;
    static final int TAGS_PER_ARTIST = 4;

    static final String SYSTEM_PROMPT = """
            You are the DJ for a home music system. You choose what plays next from a list of \
            candidate tracks.

            Choose the requested number of tracks (fewer only if too few candidates fit) and put \
            them in play order:
            - Continue naturally from what was just played. Keep the mood and genre, and let \
            energy and style change gradually; avoid abrupt jumps between very different styles.
            - Fit the time and day: calmer late at night and early in the morning, livelier on \
            weekend afternoons and evenings, unless the music just played clearly says otherwise.
            - Vary the artists: at most one track per artist unless there are too few candidates.
            - Candidates are listed best match first. "library" tracks are the listener's own \
            music, "new" tracks are new to them; keep roughly the mix of both that the top of \
            the list has.
            - Use only ids from the candidate list, copied exactly.

            For each pick, give a short reason (at most 15 words) that would help the listener \
            understand the choice, for example "Same 70s Danish rock as Gasolin', a little more \
            upbeat". Write the reasons in %s.
            """;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final JsonOutputFormat PICKS_FORMAT = JsonOutputFormat.builder()
            .schema(JsonOutputFormat.Schema.builder()
                    .putAdditionalProperty("type", JsonValue.from("object"))
                    .putAdditionalProperty("properties", JsonValue.from(Map.of(
                            "picks", Map.of(
                                    "type", "array",
                                    "items", Map.of(
                                            "type", "object",
                                            "properties", Map.of(
                                                    "id", Map.of("type", "string"),
                                                    "reason", Map.of("type", "string")),
                                            "required", List.of("id", "reason"),
                                            "additionalProperties", false)))))
                    .putAdditionalProperty("required", JsonValue.from(List.of("picks")))
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .build())
            .build();

    private final AnthropicClient client;
    private final String model;
    private final String language;
    private final ArtistProfileStore profiles;

    /**
     * @param timeout per request; no retries, because the mix has its own deadline and fallback
     */
    public ClaudeLlm(String apiKey, String model, String language, Duration timeout, ArtistProfileStore profiles) {
        this(AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(timeout).maxRetries(0).build(),
                model, language, profiles);
    }

    ClaudeLlm(AnthropicClient client, String model, String language, ArtistProfileStore profiles) {
        this.client = client;
        this.model = model;
        this.language = language;
        this.profiles = profiles;
    }

    @Override
    public List<Pick> choose(PlaybackContext context, List<Track> candidates, int count) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .system(SYSTEM_PROMPT.formatted(language))
                .outputConfig(OutputConfig.builder()
                        .effort(OutputConfig.Effort.LOW)
                        .format(PICKS_FORMAT)
                        .build())
                .addUserMessage(userMessage(context, candidates, count))
                .build();

        Message response = client.messages().create(params);
        StopReason stop = response.stopReason().orElse(null);
        if (StopReason.REFUSAL.equals(stop)) {
            throw new IllegalStateException("Claude declined to choose: " + response.stopDetails().orElse(null));
        }
        if (StopReason.MAX_TOKENS.equals(stop)) {
            throw new IllegalStateException("Claude's answer was cut off");
        }
        String text = response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(TextBlock::text)
                .collect(Collectors.joining());
        return parse(text);
    }

    String userMessage(PlaybackContext context, List<Track> candidates, int count) {
        StringBuilder m = new StringBuilder();
        m.append("Now: ")
                .append(context.localTime().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH))
                .append(' ')
                .append(String.format(Locale.ROOT, "%02d:%02d", context.localTime().getHour(), context.localTime().getMinute()))
                .append(", player \"").append(context.playerName()).append("\".\n\n");

        if (!context.playing()) {
            m.append("The player is starting fresh; the candidates were chosen from what is usually played around this time.\n\n");
        } else if (!context.queue().isEmpty()) {
            List<Track> queue = context.queue();
            m.append("Just played (oldest first; your picks follow the last one):\n");
            for (Track t : queue.subList(Math.max(0, queue.size() - QUEUE_TAIL), queue.size())) {
                m.append("- ").append(describe(t)).append('\n');
            }
            m.append('\n');
        }

        m.append("Pick ").append(count).append(" tracks from these candidates.\n")
                .append("id | artist | title | album | year | style | library/new\n");
        for (Track t : candidates) {
            m.append(t.id().value()).append(" | ")
                    .append(t.artist()).append(" | ")
                    .append(t.title()).append(" | ")
                    .append(t.album()).append(" | ")
                    .append(t.year() > 0 ? String.valueOf(t.year()) : "").append(" | ")
                    .append(tags(t.artist())).append(" | ")
                    .append(isLibrary(t) ? "library" : "new")
                    .append('\n');
        }
        return m.toString();
    }

    private String describe(Track t) {
        String tags = tags(t.artist());
        return t.artist() + " - " + t.title() + (t.year() > 0 ? " (" + t.year() + ")" : "")
                + (tags.isEmpty() ? "" : " [" + tags + "]");
    }

    private String tags(String artist) {
        return profiles.find(artist).stream()
                .flatMap(p -> p.tags().stream())
                .limit(TAGS_PER_ARTIST)
                .map(ArtistProfile.Tag::name)
                .collect(Collectors.joining(", "));
    }

    /** Library tracks have LMS's numeric ids; discovered tracks are identified by their URL. */
    private static boolean isLibrary(Track t) {
        return t.id().value().chars().allMatch(Character::isDigit);
    }

    static List<Pick> parse(String json) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (IOException e) {
            throw new IllegalStateException("Claude's answer wasn't JSON: " + json, e);
        }
        List<Pick> picks = new ArrayList<>();
        for (JsonNode pick : root.path("picks")) {
            String id = pick.path("id").asText("").strip();
            if (!id.isEmpty()) {
                picks.add(new Pick(TrackId.of(id), pick.path("reason").asText("")));
            }
        }
        return picks;
    }
}
