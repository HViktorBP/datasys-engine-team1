package dk.itu.datasys;

/**
 * Runs the command-line demonstration for the exercise SQL statements.
 *
 * @author Team 1
 * @version 0.3
 * @since 0.1
 */
public final class Engine {
    /** Exercise statements parsed and rendered by the command-line demonstration. */
    private static final String EXERCISE_SQL = """
            CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
            COPY trips FROM 'trips.csv';
            SELECT * FROM trips WHERE distance > 100;
            SELECT * FROM trips;
            """;

    /** Creates the command-line demonstration entry point. */
    public Engine() { }

    /**
     * Parses and prints the built-in exercise statements.
     *
     * @param args unused command-line arguments
     */
    public static void main(String[] args) {
        var printer = new SqlPrinter();
        new SqlParser().parse(EXERCISE_SQL).stream()
                .map(printer::print)
                .forEach(System.out::println);
    }

    /**
     * Returns the team label used by the demonstration tests.
     *
     * @return the team label
     */
    String teamName() { return "Team 1"; }
}
