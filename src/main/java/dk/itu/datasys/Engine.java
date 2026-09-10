package dk.itu.datasys;

public final class Engine {
    private static final String EXERCISE_SQL = """
            CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
            COPY trips FROM 'trips.csv';
            SELECT * FROM trips WHERE distance > 100;
            SELECT * FROM trips;
            """;

    public static void main(String[] args) {
        var printer = new SqlPrinter();
        new SqlParser().parse(EXERCISE_SQL).stream()
                .map(printer::print)
                .forEach(System.out::println);
    }

    String teamName() { return "Team 1"; }
}
