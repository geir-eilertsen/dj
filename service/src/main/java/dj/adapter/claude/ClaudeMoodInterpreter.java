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
import dj.domain.model.Mood;
import dj.domain.port.MoodInterpreter;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Asks Claude what a listener's request means in terms the library can match: style tags from
 * the library's own vocabulary, artists, and a release-year range. Settings as in
 * {@link ClaudeLlm} (no thinking, low effort, no retries): this runs while the listener waits.
 */
public class ClaudeMoodInterpreter implements MoodInterpreter {

    static final int MAX_TAGS = 8;
    static final int MAX_ARTISTS = 5;

    static final String SYSTEM_PROMPT = """
            You help a home music system understand what the listener wants to hear. Turn their \
            request, in any language, into terms the music library can match:
            - tags: 2-%d style tags for the music they want, lower case. Use tags from the known \
            tags whenever one fits; add others only for styles none of them cover. Translate \
            moods into styles (for example "something calm for dinner" might be "jazz", \
            "acoustic", "singer-songwriter", "chillout").
            - artists: up to %d artists the request names or clearly points to, spelled as the \
            artists spell themselves. Leave it empty for requests that don't name or point to \
            artists; don't invent examples of a genre.
            - fromYear, toYear: the release years the request asks for ("80s" is 1980-1989, \
            "older music" might be 0-1989); 0 when it doesn't limit them.
            """.formatted(MAX_TAGS, MAX_ARTISTS);

    private static final Logger LOG = System.getLogger(ClaudeMoodInterpreter.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final JsonOutputFormat STYLE_FORMAT = JsonOutputFormat.builder()
            .schema(JsonOutputFormat.Schema.builder()
                    .putAdditionalProperty("type", JsonValue.from("object"))
                    .putAdditionalProperty("properties", JsonValue.from(Map.of(
                            "tags", Map.of("type", "array", "items", Map.of("type", "string")),
                            "artists", Map.of("type", "array", "items", Map.of("type", "string")),
                            "fromYear", Map.of("type", "integer"),
                            "toYear", Map.of("type", "integer"))))
                    .putAdditionalProperty("required", JsonValue.from(List.of("tags", "artists", "fromYear", "toYear")))
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .build())
            .build();

    private final AnthropicClient client;
    private final String model;

    public ClaudeMoodInterpreter(String apiKey, String model, Duration timeout) {
        this(AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(timeout).maxRetries(0).build(), model);
    }

    ClaudeMoodInterpreter(AnthropicClient client, String model) {
        this.client = client;
        this.model = model;
    }

    @Override
    public Mood.Style interpret(String request, List<String> knownTags) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(2000L)
                .system(SYSTEM_PROMPT)
                .thinking(ThinkingConfigDisabled.builder().build())
                .outputConfig(OutputConfig.builder()
                        .effort(OutputConfig.Effort.LOW)
                        .format(STYLE_FORMAT)
                        .build())
                .addUserMessage(userMessage(request, knownTags))
                .build();

        long start = System.nanoTime();
        Message response = client.messages().create(params);
        LOG.log(Level.INFO, "Claude understood the mood in {0,number,#} ms", (System.nanoTime() - start) / 1_000_000);
        StopReason stop = response.stopReason().orElse(null);
        if (StopReason.REFUSAL.equals(stop) || StopReason.MAX_TOKENS.equals(stop)) {
            throw new IllegalStateException("Claude gave no usable answer (" + stop + ")");
        }
        return parse(response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(TextBlock::text)
                .collect(Collectors.joining()));
    }

    static String userMessage(String request, List<String> knownTags) {
        return "Known tags: " + String.join(", ", knownTags) + "\n\nRequest: " + request;
    }

    static Mood.Style parse(String json) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (IOException e) {
            throw new IllegalStateException("Claude's answer wasn't JSON: " + json, e);
        }
        return new Mood.Style(strings(root.path("tags"), MAX_TAGS), strings(root.path("artists"), MAX_ARTISTS),
                root.path("fromYear").asInt(0), root.path("toYear").asInt(0));
    }

    private static List<String> strings(JsonNode array, int max) {
        List<String> values = new ArrayList<>();
        for (JsonNode value : array) {
            if (values.size() < max && value.isTextual() && !value.asText().isBlank()) {
                values.add(value.asText());
            }
        }
        return values;
    }
}
