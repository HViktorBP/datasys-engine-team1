package dk.itu.datasys;

public record Predicate(String columnName, Comparison comparison, Object constant) { }
