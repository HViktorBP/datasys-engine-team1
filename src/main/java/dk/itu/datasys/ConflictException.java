package dk.itu.datasys;

/**
 * Reports that a changing statement lost a write conflict and left no visible change.
 *
 * <p>The statement worked on its table at the base version, but another statement committed in
 * the meantime and rewrote or removed a partition that this statement also rewrote or removed.
 * Nothing this statement wrote became reachable, so the caller can simply rerun it: the rerun
 * starts from the newest committed version.
 *
 * @author Team 1
 * @version 0.8
 * @since 0.8
 */
public final class ConflictException extends RuntimeException {
    /** Table whose commit lost the conflict. */
    private final String table;
    /** Table version the statement started from. */
    private final long baseVersion;
    /** Committed table version found at validation. */
    private final long currentVersion;

    /**
     * Creates a conflict for a statement that started from {@code baseVersion}.
     *
     * @param table the table whose commit lost the conflict
     * @param baseVersion the table version the statement started from
     * @param currentVersion the committed table version found at validation
     */
    ConflictException(String table, long baseVersion, long currentVersion) {
        super("conflict on table " + table + ": started from version " + baseVersion
                + " but version " + currentVersion + " is already committed");
        this.table = table;
        this.baseVersion = baseVersion;
        this.currentVersion = currentVersion;
    }

    /**
     * Returns the table whose commit lost the conflict.
     *
     * @return the table name
     */
    public String table() {
        return table;
    }

    /**
     * Returns the table version the losing statement started from.
     *
     * @return the base version
     */
    public long baseVersion() {
        return baseVersion;
    }

    /**
     * Returns the committed table version found when the losing statement validated.
     *
     * @return the current version
     */
    public long currentVersion() {
        return currentVersion;
    }
}
