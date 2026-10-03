package io.swoc2.app.connections;

import static org.assertj.core.api.Assertions.assertThat;

import io.swoc2.app.connections.transport.LineReader;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LineReaderTest {

    @Test
    void splitsLinesHandlesCrlfAndDiscardsOversizedLines() throws Exception {
        String input = "A;1\r\nB;2\n\n" + "X".repeat(50) + "\nC;3\nunterminated";
        List<String> lines = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        new LineReader(20)
                .read(new ByteArrayInputStream(input.getBytes(StandardCharsets.ISO_8859_1)), lines::add, problems::add);

        assertThat(lines).containsExactly("A;1", "B;2", "C;3");
        assertThat(problems).hasSize(1);
    }

    @Test
    void decodesIso88591() throws Exception {
        List<String> lines = new ArrayList<>();
        new LineReader(100)
                .read(new ByteArrayInputStream(new byte[] {'T', ';', (byte) 0xE4, '\n'}), lines::add, p -> {});
        assertThat(lines).containsExactly("T;ä");
    }
}
