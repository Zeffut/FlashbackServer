package dev.zeffut.flashbackserver.format;

/**
 * A single action in a Flashback chunk: a registry {@code identifier} and its opaque
 * {@code payload} bytes.
 *
 * <p>Note: {@code equals}/{@code hashCode} use array identity for {@code payload}
 * (record default). Compare payloads with {@link java.util.Arrays#equals(byte[], byte[])}.
 */
public record ReplayAction(String identifier, byte[] payload) {}
