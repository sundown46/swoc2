package io.swoc2.app.connections.transport;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits one UDP datagram into SEDAP-Express messages: "multiple messages per UDP packet allowed"
 * (ICD §4), separated by {@code \n}; a trailing {@code \r} is tolerated, empty lines are skipped.
 */
final class DatagramLines {

    private DatagramLines() {}

    static List<String> split(byte[] data, int offset, int length) {
        String text = new String(data, offset, length, StandardCharsets.ISO_8859_1);
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n")) {
            String l = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (!l.isEmpty()) {
                lines.add(l);
            }
        }
        return lines;
    }
}
