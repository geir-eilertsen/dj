package dj.adapter.stub;

import dj.domain.model.Photo;
import dj.domain.model.PhotoReading;
import dj.domain.port.PhotoReader;

import java.time.LocalDateTime;
import java.util.List;

/** Used when no LLM is configured: photos can't be read. */
public class NoPhotoReader implements PhotoReader {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public PhotoReading read(List<Photo> photos, LocalDateTime localTime) {
        throw new IllegalStateException("reading photos needs ANTHROPIC_API_KEY");
    }
}
