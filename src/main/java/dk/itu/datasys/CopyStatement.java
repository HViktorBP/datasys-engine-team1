package dk.itu.datasys;

public record CopyStatement(String tableName, String csvFilePath)
        implements Statement { }
