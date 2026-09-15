package dk.itu.datasys;

import dk.itu.datasys.sql.SqlBaseVisitor;
import dk.itu.datasys.sql.SqlParser.ColumnDefContext;
import dk.itu.datasys.sql.SqlParser.CopyContext;
import dk.itu.datasys.sql.SqlParser.CreateTableContext;
import dk.itu.datasys.sql.SqlParser.LiteralContext;
import dk.itu.datasys.sql.SqlParser.PredicateContext;
import dk.itu.datasys.sql.SqlParser.ScriptContext;
import dk.itu.datasys.sql.SqlParser.SelectContext;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.antlr.v4.runtime.Token;

/** Converts ANTLR parse trees into the engine's immutable SQL statement model. */
final class SqlAstBuilder extends SqlBaseVisitor<Object> {
    /** Creates an abstract syntax tree builder. */
    SqlAstBuilder() { }

    /**
     * Builds the statements contained in a complete parsed script.
     *
     * @param context the root parse-tree context
     * @return the statements in source order
     */
    List<Statement> build(ScriptContext context) {
        return context.statement().stream()
                .map(this::visit)
                .map(Statement.class::cast)
                .toList();
    }

    /** {@inheritDoc} */
    @Override public Object visitCreateTable(CreateTableContext context) {
        var columns = context.columnDef().stream()
                .map(this::visit)
                .map(ColumnSpec.class::cast)
                .toList();
        return new CreateTableStatement(context.IDENTIFIER().getText(), columns);
    }

    /** {@inheritDoc} */
    @Override public Object visitColumnDef(ColumnDefContext context) {
        var type = ColumnType.valueOf(
                context.columnType().getText().toUpperCase(Locale.ROOT));
        return new ColumnSpec(context.IDENTIFIER().getText(), type);
    }

    /** {@inheritDoc} */
    @Override public Object visitCopy(CopyContext context) {
        return new CopyStatement(context.IDENTIFIER().getText(),
                unquote(context.STRING_LITERAL().getText()));
    }

    /** {@inheritDoc} */
    @Override public Object visitSelect(SelectContext context) {
        var where = context.predicate() == null
                ? Optional.<Predicate>empty()
                : Optional.of((Predicate) visit(context.predicate()));
        return new SelectStatement(context.IDENTIFIER().getText(), where);
    }

    /** {@inheritDoc} */
    @Override public Object visitPredicate(PredicateContext context) {
        var comparison = switch (context.comparison.getText()) {
            case "=" -> Comparison.EQUALS;
            case "<" -> Comparison.LESS_THAN;
            case ">" -> Comparison.GREATER_THAN;
            default -> throw new IllegalStateException(
                    "Unsupported comparison: " + context.comparison.getText());
        };
        return new Predicate(context.IDENTIFIER().getText(), comparison,
                visit(context.literal()));
    }

    /**
     * Converts a string, integer, or floating-point literal to its Java representation.
     *
     * @param context the literal parse-tree context
     * @return a {@link String}, {@link Long}, or {@link Double} value
     * @throws SqlParseException if a numeric literal is outside its supported range
     */
    @Override public Object visitLiteral(LiteralContext context) {
        Token token = context.getStart();
        try {
            if (context.STRING_LITERAL() != null) {
                return unquote(context.STRING_LITERAL().getText());
            }
            if (context.LONG_LITERAL() != null) {
                return Long.valueOf(context.LONG_LITERAL().getText());
            }
            return Double.valueOf(context.DOUBLE_LITERAL().getText());
        } catch (NumberFormatException error) {
            throw new SqlParseException("Invalid numeric literal: " + token.getText(),
                    token.getLine(), token.getCharPositionInLine(), error);
        }
    }

    /**
     * Removes the surrounding quotes from a grammar-validated string literal.
     *
     * @param text the quoted literal text
     * @return the unquoted contents
     */
    private static String unquote(String text) {
        return text.substring(1, text.length() - 1);
    }
}
