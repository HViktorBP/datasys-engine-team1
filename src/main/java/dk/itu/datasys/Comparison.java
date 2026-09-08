package dk.itu.datasys;

public enum Comparison {
    EQUALS, LESS_THAN, GREATER_THAN;

    boolean matches(int order) {
        return switch (this) {
            case EQUALS -> order == 0;
            case LESS_THAN -> order < 0;
            case GREATER_THAN -> order > 0;
        };
    }
}
