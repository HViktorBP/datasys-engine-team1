package dk.itu.datasys;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/** Version 1 uses big-endian primitives and strict, length-prefixed ASCII. */
final class BinaryColumnCodec {
    static final int HEADER_SIZE = 8;
    private static final int MAGIC = 0x48544244; // HTBD
    private static final int VERSION = 1;

    private BinaryColumnCodec() { }

    static void writeHeader(RandomAccessFile file) throws IOException {
        file.writeInt(MAGIC);
        file.writeInt(VERSION);
    }

    static void checkHeader(RandomAccessFile file) throws IOException {
        file.seek(0);
        if (file.readInt() != MAGIC) throw new IOException("Incorrect binary magic");
        if (file.readInt() != VERSION) throw new IOException("Unsupported binary version");
    }

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
