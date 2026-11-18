package dk.itu.datasys;

import dk.itu.datasys.sql.SqlBaseVisitor;
import dk.itu.datasys.sql.SqlParser.ColumnDefContext;
import dk.itu.datasys.sql.SqlParser.ColumnRefContext;
import dk.itu.datasys.sql.SqlParser.CopyContext;
import dk.itu.datasys.sql.SqlParser.DeleteContext;
import dk.itu.datasys.sql.SqlParser.CreateTableContext;
import dk.itu.datasys.sql.SqlParser.FilterContext;
import dk.itu.datasys.sql.SqlParser.JoinContext;
import dk.itu.datasys.sql.SqlParser.LiteralContext;
import dk.itu.datasys.sql.SqlParser.ScriptContext;
import dk.itu.datasys.sql.SqlParser.SelectContext;
import dk.itu.datasys.sql.SqlParser.TableRefContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.antlr.v4.runtime.Token;

/**
 * Converts ANTLR parse trees into the engine's immutable SQL statement model.
 *
 * @author Team 1
 * @since 0.3
 * @version 0.10
 */
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

    /**
     * Builds a select statement and sorts each {@code WHERE} condition into the filter list or
     * the join list, keeping source order within each list.
     *
     * @param context the select parse-tree context
     * @return the {@link SelectStatement}
     * @version 0.10
     */
    @Override public Object visitSelect(SelectContext context) {
        var tables = context.tableRef().stream()
                .map(this::visit)
                .map(TableRef.class::cast)
                .toList();
        var filters = new ArrayList<Predicate>();
        var joins = new ArrayList<JoinPredicate>();
        if (context.condition() != null) {
            for (var predicate : context.condition().predicate()) {
                switch (visit(predicate)) {
                    case Predicate filter -> filters.add(filter);
                    case JoinPredicate join -> joins.add(join);
                    default -> throw new IllegalStateException("Unexpected predicate");
                }
            }
        }
        return new SelectStatement(tables, filters, joins);
    }

    /**
     * Builds one {@code FROM}-list entry.
     *
     * @param context the table-reference parse-tree context
     * @return the {@link TableRef}
     * @since 0.10
     */
    @Override public Object visitTableRef(TableRefContext context) {
        var alias = Optional.ofNullable(context.alias).map(Token::getText);
        return new TableRef(context.table.getText(), alias);
    }

    /** {@inheritDoc} */
    @Override public Object visitDelete(DeleteContext context) {
        var where = context.filter() == null
                ? Optional.<Predicate>empty()
                : Optional.of((Predicate) visit(context.filter()));
        return new DeleteStatement(context.IDENTIFIER().getText(), where);
    }

    /**
     * Builds a column-to-constant comparison.
     *
     * @param context the filter parse-tree context
     * @return the {@link Predicate}
     * @since 0.10
     */
    @Override public Object visitFilter(FilterContext context) {
        var comparison = switch (context.comparison.getText()) {
            case "=" -> Comparison.EQUALS;
            case "<" -> Comparison.LESS_THAN;
            case ">" -> Comparison.GREATER_THAN;
            default -> throw new IllegalStateException(
                    "Unsupported comparison: " + context.comparison.getText());
        };
        return new Predicate((ColumnRef) visit(context.columnRef()), comparison,
                visit(context.literal()));
    }

    /**
     * Builds a column-to-column equality.
     *
     * @param context the join parse-tree context
     * @return the {@link JoinPredicate}
     * @since 0.10
     */
    @Override public Object visitJoin(JoinContext context) {
        return new JoinPredicate((ColumnRef) visit(context.left), (ColumnRef) visit(context.right));
    }

    /**
     * Builds a possibly qualified column reference.
     *
     * @param context the column-reference parse-tree context
     * @return the {@link ColumnRef}
     * @since 0.10
     */
    @Override public Object visitColumnRef(ColumnRefContext context) {
        var qualifier = Optional.ofNullable(context.qualifier).map(Token::getText);
        return new ColumnRef(qualifier, context.name.getText());
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
