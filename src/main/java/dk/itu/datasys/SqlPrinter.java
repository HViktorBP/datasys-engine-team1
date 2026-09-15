package dk.itu.datasys;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Renders supported SQL statement objects as normalized, parseable SQL text.
 *
 * <p>Output uses uppercase keywords and stable spacing while preserving identifier spelling.
 *
 * @author Team 1
 * @version 0.3
 * @see SqlParser
 * @since 0.3
 */
public final class SqlPrinter {
    /** Pattern for unquoted identifiers supported by the grammar. */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z_0-9]*");
    /** Reserved words that cannot be emitted as unquoted identifiers. */
    private static final Set<String> KEYWORDS = Set.of(
            "CREATE", "TABLE", "COPY", "FROM", "SELECT", "WHERE",
            "STRING", "LONG", "DOUBLE");

    /** Creates a normalized SQL printer. */
    public SqlPrinter() { }

    /**
     * Renders a statement as SQL that parses back to an equal statement.
     *
     * @param statement the statement to render
     * @return a semicolon-terminated SQL statement
     * @throws IllegalArgumentException if the statement is {@code null} or contains an identifier
     *                                  or literal not representable by the supported SQL subset
     */
    public String print(Statement statement) {
        if (statement == null) {
            throw new IllegalArgumentException("Statement cannot be null");
        }
        return switch (statement) {
            case CreateTableStatement create -> printCreate(create);
            case CopyStatement copy -> "COPY " + tableName(copy.tableName()) + " FROM "
                    + quote(copy.csvFilePath()) + ";";
            case SelectStatement select -> printSelect(select);
        };
    }

    /**
     * Renders a {@code CREATE TABLE} statement.
     *
     * @param statement the create-table statement
     * @return normalized SQL text
     */
    private static String printCreate(CreateTableStatement statement) {
        String columns = statement.columns().stream()
                .map(column -> columnName(column.name()) + " " + column.type().name())
                .collect(Collectors.joining(", "));
        return "CREATE TABLE " + tableName(statement.tableName())
                + " (" + columns + ");";
    }

    /**
     * Renders a {@code SELECT} statement and its optional predicate.
     *
     * @param statement the select statement
     * @return normalized SQL text
     */
    private static String printSelect(SelectStatement statement) {
        String where = statement.where()
                .map(predicate -> " WHERE " + columnName(predicate.columnName()) + " "
                        + operator(predicate.comparison()) + " "
                        + constant(predicate.constant()))
                .orElse("");
        return "SELECT * FROM " + tableName(statement.tableName()) + where + ";";
    }

    /**
     * Validates and returns a table identifier.
     *
     * @param value the table name
     * @return the validated identifier
     */
    private static String tableName(String value) {
        return identifier("Table", value);
    }

    /**
     * Validates and returns a column identifier.
     *
     * @param value the column name
     * @return the validated identifier
     */
    private static String columnName(String value) {
        return identifier("Column", value);
    }

    /**
     * Ensures a name is a non-keyword identifier supported by the grammar.
     *
     * @param kind the identifier kind used in error messages
     * @param value the candidate identifier
     * @return the validated identifier
     * @throws IllegalArgumentException if the value cannot be represented
     */
    private static String identifier(String kind, String value) {
        if (value == null || !IDENTIFIER.matcher(value).matches()
                || KEYWORDS.contains(value.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    kind + " name cannot be represented by this SQL subset: " + value);
        }
        return value;
    }

    /**
     * Converts a comparison to its SQL operator token.
     *
     * @param comparison the comparison to render
     * @return the operator token
     */
    private static String operator(Comparison comparison) {
        return switch (comparison) {
            case EQUALS -> "=";
            case LESS_THAN -> "<";
            case GREATER_THAN -> ">";
        };
    }

    /**
     * Renders a supported typed constant as a SQL literal.
     *
     * @param value the constant to render
     * @return normalized SQL literal text
     * @throws IllegalArgumentException if the value is null, unsupported, or not representable
     */
    private static String constant(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("SQL literal value cannot be null");
        }
        return switch (value) {
            case String string -> quote(string);
            case Long number -> number.toString();
            case Double number -> decimal(number);
            default -> throw new IllegalArgumentException(
                    "Unsupported SQL literal value: " + value);
        };
    }

    /**
     * Renders a finite double without exponent notation.
     *
     * @param value the double value
     * @return a decimal literal containing a decimal point
     * @throws IllegalArgumentException if the value is not finite
     */
    private static String decimal(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Non-finite doubles are not SQL literals");
        }
        String text = Double.toString(value);
        if (!text.contains("E") && !text.contains("e"))
            return text;
        String plain = BigDecimal.valueOf(value).toPlainString();
        return plain.contains(".") ? plain : plain + ".0";
    }

    /**
     * Wraps representable ASCII text in SQL single quotes.
     *
     * @param value the unquoted text
     * @return the quoted SQL literal
     * @throws IllegalArgumentException if the text is null or contains unsupported characters
     */
    private static String quote(String value) {
        if (value == null) {
            throw new IllegalArgumentException("String cannot be null");
        }
        ColumnType.requireAscii(value);
        if (value.indexOf('\'') >= 0 || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(
                    "String cannot be represented by this SQL subset");
        }
        return "'" + value + "'";
    }
}
