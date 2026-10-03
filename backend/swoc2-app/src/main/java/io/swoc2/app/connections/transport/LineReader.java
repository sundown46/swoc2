package io.swoc2.app.connections.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Splits a byte stream into lines terminated by {@code \n} (ICD §2), decoded as ISO-8859-1 (ICD §1
 * "ASCII"). A line longer than {@code maxBytes} is discarded up to its next terminator and reported
 * once, so a sender that never sends a newline cannot exhaust memory (CLAUDE.md principle 1).
 */
public final class LineReader {

    private final int maxBytes;

    public LineReader(int maxBytes) {
        this.maxBytes = maxBytes;
    }

    /**
     * Reads until end of stream or I/O error.
     *
     * @param onLine receives each complete line (without {@code \n} / trailing {@code \r})
     * @param onOversize receives a short description when a line exceeded the limit
     */
    public void read(InputStream in, Consumer<String> onLine, Consumer<String> onOversize) throws IOException {
        byte[] buffer = new byte[Math.min(maxBytes, 64 * 1024)];
        byte[] line = new byte[1024];
        int length = 0;
        boolean discarding = false;
        int n;
        while ((n = in.read(buffer)) >= 0) {
            for (int i = 0; i < n; i++) {
                byte b = buffer[i];
                if (b == '\n') {
                    if (!discarding) {
                        int end = length > 0 && line[length - 1] == '\r' ? length - 1 : length;
                        if (end > 0) {
                            onLine.accept(new String(line, 0, end, StandardCharsets.ISO_8859_1));
                        }
                    }
                    length = 0;
                    discarding = false;
                    continue;
                }
                if (discarding) {
                    continue;
                }
                if (length == maxBytes) {
                    discarding = true;
                    onOversize.accept("line longer than " + maxBytes + " bytes discarded");
                    continue;
                }
                if (length == line.length) {
                    byte[] bigger = new byte[Math.min(maxBytes, line.length * 2)];
                    System.arraycopy(line, 0, bigger, 0, length);
                    line = bigger;
                }
                line[length++] = b;
            }
        }
    }

    /** Variant for callers that cannot throw checked exceptions. */
    public void readUnchecked(InputStream in, Consumer<String> onLine, Consumer<String> onOversize) {
        try {
            read(in, onLine, onOversize);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
