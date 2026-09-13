package dk.itu.datasys;

/**
 * Represents a typed comparison predicate applied to a table column.
 *
 * @param columnName the name of the column to compare
 * @param comparison the comparison operator
 * @param constant the typed comparison value
 * @author Team 1
 * @version 0.3
 * @since 0.3
 */
public record Predicate(String columnName, Comparison comparison, Object constant) { }
