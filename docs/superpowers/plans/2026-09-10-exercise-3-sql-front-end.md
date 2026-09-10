# Exercise 3 SQL Front-End Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `subagent-driven-development` (recommended) or `executing-plans` to implement this plan task-by-task. Before implementation, use `using-git-worktrees` to create an isolated feature worktree. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the complete Exercise 3 SQL text front end so the mini-DBMS can parse, bind, and pretty-print the required SQL subset without executing it.

**Architecture:** ANTLR generates lexer/parser classes in `dk.itu.datasys.sql`; handwritten public SQL APIs remain in `dk.itu.datasys`, alongside the existing storage vocabulary they reuse. `SqlParser` is the facade over generated code, `SqlAstBuilder` converts parse trees to immutable records, `Binder` performs catalog-aware semantic validation through `StorageEngine.schema`, and `SqlPrinter` emits normalized round-trippable SQL.

**Tech Stack:** Java 25, Maven, ANTLR 4.13.2, JUnit 6.1.2, SLF4J 2.0.18 with Log4j2.

**Spec:** `Exercises/Exercise3.md`

## Global Constraints

- Implement the full local Exercise 3 definition of done except GitHub CI configuration, pull-request review/merge, and the `v0.3` release tag.
- Do not execute SQL statements; planner/executor wiring is Exercise 4.
- Use ANTLR 4.13.2. Do not add another SQL-processing library.
- Every SQL statement accepted by the grammar must run unchanged in DuckDB.
- Generated sources stay in `target/generated-sources/antlr4` and must never be committed.
- Reuse `ColumnSpec`, `ColumnType`, and `Comparison`; do not duplicate the storage vocabulary.
- Support only `CREATE TABLE`, `COPY ... FROM`, and `SELECT *` with one optional `WHERE` comparison using `=`, `<`, or `>`.
- Keywords are case-insensitive. Identifier spelling is preserved, while table/column lookup and duplicate detection are case-sensitive.
- String literals are single-quoted ASCII without escaped quotes. Whole and decimal numbers may be negative and become exact `Long` and `Double` values.
- A script contains one or more semicolon-terminated statements. Whitespace and `--` line comments are skipped.
- Parser errors fail at the first lexer/parser error and expose a 1-based line plus 0-based column.
- Every `SqlParser.parse` call logs success at debug or failure at error.
- Use TDD for behavioral tasks: failing test, observed failure, minimal implementation, passing test.
- Commit each independently validated task with a focused Conventional Commit subject that references Exercise issue #4 and includes the required Copilot co-author trailer. Use `(#4)` at the end of every subject. Do not amend commits.

---

## File Map

### Build and grammar

- Modify `pom.xml` — declare ANTLR 4.13.2 runtime and code-generation plugin.
- Create `src/main/antlr4/dk/itu/datasys/sql/Sql.g4` — define the complete Exercise 3 grammar.

### SQL API and implementation

- Create `src/main/java/dk/itu/datasys/Statement.java` — sealed AST root.
- Create `src/main/java/dk/itu/datasys/CreateTableStatement.java` — table name and ordered immutable columns.
- Create `src/main/java/dk/itu/datasys/CopyStatement.java` — table name and CSV path.
- Create `src/main/java/dk/itu/datasys/SelectStatement.java` — table name and optional predicate.
- Create `src/main/java/dk/itu/datasys/Predicate.java` — column, comparison, and typed constant.
- Create `src/main/java/dk/itu/datasys/SqlParseException.java` — positioned parse failure.
- Create `src/main/java/dk/itu/datasys/SqlAstBuilder.java` — generated parse-tree to AST conversion.
- Create `src/main/java/dk/itu/datasys/SqlParser.java` — public parser facade, first-error handling, and logging.
- Create `src/main/java/dk/itu/datasys/Binder.java` — semantic/catalog validation.
- Create `src/main/java/dk/itu/datasys/SqlPrinter.java` — normalized SQL rendering.

### Existing runtime

- Modify `src/main/java/dk/itu/datasys/StorageEngine.java` — add ordered read-only schema lookup.
- Modify `src/main/java/dk/itu/datasys/Engine.java` — parse and print the four required statements.
- Modify `README.md` — describe the SQL front-end demo instead of the Exercise 2 query demo.

### Tests

- Create `src/test/java/dk/itu/datasys/SqlAstTest.java` — AST value/immutability contract.
- Create `src/test/java/dk/itu/datasys/SqlParserTest.java` — successful parsing, typed literals, casing, comments, and positioned failures.
- Create `src/test/java/dk/itu/datasys/SqlPrinterTest.java` — round trips for every statement shape.
- Create `src/test/java/dk/itu/datasys/BinderIT.java` — catalog-backed binding behavior on `@TempDir`.
- Modify `src/test/java/dk/itu/datasys/StorageEngineIT.java` — schema accessor behavior.
- Modify `src/test/java/dk/itu/datasys/EngineTest.java` — exact four-line demo output.

---

### Task 1: Generate the Exercise 3 Lexer and Parser

**Files:**
- Modify: `pom.xml`
- Create: `src/main/antlr4/dk/itu/datasys/sql/Sql.g4`

**Interfaces:**
- Consumes: Maven's existing `build/plugins` and `dependencies` sections.
- Produces: generated `dk.itu.datasys.sql.SqlLexer`, `SqlParser`, `SqlBaseVisitor<T>`, and parse-tree context types.

- [ ] **Step 1: Add ANTLR runtime and plugin configuration**

Add `<antlr.version>4.13.2</antlr.version>` beside the existing version properties, add this compile dependency, and add this plugin under `build/plugins`:

```xml
<dependency>
  <groupId>org.antlr</groupId>
  <artifactId>antlr4-runtime</artifactId>
  <version>${antlr.version}</version>
</dependency>
```

```xml
<plugin>
  <groupId>org.antlr</groupId>
  <artifactId>antlr4-maven-plugin</artifactId>
  <version>${antlr.version}</version>
  <configuration>
    <visitor>true</visitor>
  </configuration>
  <executions>
    <execution>
      <goals>
        <goal>antlr4</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

- [ ] **Step 2: Add the complete grammar**

Create `src/main/antlr4/dk/itu/datasys/sql/Sql.g4`:

```antlr
grammar Sql;

options { caseInsensitive = true; }

script      : (statement ';')+ EOF ;
statement   : createTable | copy | select ;

createTable : CREATE TABLE IDENTIFIER '(' columnDef (',' columnDef)* ')' ;
columnDef   : IDENTIFIER columnType ;
columnType  : STRING | LONG | DOUBLE ;

copy        : COPY IDENTIFIER FROM STRING_LITERAL ;

select      : SELECT '*' FROM IDENTIFIER (WHERE predicate)? ;
predicate   : IDENTIFIER comparison=('=' | '<' | '>') literal ;
literal     : STRING_LITERAL | LONG_LITERAL | DOUBLE_LITERAL ;

CREATE : 'CREATE' ;
TABLE  : 'TABLE' ;
COPY   : 'COPY' ;
FROM   : 'FROM' ;
SELECT : 'SELECT' ;
WHERE  : 'WHERE' ;
STRING : 'STRING' ;
LONG   : 'LONG' ;
DOUBLE : 'DOUBLE' ;

IDENTIFIER     : [A-Z_] [A-Z_0-9]* ;
LONG_LITERAL   : '-'? [0-9]+ ;
DOUBLE_LITERAL : '-'? [0-9]+ '.' [0-9]+ ;
STRING_LITERAL : '\'' ~['\r\n\u0080-\u{10FFFF}]* '\'' ;
LINE_COMMENT   : '--' ~[\r\n]* -> skip ;
WS             : [ \t\r\n]+ -> skip ;
```

Keyword rules remain before `IDENTIFIER`. ANTLR longest-match behavior distinguishes `12.0` from `12` even though `LONG_LITERAL` is declared first.

- [ ] **Step 3: Generate sources and inspect their package**

Run:

```bash
mvn -B generate-sources
```

Expected: success, including generated `SqlLexer.java`, `SqlParser.java`, `SqlBaseVisitor.java`, and `SqlVisitor.java` beneath `target/generated-sources/antlr4/dk/itu/datasys/sql/`.

- [ ] **Step 4: Confirm generated code is ignored**

Run:

```bash
git status --short
git ls-files 'target/generated-sources/antlr4/**'
```

Expected: only `pom.xml` and `src/main/antlr4/dk/itu/datasys/sql/Sql.g4` are untracked/modified; `git ls-files` prints nothing.

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/main/antlr4/dk/itu/datasys/sql/Sql.g4
git commit -m "build: generate SQL parser with ANTLR (#4)" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 2: Define the Typed Immutable AST

**Files:**
- Create: `src/main/java/dk/itu/datasys/Statement.java`
- Create: `src/main/java/dk/itu/datasys/CreateTableStatement.java`
- Create: `src/main/java/dk/itu/datasys/CopyStatement.java`
- Create: `src/main/java/dk/itu/datasys/SelectStatement.java`
- Create: `src/main/java/dk/itu/datasys/Predicate.java`
- Test: `src/test/java/dk/itu/datasys/SqlAstTest.java`

**Interfaces:**
- Consumes: existing `ColumnSpec`, `ColumnType`, and `Comparison`.
- Produces:
  - `sealed interface Statement`
  - `CreateTableStatement(String tableName, List<ColumnSpec> columns)`
  - `CopyStatement(String tableName, String csvFilePath)`
  - `SelectStatement(String tableName, Optional<Predicate> where)`
  - `Predicate(String columnName, Comparison comparison, Object constant)`

- [ ] **Step 1: Write the failing AST contract test**

Create `src/test/java/dk/itu/datasys/SqlAstTest.java`:

```java
package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SqlAstTest {
    @Test void recordsProvideStructuralEquality() {
        var predicate = new Predicate("distance", Comparison.GREATER_THAN, 100L);
        assertEquals(new SelectStatement("trips", Optional.of(predicate)),
                new SelectStatement("trips", Optional.of(predicate)));
        assertEquals(new CopyStatement("trips", "trips.csv"),
                new CopyStatement("trips", "trips.csv"));
    }

    @Test void createTableOwnsAnImmutableColumnList() {
        var source = new ArrayList<>(List.of(new ColumnSpec("city", ColumnType.STRING)));
        var statement = new CreateTableStatement("trips", source);
        source.clear();
        assertEquals(List.of(new ColumnSpec("city", ColumnType.STRING)), statement.columns());
        assertThrows(UnsupportedOperationException.class,
                () -> statement.columns().add(new ColumnSpec("distance", ColumnType.LONG)));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
mvn -B -Dtest=SqlAstTest test
```

Expected: compilation fails because the AST types do not exist.

- [ ] **Step 3: Add the AST types**

Create the five files:

```java
// Statement.java
package dk.itu.datasys;

public sealed interface Statement
        permits CreateTableStatement, CopyStatement, SelectStatement { }
```

```java
// CreateTableStatement.java
package dk.itu.datasys;

import java.util.List;

public record CreateTableStatement(String tableName, List<ColumnSpec> columns)
        implements Statement {
    public CreateTableStatement {
        columns = List.copyOf(columns);
    }
}
```

```java
// CopyStatement.java
package dk.itu.datasys;

public record CopyStatement(String tableName, String csvFilePath)
        implements Statement { }
```

```java
// SelectStatement.java
package dk.itu.datasys;

import java.util.Optional;

public record SelectStatement(String tableName, Optional<Predicate> where)
        implements Statement { }
```

```java
// Predicate.java
package dk.itu.datasys;

public record Predicate(String columnName, Comparison comparison, Object constant) { }
```

- [ ] **Step 4: Run the AST test**

Run:

```bash
mvn -B -Dtest=SqlAstTest test
```

Expected: both tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dk/itu/datasys/{Statement,CreateTableStatement,CopyStatement,SelectStatement,Predicate}.java \
  src/test/java/dk/itu/datasys/SqlAstTest.java
git commit -m "feat: define typed SQL statement AST (#4)" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 3: Expose Ordered Catalog Schemas

**Files:**
- Modify: `src/main/java/dk/itu/datasys/StorageEngine.java`
- Modify: `src/test/java/dk/itu/datasys/StorageEngineIT.java`

**Interfaces:**
- Consumes: private `StorageEngine.table(String)` and `CatalogStore.Table.columns()`.
- Produces: `public synchronized List<ColumnSpec> schema(String tableName)`.

- [ ] **Step 1: Write the failing integration test**

Add this method to `StorageEngineIT`:

```java
@Test void exposesOrderedReadOnlySchemaAndRejectsUnknownTables() {
    var engine = new StorageEngine(dir);
    engine.createTable("Trips", schema);

    var actual = engine.schema("Trips");
    assertEquals(schema, actual);
    assertThrows(UnsupportedOperationException.class,
            () -> actual.add(new ColumnSpec("extra", ColumnType.STRING)));
    assertThrows(IllegalArgumentException.class, () -> engine.schema("trips"));
    assertThrows(IllegalArgumentException.class, () -> engine.schema("missing"));
}
```

This explicitly locks in case-sensitive lookup.

- [ ] **Step 2: Run the integration test to verify it fails**

Run:

```bash
mvn -B -Dit.test=StorageEngineIT verify
```

Expected: test compilation fails because `StorageEngine.schema` does not exist.

- [ ] **Step 3: Add the accessor**

Add immediately before `select` in `StorageEngine`:

```java
/** Returns the table schema in column order. */
public synchronized List<ColumnSpec> schema(String tableName) {
    return List.copyOf(table(tableName).columns());
}
```

The existing `table` helper supplies the required `IllegalArgumentException` and exact-name lookup.

- [ ] **Step 4: Run the integration test**

Run:

```bash
mvn -B -Dit.test=StorageEngineIT verify
```

Expected: all `StorageEngineIT` tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dk/itu/datasys/StorageEngine.java \
  src/test/java/dk/itu/datasys/StorageEngineIT.java
git commit -m "feat: expose table schemas for binding (#4)" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 4: Parse SQL into the Typed AST

**Files:**
- Create: `src/main/java/dk/itu/datasys/SqlParseException.java`
- Create: `src/main/java/dk/itu/datasys/SqlAstBuilder.java`
- Create: `src/main/java/dk/itu/datasys/SqlParser.java`
- Test: `src/test/java/dk/itu/datasys/SqlParserTest.java`

**Interfaces:**
- Consumes: generated `dk.itu.datasys.sql.SqlLexer`, generated `dk.itu.datasys.sql.SqlParser`, `SqlBaseVisitor<Object>`, and all AST types from Task 2.
- Produces:
  - `public List<Statement> SqlParser.parse(String sqlText)`
  - `public int SqlParseException.line()`
  - `public int SqlParseException.column()`

- [ ] **Step 1: Write successful parsing tests**

Create `src/test/java/dk/itu/datasys/SqlParserTest.java` with these imports, field, and success tests:

```java
package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SqlParserTest {
    final SqlParser parser = new SqlParser();

    @Test void parsesEveryStatementShape() {
        assertEquals(List.of(new CreateTableStatement("trips", List.of(
                        new ColumnSpec("city", ColumnType.STRING),
                        new ColumnSpec("distance", ColumnType.LONG),
                        new ColumnSpec("price", ColumnType.DOUBLE)))),
                parser.parse("CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);"));
        assertEquals(List.of(new CopyStatement("trips", "trips.csv")),
                parser.parse("COPY trips FROM 'trips.csv';"));
        assertEquals(List.of(new SelectStatement("trips", Optional.empty())),
                parser.parse("SELECT * FROM trips;"));
        assertEquals(List.of(new SelectStatement("trips", Optional.of(
                        new Predicate("distance", Comparison.GREATER_THAN, 100L)))),
                parser.parse("SELECT * FROM trips WHERE distance > 100;"));
    }

    @Test void parsesTypedPositiveAndNegativeLiterals() {
        var statements = parser.parse("""
                SELECT * FROM trips WHERE distance = 12;
                SELECT * FROM trips WHERE price = 12.0;
                SELECT * FROM trips WHERE city = '12';
                SELECT * FROM trips WHERE distance = -1;
                SELECT * FROM trips WHERE price = -1.5;
                """);
        assertEquals(List.of(12L, 12.0, "12", -1L, -1.5),
                statements.stream()
                        .map(SelectStatement.class::cast)
                        .map(SelectStatement::where)
                        .map(Optional::orElseThrow)
                        .map(Predicate::constant)
                        .toList());
    }

    @Test void treatsKeywordsCaseInsensitivelyAndPreservesIdentifiers() {
        assertEquals(List.of(new SelectStatement("Trips_2026", Optional.of(
                        new Predicate("Distance", Comparison.LESS_THAN, 5L)))),
                parser.parse("select * from Trips_2026 where Distance < 5;"));
    }

    @Test void skipsCommentsAndWhitespaceAcrossAWholeScript() {
        assertEquals(List.of(
                        new SelectStatement("trips", Optional.empty()),
                        new CopyStatement("trips", "trips.csv")),
                parser.parse("""
                        -- first statement
                          SELECT * FROM trips; -- trailing comment

                        COPY trips
                        FROM 'trips.csv';
                        """));
    }
```

- [ ] **Step 2: Add positioned failure tests to the same class**

Append these methods before the class's closing brace:

```java
    @Test void rejectsMalformedInputAtTheFirstError() {
        assertParseError("SELECT * FROM trips", 1, 19);
        assertParseError("CREATE TABLE trips (city STRING;", 1, 31);
        assertParseError("CREATE TABLE trips (city TEXT);", 1, 25);
        assertParseError("COPY trips FROM 'trips.csv;", 1, 16);
        assertParseError("SELECT * trips;", 1, 9);
    }

    @Test void rejectsEmptyScriptsAndNonAsciiStrings() {
        assertParseError("-- no statements\n", 2, 0);
        assertParseError("COPY trips FROM 'ø.csv';", 1, 16);
        assertParseError("COPY trips FROM '😀.csv';", 1, 16);
    }

    private void assertParseError(String sql, int line, int column) {
        var error = assertThrows(SqlParseException.class, () -> parser.parse(sql));
        assertEquals(line, error.line());
        assertEquals(column, error.column());
    }
}
```

- [ ] **Step 3: Run the parser test to verify it fails**

Run:

```bash
mvn -B -Dtest=SqlParserTest test
```

Expected: compilation fails because the facade, builder, and exception do not exist.

- [ ] **Step 4: Implement the positioned exception**

Create `src/main/java/dk/itu/datasys/SqlParseException.java`:

```java
package dk.itu.datasys;

public final class SqlParseException extends RuntimeException {
    private final int line;
    private final int column;

    SqlParseException(String message, int line, int column, Throwable cause) {
        super(message, cause);
        this.line = line;
        this.column = column;
    }

    public int line() { return line; }

    public int column() { return column; }
}
```

- [ ] **Step 5: Implement the one-class AST visitor**

Create `src/main/java/dk/itu/datasys/SqlAstBuilder.java`:

```java
package dk.itu.datasys;

import dk.itu.datasys.sql.SqlBaseVisitor;
import dk.itu.datasys.sql.SqlParser.*;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.antlr.v4.runtime.Token;

final class SqlAstBuilder extends SqlBaseVisitor<Object> {
    List<Statement> build(ScriptContext context) {
        return context.statement().stream()
                .map(this::visit)
                .map(Statement.class::cast)
                .toList();
    }

    @Override public Object visitCreateTable(CreateTableContext context) {
        var columns = context.columnDef().stream()
                .map(this::visit)
                .map(ColumnSpec.class::cast)
                .toList();
        return new CreateTableStatement(context.IDENTIFIER().getText(), columns);
    }

    @Override public Object visitColumnDef(ColumnDefContext context) {
        var type = ColumnType.valueOf(
                context.columnType().getText().toUpperCase(Locale.ROOT));
        return new ColumnSpec(context.IDENTIFIER().getText(), type);
    }

    @Override public Object visitCopy(CopyContext context) {
        return new CopyStatement(context.IDENTIFIER().getText(),
                unquote(context.STRING_LITERAL().getText()));
    }

    @Override public Object visitSelect(SelectContext context) {
        var where = context.predicate() == null
                ? Optional.<Predicate>empty()
                : Optional.of((Predicate) visit(context.predicate()));
        return new SelectStatement(context.IDENTIFIER().getText(), where);
    }

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

    private static String unquote(String text) {
        return text.substring(1, text.length() - 1);
    }
}
```

- [ ] **Step 6: Implement the fail-fast parser facade and logging**

Create `src/main/java/dk/itu/datasys/SqlParser.java`:

```java
package dk.itu.datasys;

import dk.itu.datasys.sql.SqlLexer;
import java.util.List;
import org.antlr.v4.runtime.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SqlParser {
    private static final Logger LOGGER = LoggerFactory.getLogger(SqlParser.class);
    private static final ANTLRErrorListener ERRORS = new ThrowingErrorListener();

    /** Parses a whole script of semicolon-terminated statements. */
    public List<Statement> parse(String sqlText) {
        long start = System.nanoTime();
        try {
            if (sqlText == null) {
                throw new SqlParseException("SQL text is required", 1, 0, null);
            }
            var lexer = new SqlLexer(CharStreams.fromString(sqlText));
            lexer.removeErrorListeners();
            lexer.addErrorListener(ERRORS);

            var generated = new dk.itu.datasys.sql.SqlParser(
                    new CommonTokenStream(lexer));
            generated.removeErrorListeners();
            generated.addErrorListener(ERRORS);

            var statements = new SqlAstBuilder().build(generated.script());
            LOGGER.debug("statements={} durationMs={}", statements.size(), elapsed(start));
            return statements;
        } catch (SqlParseException error) {
            LOGGER.error("failed line={} col={} durationMs={}",
                    error.line(), error.column(), elapsed(start));
            throw error;
        }
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static final class ThrowingErrorListener extends BaseErrorListener {
        @Override public void syntaxError(Recognizer<?, ?> recognizer,
                                          Object offendingSymbol,
                                          int line,
                                          int charPositionInLine,
                                          String message,
                                          RecognitionException cause) {
            throw new SqlParseException(
                    "line " + line + ":" + charPositionInLine + " " + message,
                    line, charPositionInLine, cause);
        }
    }
}
```

- [ ] **Step 7: Run the parser test**

Run:

```bash
mvn -B -Dtest=SqlParserTest test
```

Expected: all parser tests pass. If an asserted malformed-input coordinate differs, inspect the first offending token and correct the implementation rather than weakening the line/column assertion.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/dk/itu/datasys/{SqlParseException,SqlAstBuilder,SqlParser}.java \
  src/test/java/dk/itu/datasys/SqlParserTest.java
git commit -m "feat: parse SQL scripts into typed statements (#4)" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 5: Bind Statements Against the Catalog

**Files:**
- Create: `src/main/java/dk/itu/datasys/Binder.java`
- Test: `src/test/java/dk/itu/datasys/BinderIT.java`

**Interfaces:**
- Consumes: `StorageEngine.schema(String)`, the sealed AST, and package-visible `ColumnType.accepts(Object)`.
- Produces:
  - `public Binder(StorageEngine engine)`
  - `public void bind(Statement statement)`

- [ ] **Step 1: Write catalog-backed binding tests**

Create `src/test/java/dk/itu/datasys/BinderIT.java`:

```java
package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BinderIT {
    @TempDir Path dir;
    StorageEngine engine;
    Binder binder;

    @BeforeEach void createCatalog() {
        engine = new StorageEngine(dir);
        engine.createTable("trips", List.of(
                new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("distance", ColumnType.LONG),
                new ColumnSpec("price", ColumnType.DOUBLE)));
        binder = new Binder(engine);
    }

    @Test void bindsEveryValidStatementShape() {
        assertDoesNotThrow(() -> binder.bind(new CreateTableStatement("new_table",
                List.of(new ColumnSpec("id", ColumnType.LONG)))));
        assertDoesNotThrow(() -> binder.bind(new CopyStatement("trips", "missing.csv")));
        assertDoesNotThrow(() -> binder.bind(
                new SelectStatement("trips", Optional.empty())));
        assertDoesNotThrow(() -> binder.bind(new SelectStatement("trips",
                Optional.of(new Predicate("distance", Comparison.GREATER_THAN, 10L)))));
    }

    @Test void rejectsUnknownTablesAndColumns() {
        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(new CopyStatement("missing", "input.csv")));
        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(new SelectStatement("missing", Optional.empty())));
        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(new SelectStatement("trips", Optional.of(
                        new Predicate("missing", Comparison.EQUALS, 1L)))));
    }

    @Test void rejectsMismatchedConstants() {
        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(new SelectStatement("trips", Optional.of(
                        new Predicate("distance", Comparison.EQUALS, "x")))));
    }

    @Test void validatesCreateColumnsButDefersExistingTableChecks() {
        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(new CreateTableStatement("empty", List.of())));
        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(new CreateTableStatement("duplicate", List.of(
                        new ColumnSpec("city", ColumnType.STRING),
                        new ColumnSpec("city", ColumnType.LONG)))));
        assertDoesNotThrow(() -> binder.bind(new CreateTableStatement("trips",
                List.of(new ColumnSpec("id", ColumnType.LONG)))));
    }

    @Test void matchesIdentifiersCaseSensitively() {
        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(new SelectStatement("Trips", Optional.empty())));
        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(new SelectStatement("trips", Optional.of(
                        new Predicate("Distance", Comparison.EQUALS, 1L)))));
        assertDoesNotThrow(() -> binder.bind(new CreateTableStatement("mixed", List.of(
                new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("City", ColumnType.STRING)))));
    }
}
```

- [ ] **Step 2: Run the integration test to verify it fails**

Run:

```bash
mvn -B -Dit.test=BinderIT verify
```

Expected: test compilation fails because `Binder` does not exist.

- [ ] **Step 3: Implement the binder**

Create `src/main/java/dk/itu/datasys/Binder.java`:

```java
package dk.itu.datasys;

import java.util.HashSet;
import java.util.Objects;

public final class Binder {
    private final StorageEngine engine;

    public Binder(StorageEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /** Validates a statement against the catalog. */
    public void bind(Statement statement) {
        switch (statement) {
            case CreateTableStatement create -> bindCreate(create);
            case CopyStatement copy -> engine.schema(copy.tableName());
            case SelectStatement select -> bindSelect(select);
        }
    }

    private void bindCreate(CreateTableStatement statement) {
        if (statement.columns().isEmpty()) {
            throw new IllegalArgumentException("Table must have at least one column");
        }
        var names = new HashSet<String>();
        for (var column : statement.columns()) {
            if (!names.add(column.name())) {
                throw new IllegalArgumentException("Duplicate column: " + column.name());
            }
        }
    }

    private void bindSelect(SelectStatement statement) {
        var schema = engine.schema(statement.tableName());
        if (statement.where().isEmpty()) return;

        var predicate = statement.where().orElseThrow();
        var column = schema.stream()
                .filter(candidate -> candidate.name().equals(predicate.columnName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown column: " + predicate.columnName()));
        if (!column.type().accepts(predicate.constant())) {
            throw new IllegalArgumentException(
                    "Invalid constant type for " + column.type());
        }
    }
}
```

- [ ] **Step 4: Run binder and storage integration tests**

Run:

```bash
mvn -B -Dit.test=BinderIT,StorageEngineIT verify
```

Expected: both integration-test classes pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dk/itu/datasys/Binder.java \
  src/test/java/dk/itu/datasys/BinderIT.java
git commit -m "feat: bind SQL statements against the catalog (#4)" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 6: Pretty-Print Round-Trippable SQL

**Files:**
- Create: `src/main/java/dk/itu/datasys/SqlPrinter.java`
- Test: `src/test/java/dk/itu/datasys/SqlPrinterTest.java`

**Interfaces:**
- Consumes: all AST variants and storage enums.
- Produces: `public String SqlPrinter.print(Statement statement)`.

- [ ] **Step 1: Write round-trip tests for every statement shape**

Create `src/test/java/dk/itu/datasys/SqlPrinterTest.java`:

```java
package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SqlPrinterTest {
    final SqlParser parser = new SqlParser();
    final SqlPrinter printer = new SqlPrinter();

    @Test void printsAndRoundTripsEveryStatementShape() {
        var statements = List.<Statement>of(
                new CreateTableStatement("Trips", List.of(
                        new ColumnSpec("City", ColumnType.STRING),
                        new ColumnSpec("distance", ColumnType.LONG),
                        new ColumnSpec("price", ColumnType.DOUBLE))),
                new CopyStatement("Trips", "input.csv"),
                new SelectStatement("Trips", Optional.empty()),
                new SelectStatement("Trips", Optional.of(
                        new Predicate("distance", Comparison.GREATER_THAN, 100L))),
                new SelectStatement("Trips", Optional.of(
                        new Predicate("City", Comparison.EQUALS, "Odense"))),
                new SelectStatement("Trips", Optional.of(
                        new Predicate("price", Comparison.LESS_THAN, -1.5))),
                new SelectStatement("Trips", Optional.of(
                        new Predicate("price", Comparison.EQUALS, 1.0e20))),
                new SelectStatement("Trips", Optional.of(
                        new Predicate("price", Comparison.EQUALS, 1.0e-7))),
                new SelectStatement("Trips", Optional.of(
                        new Predicate("price", Comparison.EQUALS, -0.0))));

        for (var statement : statements) {
            assertEquals(List.of(statement), parser.parse(printer.print(statement)));
        }
    }

    @Test void emitsStableNormalizedSql() {
        assertEquals("CREATE TABLE trips (city STRING, distance LONG);",
                printer.print(new CreateTableStatement("trips", List.of(
                        new ColumnSpec("city", ColumnType.STRING),
                        new ColumnSpec("distance", ColumnType.LONG)))));
        assertEquals("COPY trips FROM 'trips.csv';",
                printer.print(new CopyStatement("trips", "trips.csv")));
        assertEquals("SELECT * FROM trips;",
                printer.print(new SelectStatement("trips", Optional.empty())));
        assertEquals("SELECT * FROM trips WHERE distance = 12;",
                printer.print(new SelectStatement("trips", Optional.of(
                        new Predicate("distance", Comparison.EQUALS, 12L)))));
    }
}
```

- [ ] **Step 2: Run the printer test to verify it fails**

Run:

```bash
mvn -B -Dtest=SqlPrinterTest test
```

Expected: compilation fails because `SqlPrinter` does not exist.

- [ ] **Step 3: Implement normalized printing**

Create `src/main/java/dk/itu/datasys/SqlPrinter.java`:

```java
package dk.itu.datasys;

import java.math.BigDecimal;
import java.util.stream.Collectors;

public final class SqlPrinter {
    /** Renders a statement as SQL that parses to an equal statement. */
    public String print(Statement statement) {
        return switch (statement) {
            case CreateTableStatement create -> printCreate(create);
            case CopyStatement copy -> "COPY " + copy.tableName() + " FROM "
                    + quote(copy.csvFilePath()) + ";";
            case SelectStatement select -> printSelect(select);
        };
    }

    private static String printCreate(CreateTableStatement statement) {
        String columns = statement.columns().stream()
                .map(column -> column.name() + " " + column.type().name())
                .collect(Collectors.joining(", "));
        return "CREATE TABLE " + statement.tableName() + " (" + columns + ");";
    }

    private static String printSelect(SelectStatement statement) {
        String where = statement.where()
                .map(predicate -> " WHERE " + predicate.columnName() + " "
                        + operator(predicate.comparison()) + " "
                        + constant(predicate.constant()))
                .orElse("");
        return "SELECT * FROM " + statement.tableName() + where + ";";
    }

    private static String operator(Comparison comparison) {
        return switch (comparison) {
            case EQUALS -> "=";
            case LESS_THAN -> "<";
            case GREATER_THAN -> ">";
        };
    }

    private static String constant(Object value) {
        if (value instanceof String string) return quote(string);
        if (value instanceof Double number) return decimal(number);
        return value.toString();
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
        ColumnType.requireAscii(value);
        if (value.indexOf('\'') >= 0 || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("String cannot be represented by this SQL subset");
        }
        return "'" + value + "'";
    }
}
```

- [ ] **Step 4: Run parser and printer tests**

Run:

```bash
mvn -B -Dtest=SqlParserTest,SqlPrinterTest test
```

Expected: all parser and printer tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dk/itu/datasys/SqlPrinter.java \
  src/test/java/dk/itu/datasys/SqlPrinterTest.java
git commit -m "feat: pretty-print SQL statements (#4)" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 7: Replace the Demo with SQL Parse and Print

**Files:**
- Modify: `src/main/java/dk/itu/datasys/Engine.java`
- Modify: `src/test/java/dk/itu/datasys/EngineTest.java`
- Modify: `README.md`

**Interfaces:**
- Consumes: `SqlParser.parse(String)` and `SqlPrinter.print(Statement)`.
- Produces: no-argument `Engine.main` output containing exactly four normalized statements, one per line.

- [ ] **Step 1: Replace the golden-query assertion with exact SQL output**

Replace `demoPrintsGoldenResultsOnRepeatedRuns` in `EngineTest`:

```java
@Test
void demoParsesAndPrintsExerciseStatementsOnRepeatedRuns() {
    String expected = """
            CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
            COPY trips FROM 'trips.csv';
            SELECT * FROM trips WHERE distance > 100;
            SELECT * FROM trips;
            """;
    PrintStream original = System.out;
    try {
        for (int run = 0; run < 2; run++) {
            var bytes = new ByteArrayOutputStream();
            System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
            Engine.main(new String[0]);
            assertEquals(expected,
                    bytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
        }
    } finally {
        System.setOut(original);
    }
}
```

Remove the now-unused checked-exception declaration from the test.

- [ ] **Step 2: Run the engine test to verify it fails**

Run:

```bash
mvn -B -Dtest=EngineTest test
```

Expected: the test fails because the current demo prints Exercise 2 query results.

- [ ] **Step 3: Simplify `Engine.main` to parse and print**

Retain `teamName()` and replace the current storage demo and obsolete imports/helpers with:

```java
package dk.itu.datasys;

public final class Engine {
    public static void main(String[] args) {
        String sql = """
                CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
                COPY trips FROM 'trips.csv';
                SELECT * FROM trips WHERE distance > 100;
                SELECT * FROM trips;
                """;
        var printer = new SqlPrinter();
        new SqlParser().parse(sql).stream()
                .map(printer::print)
                .forEach(System.out::println);
    }

    String teamName() { return "Team 1"; }
}
```

- [ ] **Step 4: Update README demo wording**

Replace the opening command description with:

```markdown
Requires JDK 25 or newer and Maven. Run `mvn -B verify` for unit and integration
tests, or `mvn compile exec:java` to parse and pretty-print the four Exercise 3
SQL statements. The SQL front end parses and binds statements but does not
execute them yet.
```

Keep the storage API documentation below it because the underlying Exercise 2 API remains supported.

- [ ] **Step 5: Run engine and SQL tests**

Run:

```bash
mvn -B -Dtest=EngineTest,SqlParserTest,SqlPrinterTest test
```

Expected: all selected tests pass and the engine test observes exactly four lines.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dk/itu/datasys/Engine.java \
  src/test/java/dk/itu/datasys/EngineTest.java README.md
git commit -m "feat: print parsed SQL in the engine demo (#4)" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 8: Verify the Complete Local Definition of Done

**Files:**
- Verify only; change files only when a failing check identifies a defect.

**Interfaces:**
- Consumes: all preceding tasks and their commits.
- Produces: a clean, buildable Exercise 3 front end with persistent conventional commit history.

- [ ] **Step 1: Run all focused unit tests**

```bash
mvn -B -Dtest=SqlAstTest,SqlParserTest,SqlPrinterTest,EngineTest test
```

Expected: all selected unit tests pass.

- [ ] **Step 2: Run binder and storage integration tests**

```bash
mvn -B -Dit.test=BinderIT,StorageEngineIT verify
```

Expected: both integration-test classes pass; existing storage behavior remains intact.

- [ ] **Step 3: Run the complete Maven lifecycle**

```bash
mvn -B verify
```

Expected: all unit and integration tests pass.

- [ ] **Step 4: Verify the runnable demo**

Run:

```bash
mvn -q compile exec:java
```

Expected standard output:

```sql
CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
COPY trips FROM 'trips.csv';
SELECT * FROM trips WHERE distance > 100;
SELECT * FROM trips;
```

- [ ] **Step 5: Verify repository hygiene and commit structure**

Run:

```bash
git ls-files 'target/generated-sources/antlr4/**'
git status --short
git log --oneline --decorate -8
```

Expected: no generated ANTLR source is tracked; no task changes remain uncommitted; the implementation appears as focused `build:` and `feat:` commits whose subjects end in `(#4)`.

If verification exposes a defect, return to the task that owns the failing behavior, repeat its red-green cycle, and create a new focused `fix:` commit containing only that task's listed files and ending its subject in `(#4)`. Do not amend an earlier commit and do not create an empty verification commit.

---

## Dependency and Execution Order

1. Task 1 generates the classes consumed by Task 4.
2. Task 2 defines the AST consumed by Tasks 4, 5, and 6.
3. Task 3 defines the catalog API consumed by Task 5.
4. Task 4 follows Tasks 1 and 2.
5. Task 5 follows Tasks 2 and 3.
6. Task 6 follows Tasks 2 and 4 because its test proves parser/printer round trips.
7. Task 7 follows Tasks 4 and 6.
8. Task 8 follows all implementation tasks.

Tasks 1, 2, and 3 are logically independent. Run them concurrently only in separate Git worktrees and integrate their focused commits in dependency order; never allow concurrent agents to stage or commit in the same working tree. Otherwise execute the tasks sequentially with `subagent-driven-development`.

## Explicit Non-Goals

- SQL execution or mapping AST statements to `StorageEngine.createTable`, `copyFile`, or `select`.
- File-existence checks during `COPY` binding.
- Existing-table checks during `CREATE TABLE` binding.
- Escaped quotes, non-ASCII strings, empty scripts, projections other than `*`, compound predicates, nulls, aliases, joins, deletes, updates, aggregation, ordering, or additional comparison operators.
- GitHub Actions changes, pull requests, branch protection, review/merge work, or release tagging.
