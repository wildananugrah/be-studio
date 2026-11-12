package com.mhamzah.gateway.validation;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * A configured regular expression matched against request data with two limits, so a pattern that backtracks badly
 * (e.g. {@code (a+)+$}) cannot hold a request thread (ReDoS): values longer than {@code maxInput} characters are not
 * matched at all, and a match that runs longer than {@code timeout} is stopped.
 */
public record BoundedPattern(Pattern pattern, int maxInput, Duration timeout) {

    /** {@code gateway.validation.pattern-max-input} / {@code pattern-timeout} defaults. */
    public static final int DEFAULT_MAX_INPUT = 4096;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMillis(50);

    public enum Outcome { MATCH, NO_MATCH, TOO_LONG, TIMEOUT }

    public BoundedPattern {
        if (maxInput <= 0) {
            throw new IllegalArgumentException("maxInput must be positive");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    /** Whether the whole of {@code text} matches. */
    public Outcome matches(String text) {
        if (text.length() > maxInput) {
            return Outcome.TOO_LONG;
        }
        try {
            return pattern.matcher(new Deadline(text, System.nanoTime() + timeout.toNanos())).matches()
                    ? Outcome.MATCH : Outcome.NO_MATCH;
        } catch (TimedOut e) {
            return Outcome.TIMEOUT;
        }
    }

    /**
     * The text, checking the clock as the regex engine reads it. Backtracking reads characters again and again, so a
     * runaway match keeps calling {@link #charAt} and is stopped there.
     */
    private static final class Deadline implements CharSequence {

        private final CharSequence text;
        private final long deadline;
        private int reads;

        Deadline(CharSequence text, long deadline) {
            this.text = text;
            this.deadline = deadline;
        }

        @Override
        public char charAt(int index) {
            if ((++reads & 0x3FF) == 0 && System.nanoTime() - deadline > 0) { // every 1,024 reads
                throw new TimedOut();
            }
            return text.charAt(index);
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new Deadline(text.subSequence(start, end), deadline);
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }

    private static final class TimedOut extends RuntimeException {
        TimedOut() {
            super(null, null, false, false); // no stack trace: it only ends the match
        }
    }
}
