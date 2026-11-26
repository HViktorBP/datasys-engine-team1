package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EngineTest {
    @Test
    void noArgumentsPrintsTeamNameAndUsageOnRepeatedRuns() {
        PrintStream original = System.out;
        try {
            for (int run = 0; run < 2; run++) {
                var bytes = new ByteArrayOutputStream();
                System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
                Engine.main(new String[0]);
                String output = bytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
                assertTrue(output.contains("Team 1"));
                assertTrue(output.contains("-f"));
                assertTrue(output.contains("-j"));
                assertFalse(output.contains("CREATE TABLE trips"));
            }
        } finally {
            System.setOut(original);
        }
    }

    @Test
    void invalidJoinModeReportsAnErrorWithoutRunningSql() {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int status = Engine.run(new String[]{"-j", "bushy", "-c", "SELECT * FROM trips;"},
                Path.of("data"), new PrintStream(out), new PrintStream(err));
        assertEquals(1, status);
        assertEquals("", out.toString(StandardCharsets.UTF_8));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("optimized or syntax"));
    }
}
