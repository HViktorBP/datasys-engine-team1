package dk.itu.datasys;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * Renders statements as normalized SQL, so tests can check that {@code parse(print(s))} equals
 * {@code s}.
 *
 * <p>The printer works from the AST alone, never from the original text, so a successful round
 * trip shows that the parser captured the whole statement. It lives in test sources because the
 * engine itself never prints SQL (Exercise 5 removed code unreachable from {@code main}). It
 * assumes the statement is representable: identifiers are valid and strings contain no quote.
 *
 * @author Team 1
 * @version 0.10
 * @since 0.3
 */
final class SqlPrinter {
    private SqlPrinter() { }

    /**
     * Renders a statement as one semicolon-terminated SQL statement.
     *
     * @param statement the statement to render
     * @return normalized SQL text with uppercase keywords
     */
    static String print(Statement statement) {
        return switch (statement) {
            case CreateTableStatement create -> "CREATE TABLE " + create.tableName() + " ("
                    + create.columns().stream()
                            .map(column -> column.name() + " " + column.type())
                            .collect(Collectors.joining(", "))
                    + ");";
            case CopyStatement copy -> "COPY " + copy.tableName() + " FROM '" + copy.csvFilePath() + "';";
            case DeleteStatement delete -> "DELETE FROM " + delete.tableName()
                    + delete.where().map(filter -> " WHERE " + filter(filter)).orElse("") + ";";
            case SelectStatement select -> printSelect(select);
        };
    }

    /**
     * Renders a select statement, printing joins before filters.
     *
     * @param select the select statement
     * @return normalized SQL text
     */
    private static String printSelect(SelectStatement select) {
        String tables = select.tables().stream()
                .map(table -> table.tableName() + table.alias().map(alias -> " " + alias).orElse(""))
                .collect(Collectors.joining(", "));
        var conditions = new ArrayList<String>();
        select.joins().forEach(join -> conditions.add(join.toString()));
        select.filters().forEach(filter -> conditions.add(filter(filter)));
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        return "SELECT * FROM " + tables + where + ";";
    }

    /**
     * Renders a column-to-constant comparison.
     *
     * @param filter the comparison
     * @return SQL text such as {@code c1.c_city = 'Aarhus'}
     */
    private static String filter(Predicate filter) {
        String operator = switch (filter.comparison()) {
            case EQUALS -> "=";
            case LESS_THAN -> "<";
            case GREATER_THAN -> ">";
        };
        return filter.column() + " " + operator + " " + literal(filter.constant());
    }

    /**
     * Renders a constant as a SQL literal; doubles never use exponent notation, which the grammar
     * cannot parse.
     *
     * @param value a {@link String}, {@link Long} or finite {@link Double}
     * @return the literal text
     */
    private static String literal(Object value) {
        return switch (value) {
            case String text -> "'" + text + "'";
            case Double number when Double.toString(number).contains("E") -> {
                String plain = BigDecimal.valueOf(number).toPlainString();
                yield plain.contains(".") ? plain : plain + ".0";
            }
            default -> value.toString();
        };
    }
}
