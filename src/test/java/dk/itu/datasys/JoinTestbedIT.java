package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the Exercise 10 testbed through {@code ./engine -f} and compares sorted output with DuckDB.
 *
 * <p>The expected rows were produced by DuckDB with the procedure in {@code docs/exercise-10.md}
 * and sorted, because a hash join emits rows in probe order.
 */
class JoinTestbedIT {
    @TempDir Path dir;

    @Test
    void sortedOutputMatchesDuckDb() throws Exception {
        for (var file : List.of("setup.sql", "orders.csv", "products.csv", "customers.csv", "cities.csv")) {
            Files.copy(Path.of("src/test/resources/joins", file), dir.resolve(file));
        }
        assertEquals("", launch("setup.sql"));

        assertQuery("SELECT * FROM orders, customers WHERE o_custname = c_name;", """
                100,Anna,Bike,1,Anna,Copenhagen
                101,Bob,Helmet,2,Bob,Aarhus
                102,Carol,Bike,1,Carol,Copenhagen
                103,Anna,Helmet,1,Anna,Copenhagen
                """);
        assertQuery("""
                SELECT * FROM orders, customers, products
                WHERE o_custname = c_name AND o_prodname = p_name;""", """
                100,Anna,Bike,1,Anna,Copenhagen,Bike,2999.0
                101,Bob,Helmet,2,Bob,Aarhus,Helmet,299.0
                102,Carol,Bike,1,Carol,Copenhagen,Bike,2999.0
                103,Anna,Helmet,1,Anna,Copenhagen,Helmet,299.0
                """);
        assertQuery("""
                SELECT * FROM orders, customers, products, cities
                WHERE o_custname = c_name AND o_prodname = p_name AND c_city = ct_city;""", """
                100,Anna,Bike,1,Anna,Copenhagen,Bike,2999.0,Copenhagen,Denmark,1400000
                101,Bob,Helmet,2,Bob,Aarhus,Helmet,299.0,Aarhus,Denmark,290000
                102,Carol,Bike,1,Carol,Copenhagen,Bike,2999.0,Copenhagen,Denmark,1400000
                103,Anna,Helmet,1,Anna,Copenhagen,Helmet,299.0,Copenhagen,Denmark,1400000
                """);
        assertQuery("""
                SELECT * FROM orders, customers, products
                WHERE o_custname = c_name AND o_prodname = p_name AND c_city = 'Copenhagen';""", """
                100,Anna,Bike,1,Anna,Copenhagen,Bike,2999.0
                102,Carol,Bike,1,Carol,Copenhagen,Bike,2999.0
                103,Anna,Helmet,1,Anna,Copenhagen,Helmet,299.0
                """);
        assertQuery("SELECT * FROM customers c1, customers c2 WHERE c1.c_city = c2.c_city;", """
                Anna,Copenhagen,Anna,Copenhagen
                Anna,Copenhagen,Carol,Copenhagen
                Bob,Aarhus,Bob,Aarhus
                Carol,Copenhagen,Anna,Copenhagen
                Carol,Copenhagen,Carol,Copenhagen
                """);
    }

    private void assertQuery(String sql, String expectedSorted) throws Exception {
        Files.writeString(dir.resolve("query.sql"), sql + "\n");
        var rows = new ArrayList<>(launch("query.sql").lines().toList());
        rows.sort(null);
        assertEquals(expectedSorted, String.join("\n", rows) + "\n", sql);
    }

    private String launch(String script) throws Exception {
        var builder = new ProcessBuilder(Path.of("engine").toAbsolutePath().toString(), "-f", script)
                .directory(dir.toFile())
                .redirectOutput(dir.resolve("stdout.txt").toFile())
                .redirectError(dir.resolve("stderr.txt").toFile());
        builder.environment().put("ENGINE_JAVA_OPTS", "-Xmx128m");
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(25, TimeUnit.SECONDS), "launcher timed out");
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
        assertEquals(0, process.exitValue(), Files.readString(dir.resolve("stderr.txt")));
        return Files.readString(dir.resolve("stdout.txt"), StandardCharsets.UTF_8);
    }
}
