package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineIT {
    @TempDir Path dir;

    @Test
    void scriptStdoutMatchesCommittedCsv() throws Exception {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        Engine.run(new String[]{"-f", "src/test/resources/front-door.sql"}, dir,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        String expected = Files.readString(Path.of("src/test/resources/front-door-expected.csv"))
                .replace("\r\n", "\n");
        assertEquals(expected, out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void failingScriptLeavesStdoutEmptyAndReportsStderr() throws Exception {
        Path script = dir.resolve("bad.sql");
        Files.writeString(script, "SELECT * FROM missing;\n");
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        Engine.run(new String[]{"-f", script.toString()}, dir,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals("", out.toString(StandardCharsets.UTF_8));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Unknown table"));
    }
}
