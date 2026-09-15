package dk.itu.datasys;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Encodes and decodes version 1 binary data files in the engine's columnar format.
 *
 * <p>Each file starts with the four-byte {@code HTBD} magic and a four-byte signed version
 * number. All multi-byte fields are big-endian. {@link ColumnType#LONG LONG} uses an eight-byte
 * two's-complement signed integer, {@link ColumnType#DOUBLE DOUBLE} uses an eight-byte IEEE 754
 * binary64 value, and {@link ColumnType#STRING STRING} uses a four-byte non-negative length
 * followed by strict ASCII bytes.
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
     * Writes the binary format magic and version at the current file position.
     *
     * @param file the file to receive the header
     * @throws IOException if the header cannot be written
     */
    static void writeHeader(RandomAccessFile file) throws IOException {
        file.writeInt(MAGIC);
        file.writeInt(VERSION);
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
     * Writes one typed value at the current file position using the type's binary representation.
     *
     * @param file the destination file
     * @param type the column type that controls the encoding
     * @param value the value to encode
     * @throws IOException if the value cannot be written
     * @throws IllegalArgumentException if a string value contains non-ASCII characters
     */
    static void writeValue(RandomAccessFile file, ColumnType type, Object value) throws IOException {
        switch (type) {
            case LONG -> file.writeLong((Long) value);
            case DOUBLE -> file.writeDouble((Double) value);
            case STRING -> {
                String string = (String) value;
                ColumnType.requireAscii(string);
                byte[] bytes = string.getBytes(StandardCharsets.US_ASCII);
                file.writeInt(bytes.length);
                file.write(bytes);
            }
        }
    }

    /**
     * Reads and validates a contiguous chunk of typed column values.
     *
     * @param file the source file
     * @param type the column type that controls decoding
     * @param offset the absolute byte offset of the chunk
     * @param length the exact encoded length of the chunk
     * @param rowCount the number of values expected in the chunk
     * @return the decoded values in row order
     * @throws IOException if the bounds, encoding, or number of bytes are invalid
     */
    static Object[] readChunk(RandomAccessFile file, ColumnType type, long offset,
                              long length, int rowCount) throws IOException {
        if (offset < HEADER_SIZE || length < 0 || offset > file.length()
                || length > file.length() - offset || rowCount < 0) {
            throw new IOException("Invalid column chunk bounds");
        }
        int minimumWidth = type == ColumnType.STRING ? 4 : 8;
        if ((long) rowCount * minimumWidth > length) throw new IOException("Truncated column chunk");
        long end = offset + length;
        file.seek(offset);
        Object[] values = new Object[rowCount];
        for (int i = 0; i < rowCount; i++) {
            if (end - file.getFilePointer() < minimumWidth) throw new IOException("Truncated value");
            values[i] = switch (type) {
                case LONG -> file.readLong();
                case DOUBLE -> file.readDouble();
                case STRING -> {
                    int size = file.readInt();
                    if (size < 0 || size > end - file.getFilePointer()) throw new IOException("Invalid string length");
                    byte[] bytes = new byte[size];
                    file.readFully(bytes);
                    for (byte b : bytes) if (b < 0) throw new IOException("Non-ASCII string in column chunk");
                    yield new String(bytes, StandardCharsets.US_ASCII);
                }
            };
        }
        if (file.getFilePointer() != end) throw new IOException("Unexpected trailing column chunk bytes");
        return values;
    }
}
