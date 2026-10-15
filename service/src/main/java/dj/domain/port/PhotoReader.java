package dj.domain.port;

import dj.domain.model.Photo;
import dj.domain.model.PhotoReading;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Reads the moment from the listener's photos: what music would suit it. Called only when the
 * listener asks, never while mixing; may be slow and need the internet.
 */
public interface PhotoReader {

    /** False when there's nothing to read photos with; {@link #read} then fails. */
    default boolean available() {
        return true;
    }

    /**
     * @param photos    one moment, one or more pictures of it
     * @param localTime the listener's local date and time
     */
    PhotoReading read(List<Photo> photos, LocalDateTime localTime);
}
