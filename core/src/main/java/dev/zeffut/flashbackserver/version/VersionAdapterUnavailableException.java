package dev.zeffut.flashbackserver.version;

/**
 * Thrown when the version adapter class cannot be loaded (not shaded into the jar, partial
 * classpath, or reflective construction failed for a class-loading reason).
 *
 * <p>Callers such as {@code FlashbackAPI.verify} treat this as “decode skipped”, not as a
 * successful verification.
 */
public final class VersionAdapterUnavailableException extends IllegalStateException {

    public VersionAdapterUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
