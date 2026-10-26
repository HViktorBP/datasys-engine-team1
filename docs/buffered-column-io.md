# Buffered column I/O

Partition files used to be written and read one value at a time through an unbuffered
`RandomAccessFile`, which made almost every value a separate system call. This document
describes the problem, how it was measured, and the fix.

## The problem

`RandomAccessFile` has no buffer: every `writeLong`, `writeInt`, `readLong` and
`getFilePointer` call goes to the operating system.

- **Writes:** `BinaryColumnCodec.writeValue` wrote each value directly to the file. A
  `trips` row (`STRING`, `LONG`, `DOUBLE`) cost about four `write` calls.
- **Reads:** `BinaryColumnCodec.readChunk` read each value separately and called
  `getFilePointer()` (an `lseek`) for its bounds checks, about eight system calls per row.

The cost grows with the number of values, not with the number of partitions. This is why
the Project 1 experiment found durations proportional to rows and almost flat across
partition sizes from 256 to 65,536 rows.

## How it was measured

[`scripts/profile-io.sh`](../scripts/profile-io.sh) profiles one import and one
`SELECT * FROM trips WHERE distance > 900` on a generated dataset. Each workload runs in a
fresh JVM, launched like the experiment runner launches it, under Java Flight Recorder.
For each workload it reports:

- the engine's own statement `durationMs` from `logs/engine.log`;
- user and system (kernel) CPU time of the process, which does not depend on the profiler;
- the most frequent top stack frames, separately for Java-code and native-code samples,
  because JFR samples them at different intervals (10 ms and 20 ms);
- the share of sampled thread time spent in `RandomAccessFile` calls, weighted by those
  intervals.

```bash
mvn -B package
python3 scripts/experiment.py generate --output data/profile-datasets --sizes 50MB
scripts/profile-io.sh data/profile-datasets/50MB-shuffled.csv 4096
```

The evidence (recordings, logs, database) stays in a temporary directory printed by the
script. A high kernel share together with `RandomAccessFile.writeBytes0`, `readBytes0` or
`getFilePointer` as the top native frames is the signature of this problem.

These are single runs with a warm filesystem cache and the profiler attached, on one
machine. They are exploratory evidence, not a controlled experiment.

## The fix

Values are still encoded the same way; only how bytes reach the file changed. The binary
format is unchanged, so existing catalogs and partition files stay readable.

- **Writes:** `StorageEngine.stagePartition` writes each partition file through a
  `DataOutputStream` over a 64 KB `BufferedOutputStream`. `BinaryColumnCodec.writeHeader`
  and `writeValue` accept any `DataOutput`, and `writeValue` returns the number of bytes
  it wrote. A stream cannot report its position, so `writePartition` computes chunk
  offsets from these counts, starting at the header size, since every file holds one
  partition.
- **Reads:** `BinaryColumnCodec.readChunk` reads the whole chunk with one positioned
  `readFully` and decodes the values from a `ByteBuffer`, which is big-endian like
  `RandomAccessFile`. All bounds and encoding checks remain, using the buffer's remaining
  bytes instead of `getFilePointer()`.

`DELETE` reads candidate partitions through `ScanOperator` and writes survivors through
`stagePartition`, so it benefits from both changes.

## Results

50 MB shuffled dataset (1,785,714 rows), Apple M3 Pro, macOS 26.6.2, OpenJDK 25.0.4.1,
measured on 2026-10-26 with `scripts/profile-io.sh`; one run each:

| Rows per partition | Workload | Before (`df1a5eb`) | After |
|---|---|---|---|
| 4,096 | Import | 10,404 ms, 68% kernel | 707 ms, 6% kernel |
| 4,096 | SELECT | 6,064 ms, 47% kernel | 386 ms, 12% kernel |
| 256 | Import | 16,430 ms, 72% kernel | 1,538 ms, 22% kernel |
| 256 | SELECT | 6,065 ms, 46% kernel | 666 ms, 15% kernel |
| 65,536 | Import | 10,974 ms, 69% kernel | 824 ms, 5% kernel |
| 65,536 | SELECT | 5,942 ms, 46% kernel | 805 ms, 10% kernel |

At 4,096 rows per partition, the share of time in `RandomAccessFile` calls fell from 93%
to 3% for the import and from 92% to 0% for the `SELECT`. The import's remaining time is
mostly CSV work: parsing doubles, the ASCII check and reading lines. Single runs vary;
repeating the 4,096-row run gave 680 ms for the import and 581 ms for the `SELECT`, so
read differences of tens of percent between runs as noise.

## Remaining costs

- **One file per partition.** Each partition file is created, opened, header-checked and
  closed separately. With 256 rows per partition, the 50 MB table has 6,976 files; the
  import is about twice as slow as at 4,096 rows, and the `SELECT` still spends about 25%
  of its time in `RandomAccessFile` calls, now opening files rather than reading values.
- **Result output.** `System.out` flushes at every newline, so `SELECT` makes one `write`
  call per output row. This is visible in the profile but small compared with reading.
- **Large partitions.** `SELECT` on shuffled data is slower at 65,536 rows than at 4,096,
  possibly from decoding a whole partition into memory at once. This is unconfirmed.
