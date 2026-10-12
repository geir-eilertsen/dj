package dj.domain.port;

/** The library could not be read, so no mix can be produced. */
public class LibraryUnavailableException extends RuntimeException {

    public LibraryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
