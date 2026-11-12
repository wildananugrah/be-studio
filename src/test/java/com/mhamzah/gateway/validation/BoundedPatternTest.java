package com.mhamzah.gateway.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mhamzah.gateway.validation.BoundedPattern.Outcome;
import java.time.Duration;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Request data matched against a configured regex: a length limit and a time limit (ReDoS). */
class BoundedPatternTest {

    private static BoundedPattern bounded(String regex, int maxInput, long timeoutMs) {
        return new BoundedPattern(Pattern.compile(regex), maxInput, Duration.ofMillis(timeoutMs));
    }

    @Test
    void matchesTheWholeText() {
        BoundedPattern p = bounded("[0-9]{3}", 10, 50);
        assertThat(p.matches("123")).isEqualTo(Outcome.MATCH);
        assertThat(p.matches("1234")).isEqualTo(Outcome.NO_MATCH);
        assertThat(p.matches("")).isEqualTo(Outcome.NO_MATCH);
    }

    @Test
    void longerTextIsNotMatchedAtAll() {
        BoundedPattern p = bounded(".*", 5, 50);
        assertThat(p.matches("12345")).isEqualTo(Outcome.MATCH);
        assertThat(p.matches("123456")).isEqualTo(Outcome.TOO_LONG);
    }

    /** Both patterns take seconds or more without the limit (measured on Java 25: more than 3 s and about 2 s). */
    @Test
    void catastrophicBacktrackingIsStoppedNearTheTimeout() {
        long start = System.nanoTime();
        assertThat(bounded("(.*a){12}b", 4096, 50).matches("a".repeat(40))).isEqualTo(Outcome.TIMEOUT);
        assertThat(bounded("(.*,){11}P", 4096, 50).matches("1,".repeat(40))).isEqualTo(Outcome.TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
        assertThat(bounded("(.*a){12}b", 4096, 50).matches("a".repeat(12) + "b")).isEqualTo(Outcome.MATCH);
    }

    @Test
    void limitsMustBePositive() {
        assertThatThrownBy(() -> bounded("a", 0, 50)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bounded("a", 10, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
