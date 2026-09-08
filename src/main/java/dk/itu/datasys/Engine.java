package dk.itu.datasys;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public final class Engine {
    private static final Logger LOGGER = LoggerFactory.getLogger(Engine.class);

    public static void main(String[] args) throws IOException {
        var previousContext = MDC.getCopyOfContextMap();
        MDC.put("sessionId", UUID.randomUUID().toString());
        MDC.put("statementNumber", "0");
        Path directory = Files.createTempDirectory("htbd-demo-");
        try {
            LOGGER.debug("engine started");
            Path csv = directory.resolve("trips.csv");
            Files.writeString(csv, """
                    Copenhagen,12,23.5
                    Aarhus,187,301.0
                    Odense,95,120.75
                    Copenhagen,140,210.0
                    Aalborg,210,340.5
                    Roskilde,31,45.0
                    Copenhagen,88,99.99
                    Esbjerg,299,450.25
                    """);
            StorageEngine storage = new StorageEngine(directory, 2);
            storage.createTable("trips", List.of(new ColumnSpec("city", ColumnType.STRING),
                    new ColumnSpec("distance", ColumnType.LONG), new ColumnSpec("price", ColumnType.DOUBLE)));
            storage.copyFile("trips", csv.toString());
            printQuery(storage, "distance", Comparison.GREATER_THAN, 100L);
            printQuery(storage, "city", Comparison.EQUALS, "Copenhagen");
            printQuery(storage, "price", Comparison.LESS_THAN, 50.0);
            LOGGER.debug("engine stopped");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            } finally {
                MDC.clear();
                if (previousContext != null) MDC.setContextMap(previousContext);
            }
        }
    }

    private static void printQuery(StorageEngine storage, String column, Comparison comparison, Object constant) {
        System.out.println(column + " " + comparison + " " + constant);
        for (Object[] row : storage.select("trips", column, comparison, constant)) {
            System.out.println(Arrays.toString(row));
        }
    }

    String teamName() { return "Team 1"; }
}
