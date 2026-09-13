package dk.itu.datasys;

/**
 * Represents a {@code COPY} statement that imports a CSV file into a table.
 *
 * @param tableName the destination table name
 * @param csvFilePath the source CSV file path
 * @author Team 1
 * @version 0.3
 * @since 0.3
 */
public record CopyStatement(String tableName, String csvFilePath)
        implements Statement { }
