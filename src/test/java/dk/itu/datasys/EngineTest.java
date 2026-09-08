package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EngineTest {
    @Test
    void teamName() {
        assertEquals("Team 1", new Engine().teamName());
    }
    @Test
    void demoPrintsGoldenResultsOnRepeatedRuns() throws Exception {
        PrintStream original = System.out;
        try {
            for (int run = 0; run < 2; run++) {
                var bytes = new ByteArrayOutputStream();
                System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
                Engine.main(new String[0]);
                String output = bytes.toString(StandardCharsets.UTF_8);
                assertTrue(output.contains("distance GREATER_THAN 100"));
                assertTrue(output.contains("city EQUALS Copenhagen"));
                assertTrue(output.contains("price LESS_THAN 50.0"));
                assertEquals(9, output.lines().filter(line -> line.startsWith("[")).count());
                assertTrue(output.contains("[Roskilde, 31, 45.0]"));
            }
        } finally {
            System.setOut(original);
        }
    }
}
