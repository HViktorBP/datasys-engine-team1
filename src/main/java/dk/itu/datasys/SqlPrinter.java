package dk.itu.datasys;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class SqlPrinter {
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z_][A-Za-z_0-9]*");
    private static final Set<String> KEYWORDS = Set.of(
            "CREATE", "TABLE", "COPY", "FROM", "SELECT", "WHERE",
            "STRING", "LONG", "DOUBLE");

    /** Renders a statement as SQL that parses back to an equal statement. */
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

    private static String printCreate(CreateTableStatement statement) {
        String columns = statement.columns().stream()
                .map(column -> columnName(column.name()) + " " + column.type().name())
                .collect(Collectors.joining(", "));
        return "CREATE TABLE " + tableName(statement.tableName())
                + " (" + columns + ");";
    }

    private static String printSelect(SelectStatement statement) {
        String where = statement.where()
                .map(predicate -> " WHERE " + columnName(predicate.columnName()) + " "
                        + operator(predicate.comparison()) + " "
                        + constant(predicate.constant()))
                .orElse("");
        return "SELECT * FROM " + tableName(statement.tableName()) + where + ";";
    }

    private static String tableName(String value) {
        return identifier("Table", value);
    }

    private static String columnName(String value) {
        return identifier("Column", value);
    }

    private static String identifier(String kind, String value) {
        if (value == null || !IDENTIFIER.matcher(value).matches()
                || KEYWORDS.contains(value.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    kind + " name cannot be represented by this SQL subset: " + value);
        }
        return value;
    }

    private static String operator(Comparison comparison) {
        return switch (comparison) {
            case EQUALS -> "=";
            case LESS_THAN -> "<";
            case GREATER_THAN -> ">";
        };
    }

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

    private static String decimal(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Non-finite doubles are not SQL literals");
        }
        String text = Double.toString(value);
        if (!text.contains("E") && !text.contains("e")) return text;
        String plain = BigDecimal.valueOf(value).toPlainString();
        return plain.contains(".") ? plain : plain + ".0";
    }

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
