package dk.itu.datasys;

public sealed interface Statement
        permits CreateTableStatement, CopyStatement, SelectStatement { }
