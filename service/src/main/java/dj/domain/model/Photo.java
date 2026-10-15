package dj.domain.model;

import java.util.Objects;
import java.util.Set;

/**
 * A picture the listener took to show DJ the moment. Only read, never stored.
 *
 * @param mediaType {@code image/jpeg}, {@code image/png}, {@code image/webp} or {@code image/gif}
 */
public record Photo(byte[] bytes, String mediaType) {

    public static final Set<String> MEDIA_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");

    public Photo {
        Objects.requireNonNull(bytes);
        if (bytes.length == 0) {
            throw new IllegalArgumentException("empty photo");
        }
        if (!MEDIA_TYPES.contains(mediaType)) {
            throw new IllegalArgumentException("not a supported picture: " + mediaType);
        }
    }
}
