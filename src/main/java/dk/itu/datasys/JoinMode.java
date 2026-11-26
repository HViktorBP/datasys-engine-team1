package dk.itu.datasys;

import java.util.Locale;

/**
 * Selects whether the planner reorders joins or keeps the week-10 {@code FROM}-list plan.
 *
 * @author Team 1
 * @version 0.11
 * @since 0.11
 */
public enum JoinMode {
    /** Greedy smallest-intermediate-first order; the smaller estimated input builds. */
    OPTIMIZED,

    /** Left-deep {@code FROM}-list order; the result so far always builds. */
    SYNTAX;

    /**
     * Parses a {@code -j} flag value.
     *
     * @param text {@code optimized} or {@code syntax}
     * @return the matching mode
     * @throws IllegalArgumentException if {@code text} is not a supported value
     * @since 0.11
     */
    public static JoinMode parse(String text) {
        if ("optimized".equals(text)) {
            return OPTIMIZED;
        }
        if ("syntax".equals(text)) {
            return SYNTAX;
        }
        throw new IllegalArgumentException("Join mode must be optimized or syntax");
    }

    /**
     * Returns the {@code -j} spelling used in log lines.
     *
     * @return {@code optimized} or {@code syntax}
     * @since 0.11
     */
    @Override
    public String toString() {
        return name().toLowerCase(Locale.ROOT);
    }
}
