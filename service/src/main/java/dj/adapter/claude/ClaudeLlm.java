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
import com.anthropic.models.messages.ThinkingConfigDisabled;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dj.domain.model.ArtistProfile;
import dj.domain.model.MixBrief;
import dj.domain.model.Mood;
import dj.domain.model.Pick;
import dj.domain.model.PlaybackContext;
import dj.domain.model.Track;
import dj.domain.model.TrackId;
import dj.domain.port.ArtistProfileStore;
import dj.domain.port.LlmPort;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
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
 * <p>Thinking is off: measured on this workload, it doubled the output tokens and pushed calls
 * past the mix deadline (3.3-5.6 s versus 2.2-2.8 s) without better picks. There are no tool
 * calls, so disabled thinking's known failure modes don't apply.
 *
 * <p>Claude sees only what's needed for musical judgment: time and weekday, the listener's
 * request if any, the end of the queue, and compact candidate lines with style tags. It answers with candidate ids in a JSON schema;
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
            - If the listener asked for something, that comes first: every pick must fit the \
            request, before continuing from what was just played and before the time of day.
            - Continue naturally from what was just played. Keep the mood and genre, and let \
            energy and style change gradually; avoid abrupt jumps between very different styles.
            - Fit the time and day: calmer late at night and early in the morning, livelier on \
            weekend afternoons and evenings, unless the music just played clearly says otherwise.
            - Vary the artists: at most one track per artist unless there are too few candidates \
            or the listener asked for one artist.
            - Candidates are listed best match first. "library" tracks are the listener's own \
            music, "new" tracks are new to them; keep roughly the mix of both that the top of \
            the list has.
            - Use only ids from the candidate list, copied exactly.

            For each pick, give a short reason (at most 15 words) that would help the listener \
            understand the choice, for example "Same 70s Danish rock as Gasolin', a little more \
            upbeat"; if they asked for something, say how the pick fits it. Write the reasons in %s.
            """;

    private static final Logger LOG = System.getLogger(ClaudeLlm.class.getName());
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

    /**
     * Opens the connection and loads the SDK ahead of the first mix, which otherwise pays for
     * both and misses its deadline. Lists models: no tokens, no cost. Never throws.
     */
    public void warmUp() {
        try {
            long start = System.nanoTime();
            client.models().list();
            LOG.log(Level.INFO, "Claude connection ready in {0,number,#} ms", (System.nanoTime() - start) / 1_000_000);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not reach the Claude API yet; mixes fall back until it answers", e);
        }
    }

    @Override
    public List<Pick> choose(MixBrief brief, List<Track> candidates, int count) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .system(SYSTEM_PROMPT.formatted(language))
                .thinking(ThinkingConfigDisabled.builder().build())
                .outputConfig(OutputConfig.builder()
                        .effort(OutputConfig.Effort.LOW)
                        .format(PICKS_FORMAT)
                        .build())
                .addUserMessage(userMessage(brief, candidates, count))
                .build();

        long start = System.nanoTime();
        Message response = client.messages().create(params);
        LOG.log(Level.INFO, "Claude answered in {0,number,#} ms ({1,number,#} tokens in, {2,number,#} out)",
                (System.nanoTime() - start) / 1_000_000, response.usage().inputTokens(), response.usage().outputTokens());
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

    String userMessage(MixBrief brief, List<Track> candidates, int count) {
        PlaybackContext context = brief.context();
        StringBuilder m = new StringBuilder();
        m.append("Now: ")
                .append(context.localTime().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH))
                .append(' ')
                .append(String.format(Locale.ROOT, "%02d:%02d", context.localTime().getHour(), context.localTime().getMinute()))
                .append(", player \"").append(context.playerName()).append("\".\n\n");

        brief.mood().ifPresent(mood -> m.append("The listener asked for: \"").append(mood.text()).append("\"")
                .append(understood(mood.style())).append("\n\n"));

        if (brief.fresh() && brief.mood().isPresent()) {
            m.append("This starts a new queue for that request; it needn't follow what was playing.\n\n");
            if (!brief.taste().isEmpty()) {
                m.append("The listener's favourite artists lately: ")
                        .append(String.join(", ", brief.taste().artists().keySet()))
                        .append(". Among tracks that fit the request, prefer those closest to their taste.\n\n");
            }
        } else if (brief.fresh()) {
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

    /** How DJ understood the request, so the picks agree with the candidates' ranking. */
    private static String understood(Mood.Style style) {
        List<String> parts = new ArrayList<>();
        if (!style.tags().isEmpty()) {
            parts.add("styles " + String.join(", ", style.tags()));
        }
        if (!style.artists().isEmpty()) {
            parts.add("artists " + String.join(", ", style.artists()));
        }
        if (style.hasEra()) {
            parts.add("released " + (style.fromYear() > 0 ? style.fromYear() : "") + "-" + (style.toYear() > 0 ? style.toYear() : ""));
        }
        return parts.isEmpty() ? "." : " (understood as: " + String.join("; ", parts) + ").";
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
