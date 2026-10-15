package dj.domain;

import dj.domain.model.Mood;
import dj.domain.model.Photo;
import dj.domain.model.PhotoReading;
import dj.domain.port.PhotoReader;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Sets a player's mood from photos of the moment: the photos are read as a request in words,
 * which is then set like a typed one ({@link MoodService#set}), so it shows among the recent
 * moods and can be played again without the photos. Several photos make one mood.
 */
public final class PhotoMoods {

    public static final int MAX_PHOTOS = 4;

    private static final Logger LOG = System.getLogger(PhotoMoods.class.getName());

    /** @param why what in the photos led to the mood */
    public record Result(Mood mood, String why) {
    }

    private final PhotoReader reader;
    private final MoodService moods;
    private final Clock clock;

    /** @param clock in the listener's time zone */
    public PhotoMoods(PhotoReader reader, MoodService moods, Clock clock) {
        this.reader = Objects.requireNonNull(reader);
        this.moods = Objects.requireNonNull(moods);
        this.clock = Objects.requireNonNull(clock);
    }

    public boolean available() {
        return reader.available();
    }

    /**
     * @throws IllegalArgumentException with no photos or more than {@link #MAX_PHOTOS}
     * @throws IllegalStateException    when the photos can't be read
     */
    public Result fromPhotos(String playerId, List<Photo> photos) {
        if (photos.isEmpty() || photos.size() > MAX_PHOTOS) {
            throw new IllegalArgumentException("send 1 to " + MAX_PHOTOS + " photos");
        }
        PhotoReading reading = reader.read(photos, LocalDateTime.now(clock));
        if (reading == null || reading.mood() == null || reading.mood().isBlank()) {
            throw new IllegalStateException("the photos gave no mood");
        }
        LOG.log(Level.INFO, "{0} photo(s) for {1} read as \"{2}\"", photos.size(), playerId, reading.mood());
        return new Result(moods.set(playerId, reading.mood().strip()),
                reading.why() == null ? "" : reading.why().strip());
    }
}
