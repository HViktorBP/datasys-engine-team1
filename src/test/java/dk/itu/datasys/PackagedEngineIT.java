package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the shaded JAR and launcher in a separate working directory.
 *
 * @author Team 1
 * @version 0.5
 * @since 0.5
 */
class PackagedEngineIT {
    @TempDir Path dir;

    @Test
    void launcherRunsCommandsAndScriptsAndReadsItsLiveLog() throws Exception {
        assertTrue(launch().contains("Team 1"));
        Files.writeString(dir.resolve("trips.csv"), "Odense,95\nAarhus,187\n");
        assertEquals("", launch("-c", "CREATE TABLE trips (city STRING, distance LONG)"));
        Files.writeString(dir.resolve("script.sql"), """
                COPY trips FROM 'trips.csv';
                SELECT * FROM trips WHERE city = 'Odense';
                SELECT * FROM trips WHERE distance > 100;
                SELECT * FROM trips WHERE distance < 100;
                SELECT * FROM trips WHERE city = 'Aarhus';
                SELECT * FROM trips WHERE distance = 95;
                SELECT * FROM missing_packaged_table;
                """);
        assertEquals("Odense,95\nAarhus,187\nOdense,95\nAarhus,187\nOdense,95\n",
                launch(1, "-f", "script.sql"));
        String failure = Files.readAllLines(dir.resolve("logs/engine.log")).stream()
                .filter(line -> line.contains(",ERROR,") && line.contains("missing_packaged_table"))
                .findFirst().orElseThrow();
        String session = failure.split(",", -1)[1];
        assertEquals("7", failure.split(",", -1)[2]);

        assertEquals("", launch("-c", "CREATE TABLE logs (timestamp STRING, sessionId STRING, "
                + "statementNumber LONG, threadId LONG, logLevel STRING, className STRING, logMessage STRING)"));
        assertEquals("", launch("-c", "COPY logs FROM 'logs/engine.log'"));
        String errors = launch("-c", "SELECT * FROM logs WHERE logLevel = 'ERROR'");
        assertEquals(failure + "\n", errors);
        assertEquals(failure + "\n", launch("-c", "SELECT * FROM logs WHERE statementNumber = 7"));
        String sessionRows = launch("-c", "SELECT * FROM logs WHERE sessionId = '" + session + "'");
        assertTrue(sessionRows.contains(failure));
        assertTrue(sessionRows.contains("operation=start"));
        assertTrue(sessionRows.contains("operation=stop"));
    }

    @Test
    void commandFailureSanitizesQuotesCommasAndNewlinesInTheLog() throws Exception {
        String path = "missing,\"file\n.sql";
        assertEquals("", launch(1, "-f", path));
        var lines = Files.readAllLines(dir.resolve("logs/engine.log"));
        var errors = lines.stream().filter(line -> line.contains(",ERROR,")).toList();
        assertEquals(1, errors.size());
        assertEquals(7, errors.getFirst().split(",", -1).length);
        assertFalse(errors.getFirst().contains("\""));
        assertEquals("0", errors.getFirst().split(",", -1)[2]);
    }

    @Test
    void unicodeFailureDiagnosticsRemainIngestibleAsAsciiCsv() throws Exception {
        assertEquals("", launch(1, "-f", "missing-é-😀.sql"));
        assertEquals("", launch("-c", "CREATE TABLE logs (timestamp STRING, sessionId STRING, "
                + "statementNumber LONG, threadId LONG, logLevel STRING, className STRING, logMessage STRING)"));
        assertEquals("", launch("-c", "COPY logs FROM 'logs/engine.log'"));
        String errors = launch("-c", "SELECT * FROM logs WHERE logLevel = 'ERROR'");
        assertEquals(1, errors.lines().count());
        assertTrue(errors.chars().allMatch(character -> character < 128));
        assertTrue(errors.contains("missing-\\u00e9-\\ud83d\\ude00.sql"));
    }

    @Test
    void invalidArgumentsAndMalformedSqlFailWithoutCreatingTables() throws Exception {
        for (String[] args : List.of(new String[]{"-c"}, new String[]{"-f"},
                new String[]{"-x", "SELECT * FROM trips"},
                new String[]{"-c", "SELECT * trips"})) {
            assertEquals("", launch(1, args));
            assertFalse(Files.readString(dir.resolve("stderr.txt")).isBlank());
        }
        assertFalse(Files.exists(dir.resolve("data/catalog")));
    }

    /**
     * Runs the actual launcher and captures stdout without mixing in diagnostics.
     *
     * @param args the command-line arguments
     * @return normalized stdout
     * @throws Exception if the process cannot start, finish, or write captured output
     */
    private String launch(String... args) throws Exception {
        return launch(0, args);
    }

    /**
     * Captures a launcher invocation and checks its process exit status.
     *
     * @param expectedExit the expected process exit status
     * @param args the command-line arguments
     * @return normalized stdout
     * @throws Exception if process execution or captured output fails
     */
    private String launch(int expectedExit, String... args) throws Exception {
        Path launcher = Path.of("engine").toAbsolutePath();
        var command = new java.util.ArrayList<>(List.of(launcher.toString()));
        command.addAll(List.of(args));
        Path stdout = dir.resolve("stdout.txt");
        var builder = new ProcessBuilder(command).directory(dir.toFile())
                .redirectOutput(stdout.toFile()).redirectError(dir.resolve("stderr.txt").toFile());
        builder.environment().put("ENGINE_JAVA_OPTS", "-Xmx128m");
        Process process = builder.start();
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
                assertTrue(process.waitFor(25, TimeUnit.SECONDS), "launcher timed out");
            });
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
        assertEquals(expectedExit, process.exitValue(), Files.readString(dir.resolve("stderr.txt")));
        return Files.readString(stdout, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
