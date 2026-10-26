package dk.itu.datasys;

import java.io.DataOutput;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Encodes and decodes version 1 binary data files in the engine's columnar format.
 *
 * <p>Each file starts with the four-byte {@code HTBD} magic and a four-byte signed version
 * number. All multi-byte fields are big-endian. {@link ColumnType#LONG LONG} uses an eight-byte
 * two's-complement signed integer, {@link ColumnType#DOUBLE DOUBLE} uses an eight-byte IEEE 754
 * binary64 value, and {@link ColumnType#STRING STRING} uses a four-byte non-negative length
 * followed by strict ASCII bytes.
 *
 * <p>Writers should pass a buffered {@link DataOutput}, and readers decode a whole chunk from
 * memory, because each call on an unbuffered {@link RandomAccessFile} is a separate system call.
 */
final class BinaryColumnCodec {
    /** Number of bytes occupied by the file magic and format version. */
    static final int HEADER_SIZE = 8;
    /** Magic number identifying a Team 1 binary data file. */
    private static final int MAGIC = 0x48544244; // HTBD
    /** Supported binary data format version. */
    private static final int VERSION = 1;

    /** Prevents utility-class instantiation. */
    private BinaryColumnCodec() { }

    /**
     * Writes the binary format magic and version, {@link #HEADER_SIZE} bytes in total.
     *
     * @param output the output to receive the header at its current position
     * @throws IOException if the header cannot be written
     */
    static void writeHeader(DataOutput output) throws IOException {
        output.writeInt(MAGIC);
        output.writeInt(VERSION);
    }

    /**
     * Verifies the header at the beginning of a binary data file.
     *
     * @param file the file whose header is checked
     * @throws IOException if the header cannot be read or is not a supported format
     */
    static void checkHeader(RandomAccessFile file) throws IOException {
        file.seek(0);
        if (file.readInt() != MAGIC) throw new IOException("Incorrect binary magic");
        if (file.readInt() != VERSION) throw new IOException("Unsupported binary version");
    }

    /**
     * Writes one typed value at the output's current position using the type's binary representation.
     *
     * @param output the destination output
     * @param type the column type that controls the encoding
     * @param value the value to encode
     * @return the number of bytes written, so callers can track chunk offsets and lengths
     * @throws IOException if the value cannot be written
     * @throws IllegalArgumentException if a string value contains non-ASCII characters
     */
    static int writeValue(DataOutput output, ColumnType type, Object value) throws IOException {
        return switch (type) {
            case LONG -> {
                output.writeLong((Long) value);
                yield Long.BYTES;
            }
            case DOUBLE -> {
                output.writeDouble((Double) value);
                yield Double.BYTES;
            }
            case STRING -> {
                String string = (String) value;
                ColumnType.requireAscii(string);
                byte[] bytes = string.getBytes(StandardCharsets.US_ASCII);
                output.writeInt(bytes.length);
                output.write(bytes);
                yield Integer.BYTES + bytes.length;
            }
        };
    }

    /**
     * Reads and validates a contiguous chunk of typed column values.
     *
     * <p>The chunk is read with one positioned read and decoded in memory.
     *
     * @param file the source file
     * @param type the column type that controls decoding
     * @param offset the absolute byte offset of the chunk
     * @param length the exact encoded length of the chunk
     * @param rowCount the number of values expected in the chunk
     * @return the decoded values in row order
     * @throws IOException if the bounds, encoding, or number of bytes are invalid, or the chunk
     *                     is larger than {@link Integer#MAX_VALUE} bytes
     */
    static Object[] readChunk(RandomAccessFile file, ColumnType type, long offset,
                              long length, int rowCount) throws IOException {
        long fileLength = file.length();
        if (offset < HEADER_SIZE || length < 0 || offset > fileLength
                || length > fileLength - offset || rowCount < 0) {
            throw new IOException("Invalid column chunk bounds");
        }
        int minimumWidth = type == ColumnType.STRING ? 4 : 8;
        if ((long) rowCount * minimumWidth > length) throw new IOException("Truncated column chunk");
        if (length > Integer.MAX_VALUE) throw new IOException("Column chunk too large");
        byte[] encoded = new byte[(int) length];
        file.seek(offset);
        file.readFully(encoded);
        ByteBuffer buffer = ByteBuffer.wrap(encoded); // big-endian, like RandomAccessFile
        Object[] values = new Object[rowCount];
        for (int i = 0; i < rowCount; i++) {
            if (buffer.remaining() < minimumWidth) throw new IOException("Truncated value");
            values[i] = switch (type) {
                case LONG -> buffer.getLong();
                case DOUBLE -> buffer.getDouble();
                case STRING -> {
                    int size = buffer.getInt();
                    if (size < 0 || size > buffer.remaining()) throw new IOException("Invalid string length");
                    byte[] bytes = new byte[size];
                    buffer.get(bytes);
                    for (byte b : bytes) if (b < 0) throw new IOException("Non-ASCII string in column chunk");
                    yield new String(bytes, StandardCharsets.US_ASCII);
                }
            };
        }
        if (buffer.hasRemaining()) throw new IOException("Unexpected trailing column chunk bytes");
        return values;
    }
}
