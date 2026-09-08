# datasys-engine-team1

How to Build Data Systems – Fall 2026. Team 1 query engine.

Requires JDK 25 or newer and Maven. Run `mvn -B verify` for unit and integration
tests, or `mvn compile exec:java` for the three golden-example queries. The demo
uses a fresh temporary directory and removes it afterward.

The storage API lives in `dk.itu.datasys`. For persistent use, construct
`new StorageEngine(Path.of("data"))`, create a table with ordered `ColumnSpec`
values, then call `copyFile` with a headerless ASCII CSV. The optional second
constructor argument sets the maximum partition size (default: 8).
`select` accepts exact `String`, `Long`, or `Double` constants and returns rows
in input order. `getLastScanStats()` reports the latest successful scan.

Use one writer engine per directory; reopen the engine to reload catalogs
written by another instance. Part 1 supports one successful copy per table.
I/O failures are surfaced as `UncheckedIOException`; invalid API arguments and
malformed CSV rows use `IllegalArgumentException`. Generated state belongs
under the Git-ignored `data/` directory. The format and publication rules are
specified in [the storage design](docs/storage-design.md).
