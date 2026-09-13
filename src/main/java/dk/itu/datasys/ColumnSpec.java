package dk.itu.datasys;

/**
 * Describes a named, typed column in a table schema.
 *
 * @param name the column name
 * @param type the values accepted by the column
 * @author Team 1
 * @version 0.3
 * @since 0.2
 */
public record ColumnSpec(String name, ColumnType type) { }
