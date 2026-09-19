package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ResultCsvTest {
    @Test
    void writesUnquotedGoldenFieldsAndQuotesCommas() {
        var bytes = new ByteArrayOutputStream();
        var out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        ResultCsv.writeRow(out, new Object[]{"Copenhagen", 12L, 23.5});
        ResultCsv.writeRow(out, new Object[]{"a,b", "quote\"here"});
        ResultCsv.writeRow(out, new Object[]{-0.0});
        assertEquals("Copenhagen,12,23.5\n\"a,b\",\"quote\"\"here\"\n-0.0\n",
                bytes.toString(StandardCharsets.UTF_8));
    }
}
