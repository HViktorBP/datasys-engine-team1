package dk.itu.datasys;

/**
 * Marks an abstract syntax tree node for a supported top-level SQL statement.
 *
 * @author Team 1
 * @version 0.3
 * @since 0.3
 */
public sealed interface Statement
        permits CreateTableStatement, CopyStatement, SelectStatement { }
