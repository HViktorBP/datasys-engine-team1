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
    void demoParsesAndPrintsExerciseStatementsOnRepeatedRuns() {
        String expected = """
                CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
                COPY trips FROM 'trips.csv';
                SELECT * FROM trips WHERE distance > 100;
                SELECT * FROM trips;
                """;
        PrintStream original = System.out;
        try {
            for (int run = 0; run < 2; run++) {
                var bytes = new ByteArrayOutputStream();
                System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
                Engine.main(new String[0]);
                assertEquals(expected,
                        bytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
            }
        } finally {
            System.setOut(original);
        }
    }
}
