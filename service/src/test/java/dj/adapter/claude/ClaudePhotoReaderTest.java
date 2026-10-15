package dj.adapter.claude;

import com.anthropic.models.messages.ContentBlockParam;
import dj.domain.model.Photo;
import dj.domain.model.PhotoReading;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The prompt and response handling; no network. */
class ClaudePhotoReaderTest {

    private static final LocalDateTime THURSDAY_MORNING = LocalDateTime.of(2026, 10, 15, 7, 36);

    @Test
    void sendsThePicturesThenWhenTheyWereTaken() {
        List<ContentBlockParam> blocks = ClaudePhotoReader.content(List.of(
                new Photo(new byte[]{1, 2, 3}, "image/jpeg"), new Photo(new byte[]{4}, "image/png")), THURSDAY_MORNING);

        assertEquals(3, blocks.size());
        assertEquals("AQID", blocks.get(0).image().orElseThrow().source().base64().orElseThrow().data());
        assertEquals("image/png", blocks.get(1).image().orElseThrow().source().base64().orElseThrow().mediaType().asString());
        assertEquals("It is Thursday 07:36 for the listener. These 2 photos show the moment.",
                blocks.get(2).text().orElseThrow().text());
    }

    @Test
    void oneMomentOnePhoto() {
        assertTrue(ClaudePhotoReader.when(THURSDAY_MORNING, 1).endsWith("This photo shows the moment."));
    }

    @Test
    void asksForTheAnswerInTheConfiguredLanguage() {
        assertTrue(ClaudePhotoReader.SYSTEM_PROMPT.formatted("Norwegian").contains("in Norwegian"));
    }

    @Test
    void readsTheMoodAndWhy() {
        assertEquals(new PhotoReading("gentle soft rock", "Morning light."),
                ClaudePhotoReader.parse("{\"mood\":\" gentle soft rock \",\"why\":\"Morning light.\"}"));
    }

    @Test
    void rejectsAnswersThatAreNotJson() {
        assertThrows(IllegalStateException.class, () -> ClaudePhotoReader.parse("What a nice room!"));
    }
}
