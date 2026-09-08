package dk.itu.datasys;

final class PartitionPruner {
    private PartitionPruner() { }

    static boolean canPrune(ColumnType type, PartitionStatistics statistics,
                            Comparison comparison, Object constant) {
        int minOrder = type.compare(type.parse(statistics.min()), constant);
        int maxOrder = type.compare(type.parse(statistics.max()), constant);
        return switch (comparison) {
            case EQUALS -> minOrder > 0 || maxOrder < 0;
            case LESS_THAN -> minOrder >= 0;
            case GREATER_THAN -> maxOrder <= 0;
        };
    }
}
