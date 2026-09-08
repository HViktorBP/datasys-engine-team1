package dk.itu.datasys;

public enum ColumnType {
    STRING, LONG, DOUBLE;

    Object parse(String value) {
        return switch (this) {
            case STRING -> { requireAscii(value); yield value; }
            case LONG -> Long.parseLong(value);
            case DOUBLE -> Double.parseDouble(value);
        };
    }

    boolean accepts(Object value) {
        return value != null && value.getClass() == switch (this) {
            case STRING -> String.class;
            case LONG -> Long.class;
            case DOUBLE -> Double.class;
        };
    }

    int compare(Object left, Object right) {
        return switch (this) {
            case STRING -> ((String) left).compareTo((String) right);
            case LONG -> Long.compare((Long) left, (Long) right);
            // Signed zeros compare numerically equal while retaining their encoded
            // sign. Double.compare supplies a consistent order for NaN/infinities.
            case DOUBLE -> {
                double a = (Double) left;
                double b = (Double) right;
                yield a == b ? 0 : Double.compare(a, b);
            }
        };
    }

    static void requireAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 127) throw new IllegalArgumentException("Non-ASCII value");
        }
    }
}
