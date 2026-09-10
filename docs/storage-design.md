# Storage Design

1. **Catalog storage:** one catalog file or one per table? Which format: JSON,
   Java properties, or your own binary? Where on disk relative to the data
   directory?

   **Original answer:** Catalog with one file per table. Format: JSON. Catalog
   storage and files will be in repo but gitignored.

   **Explanation:** All catalog and binary data files live below the
   `dataDirectory` passed to `StorageEngine`. That directory is inside the
   repository, so this generated state must be Git-ignored.

   The directory layout is:

   ```text
   dataDirectory/
     catalog/
       <table-id>.json
     data/
       <table-id>-0.bin
   ```

   **Why:** A separate catalog per table keeps catalog updates isolated: adding
   or changing one table does not require rewriting metadata for every table.
   JSON is human-readable and easy to inspect during the exercise. Jackson is
   allowed for catalog JSON, while the actual table data remains in our own
   binary format. A generated table ID is used in filenames so a table name
   containing spaces or path separators cannot create an unsafe path. The
   original table name is stored inside the catalog and used as the lookup key
   after restart.

2. **Catalog contents:** per table, at least the schema and the list of data
   files and partitions that belong to it.

   **Original answer:** in the catalog file (min/max statistics should be added
   there as well)

   **Explanation:** To make the original answer concrete enough to implement,
   each table catalog contains:

   - the catalog format version;
   - a stable table ID and the original table name;
   - the ordered schema, including each column's name and `ColumnType`;
   - a list of relative binary data-file paths;
   - each file's ordered partitions and their row counts; and
   - every partition-column chunk's byte offset, byte length, minimum, and
     maximum.

   Exercise 2 Part 1 permits only one `copyFile` call per table, so the data-file
   list contains at most one entry for now. That entry points to one binary file
   containing all partitions from the copy.

   **Why:** `select` needs the schema to interpret bytes, the partition order to
   preserve input order, and offsets to seek directly to each column chunk.
   Keeping a list even in Part 1 avoids changing the catalog model when append
   support introduces additional data files later. One binary file per table
   copy is compact and avoids producing many small partition files.

   Catalogs are written through a temporary file in the same directory. The
   completed file is installed with an atomic replacement move when supported,
   with a same-directory replacement fallback. This prevents a partially
   written JSON document from appearing at the final catalog path.

3. **Where the min/max summaries live.** The requirement is only that they
   exist per column per partition and that `select` can consult them without
   reading the column data they describe. Three designs are defensible. A
   **footer** after the data is Parquet's choice and is natural for a
   single-pass writer. A **header** at the front is convenient for the reader,
   but the writer must buffer the partition or seek back to fill it in. **In
   the catalog only** means that pruning needs no data-file I/O at all, as in
   Snowflake and Iceberg, but a data file is then no longer self-describing.
   Pick one and justify it.

   **Original answer:** In the catalog only

   **Explanation:** Per-column, per-partition min/max summaries are stored only
   in the table's JSON catalog.

   Minima and maxima are stored as canonical strings and converted using the
   column's schema type: decimal text for `LONG`, `Double.toString` text for
   `DOUBLE`, and the original value for `STRING`.

   **Why:** `select` can decide whether to prune every partition by reading only
   the catalog; it does not need to open the binary file or read the predicate
   column first. Encoding statistics as strings avoids a JSON library narrowing
   a small long to an integer or changing numeric precision. The schema supplies
   the type needed to reconstruct each value. The trade-off is that the binary
   file is not fully self-describing, which is acceptable because the catalog
   is already required persistent state.

   The pruning rules for a range `[min, max]` are:

   - `EQUALS constant`: prune when `constant < min` or `constant > max`;
   - `LESS_THAN constant`: prune when `min >= constant`; and
   - `GREATER_THAN constant`: prune when `max <= constant`.

   In every other case the partition is read, because min/max can prove that a
   match is impossible but cannot prove that a particular matching value exists.

4. **Restart:** what does a fresh `StorageEngine` on the same directory have to
   read before it can answer a `select`?

   **Original answer:** Needs to read the schema - needs to know how the data
   looks (+ metadata files for optimisations)

   **Explanation:** On construction, the engine creates any missing catalog
   and data directories, reads all `catalog/*.json` files, validates their
   catalog versions, and indexes them by table name. It does not scan column
   data.

   When a query needs a registered binary file, the reader checks its magic
   bytes and format version before seeking to the chunk offsets stored in the
   catalog.

   **Why:** The catalogs already contain the schema, binary paths, partition
   order, row counts, chunk locations, and pruning metadata. Loading only these
   small metadata files makes restart cheap while still providing everything
   needed to plan a filtered scan. Checking the binary header when the file is
   opened fails clearly if the data is corrupt or uses an unsupported format.

5. **Layout inside a partition:** choose either row-wise or columnar format.

   **Original answer:** Columnar for now

   **Explanation:** Partitions are stored in original CSV order in a single
   binary file. Within each partition, one contiguous chunk is written for each
   column in schema order. The catalog records each chunk's offset and length.

   ```text
   file header
   partition 0: column 0 chunk | column 1 chunk | column 2 chunk
   partition 1: column 0 chunk | column 1 chunk | column 2 chunk
   ...
   ```

   **Why:** Values used by the same predicate are contiguous, and the catalog
   can address each partition-column combination directly. Grouping chunks by
   partition also makes it straightforward to reconstruct complete matching
   rows without coordinating separate files. A pruned partition causes no reads
   of any of its column chunks.

6. **Partition size:** maximum rows per partition, as a configurable parameter
   (your tests will use tiny values like 2; pick a sensible default).

   **Original answer:** 8 but will be changed pobably later on

   **Explanation:** The implementation defaults to eight rows per partition
   and adds two constructors:

   ```java
   public StorageEngine(Path dataDirectory)
   public StorageEngine(Path dataDirectory, int maxRowsPerPartition)
   ```

   The first uses `8`; the overload rejects values less than one. Each
   partition's actual row count is persisted in the catalog, but the configured
   maximum is not.

   **Why:** Eight is the team's chosen initial default and keeps demonstration
   files easy to inspect. The overload makes the setting testable with two-row
   partitions. Persisting actual row counts rather than the writer setting means
   an engine restarted with a different maximum can still read existing files.

   `copyFile` buffers at most one partition of rows. When the buffer reaches the
   maximum, it writes each column chunk, computes min/max, and records the
   metadata. This bounds working memory instead of loading the entire CSV.

7. **Value encodings and framing:** e.g. `LONG` as 8-byte two's-complement,
   `DOUBLE` as 8-byte IEEE 754, `STRING` as length-prefixed ASCII bytes; magic
   bytes and a format version number at the start of each file; how a reader
   finds a given partition's column chunk.

   **Original answer:** use industry standards

   **Explanation:** The broad original answer is made concrete with standard
   primitive encodings. Every binary file starts with this eight-byte header:

   | Offset | Size | Meaning |
   |---:|---:|---|
   | 0 | 4 | ASCII magic bytes `HTBD` |
   | 4 | 4 | signed format version, initially `1` |

   Values use the following encodings:

   - `LONG`: eight-byte signed two's-complement integer;
   - `DOUBLE`: eight-byte IEEE 754 binary64 value; and
   - `STRING`: four-byte non-negative byte length followed by that many strict
     ASCII bytes.

   A chunk has no repeated inline metadata. Its type comes from the schema; its
   file offset, byte length, and value count come from the catalog. The decoder
   rejects invalid string lengths, premature end-of-file, chunks extending past
   the file, unexpected trailing chunk bytes, incorrect magic bytes, and
   unsupported versions.

   **Why:** Fixed-width numeric encodings are conventional, exact, and support
   direct decoding. Length-prefixed strings support empty and variable-length
   values without separators or escaping. Keeping structural metadata in the
   catalog avoids duplicating it in every chunk while still providing explicit
   file framing and direct seeks.

   CSV files are headerless and parsed positionally with trailing empty fields
   preserved. Field count is checked before conversion. Strings must be ASCII,
   and numeric fields are parsed according to their exact `ColumnType`. Any
   malformed row fails the whole copy with an error naming the source file and
   one-based line number.

   The binary output is written to a temporary file and published only after
   the entire CSV succeeds. The engine then atomically updates the catalog to
   reference it. If catalog persistence fails, the newly published binary file
   is removed, so a partial or failed copy is never registered.

8. **Byte order:** `ByteBuffer` defaults to big-endian, while the machines you
   run on are little-endian. Pick one and document the choice.

   **Original answer:** Big-endian (keep in mind that DuckDB uses the
   little-endian)

   **Explanation:** Big-endian byte order is used for the file version, string
   lengths, longs, and doubles.

   **Why:** Big-endian matches the defaults of Java's `DataInput`, `DataOutput`,
   and `ByteBuffer`, reducing the chance that a reader and writer silently use
   different orders. Declaring it as part of the format makes files portable
   across host architectures. DuckDB uses little-endian, but this engine owns an
   independent format and does not exchange binary pages with DuckDB.

## API behavior, logging, and verification

The decisions above are implemented by small focused components:
`CatalogStore`, `BinaryColumnCodec`, `CsvRowParser`,
`PartitionStatistics`, and `PartitionPruner`, coordinated by `StorageEngine`.
Helpers remain package-private so unit tests in the same package can exercise
them without expanding the public API.

`createTable` persists an empty catalog and creates no data file. `copyFile`
supports one successful copy per table in Part 1. `select` requires an exact
constant type (`String`, `Long`, or `Double`), preserves schema and on-disk row
order, and publishes the latest successful result as
`ScanStats(partitionsTotal, partitionsRead, partitionsPruned)` through
`getLastScanStats()`.

Each `createTable`, `copyFile`, and `select` call emits a comma-free summary log.
Every min/max is logged where `copyFile` computes it, and every `READ` or
`PRUNED` decision is logged where `select` makes it. Dynamic values have commas
and line breaks replaced so the existing Log4j2 pattern always produces exactly
seven CSV fields.

Unit tests cover codec round trips, statistics, pruning, and CSV parsing.
Integration tests cover persistence, validation, all comparison/type
combinations, partition metadata, pruning statistics, and the golden example.
Maven Surefire runs `*Test` classes; Failsafe 3.5.6 runs `*IT` classes during
`verify`. The no-argument `Engine.main` uses isolated temporary files and prints
the three required golden queries so repeated demo runs do not conflict.
