package dj.adapter.claude;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigDisabled;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dj.domain.model.Photo;
import dj.domain.model.PhotoReading;
import dj.domain.port.PhotoReader;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Asks Claude what music suits the moment the listener's photos show. Settings as in
 * {@link ClaudeLlm} (no thinking, low effort, no retries). The photos only go into the request:
 * they are never stored or logged.
 *
 * <p>The scene (place, light, activity, the day and time) counts more than faces, which say
 * little about what to play, and the answer is one direction rather than "X or Y", since a mood's
 * years apply to all of it.
 */
public class ClaudePhotoReader implements PhotoReader {

    /** Pictures take longer than text: a few seconds each. */
    public static final Duration TIMEOUT = Duration.ofSeconds(20);

    static final String SYSTEM_PROMPT = """
            You read the moment for a home music DJ. The listener took these photos to show where \
            they are and what they are doing; decide what music would suit the moment.
            - Use the day and time and the whole scene: place, light, activity, people, anything \
            already playing. Several photos are one moment: give one mood for all of them.
            - The scene counts more than facial expressions. Never guess anyone's age, identity, \
            health or other personal traits, and don't describe people's looks.
            - mood: a request the listener could have typed, in %1$s, at most 10 words, with one \
            clear direction (not "X or Y"), for example "gentle 70s soft rock for a quiet morning". \
            Name an artist only when the photos show one.
            - why: one sentence in %1$s on what in the photos led you there.
            """;

    private static final Logger LOG = System.getLogger(ClaudePhotoReader.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final JsonOutputFormat READING_FORMAT = JsonOutputFormat.builder()
            .schema(JsonOutputFormat.Schema.builder()
                    .putAdditionalProperty("type", JsonValue.from("object"))
                    .putAdditionalProperty("properties", JsonValue.from(Map.of(
                            "mood", Map.of("type", "string"),
                            "why", Map.of("type", "string"))))
                    .putAdditionalProperty("required", JsonValue.from(List.of("mood", "why")))
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .build())
            .build();

    private final AnthropicClient client;
    private final String model;
    private final String language;

    public ClaudePhotoReader(String apiKey, String model, String language) {
        this(AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(TIMEOUT).maxRetries(0).build(), model, language);
    }

    ClaudePhotoReader(AnthropicClient client, String model, String language) {
        this.client = client;
        this.model = model;
        this.language = language;
    }

    @Override
    public PhotoReading read(List<Photo> photos, LocalDateTime localTime) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(2000L)
                .system(SYSTEM_PROMPT.formatted(language))
                .thinking(ThinkingConfigDisabled.builder().build())
                .outputConfig(OutputConfig.builder()
                        .effort(OutputConfig.Effort.LOW)
                        .format(READING_FORMAT)
                        .build())
                .addUserMessageOfBlockParams(content(photos, localTime))
                .build();

        long start = System.nanoTime();
        Message response = client.messages().create(params);
        LOG.log(Level.INFO, "Claude read {0} photo(s) in {1,number,#} ms", photos.size(),
                (System.nanoTime() - start) / 1_000_000);
        StopReason stop = response.stopReason().orElse(null);
        if (StopReason.REFUSAL.equals(stop) || StopReason.MAX_TOKENS.equals(stop)) {
            throw new IllegalStateException("Claude gave no usable answer (" + stop + ")");
        }
        return parse(response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(TextBlock::text)
                .collect(Collectors.joining()));
    }

    /** The pictures first, then when they were taken. */
    static List<ContentBlockParam> content(List<Photo> photos, LocalDateTime localTime) {
        List<ContentBlockParam> blocks = new ArrayList<>();
        for (Photo photo : photos) {
            blocks.add(ContentBlockParam.ofImage(ImageBlockParam.builder()
                    .source(Base64ImageSource.builder()
                            .mediaType(Base64ImageSource.MediaType.of(photo.mediaType()))
                            .data(Base64.getEncoder().encodeToString(photo.bytes()))
                            .build())
                    .build()));
        }
        blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(when(localTime, photos.size())).build()));
        return blocks;
    }

    static String when(LocalDateTime localTime, int photos) {
        return "It is " + localTime.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                + String.format(Locale.ROOT, " %02d:%02d", localTime.getHour(), localTime.getMinute())
                + " for the listener. " + (photos == 1 ? "This photo shows" : "These " + photos + " photos show")
                + " the moment.";
    }

    static PhotoReading parse(String json) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (IOException e) {
            throw new IllegalStateException("Claude's answer wasn't JSON: " + json, e);
        }
        return new PhotoReading(root.path("mood").asText("").strip(), root.path("why").asText("").strip());
    }
}
