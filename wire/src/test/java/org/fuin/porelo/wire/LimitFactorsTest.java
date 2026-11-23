package org.fuin.porelo.wire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LimitFactors}: the limits line every program reads the same way, held against
 * the cases published for a reader in another language.
 */
class LimitFactorsTest {

    private static Map<?, ?> cases() throws IOException {
        try (InputStream in =
                Objects.requireNonNull(
                        LimitFactors.class.getResourceAsStream(LimitFactors.CASES),
                        "the cases are published beside the class")) {
            return (Map<?, ?>)
                    Objects.requireNonNull(
                            Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        }
    }

    @Test
    void everyGoodLineOfThePublishedCasesReadsToItsFactorsAndWritesBack() throws IOException {
        final List<?> good = (List<?>) cases().get("good");
        assertThat(good).as("the cases hold good lines").isNotEmpty();
        for (final Object each : good) {
            final Map<?, ?> fields = (Map<?, ?>) each;
            final LimitFactors read = LimitFactors.parse((String) fields.get("line"));
            for (final String name : List.of("services", "machines", "polling", "retries")) {
                final BigDecimal factor =
                        switch (name) {
                            case "services" -> read.services();
                            case "machines" -> read.machines();
                            case "polling" -> read.polling();
                            default -> read.retries();
                        };
                assertThat(factor)
                        .as("%s of '%s'", name, fields.get("line"))
                        .isEqualByComparingTo(new BigDecimal((String) fields.get(name)));
            }
            assertThat(read.line())
                    .as("'%s' written back as porelod hands it on", fields.get("line"))
                    .isEqualTo(fields.get("written"));
            assertThat(LimitFactors.parse(read.line()))
                    .as("what is written reads back the same")
                    .isEqualTo(read);
        }
    }

    @Test
    void everyRefusedLineOfThePublishedCasesIsRefusedNamingWhatIsWrong() throws IOException {
        final List<?> refused = (List<?>) cases().get("refused");
        assertThat(refused).as("the cases hold refusals").isNotEmpty();
        for (final Object each : refused) {
            final Map<?, ?> fields = (Map<?, ?>) each;
            final String line = (String) fields.get("line");
            assertThatThrownBy(() -> LimitFactors.parse(line))
                    .as("'%s' is refused before anything is done", line)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining((String) fields.get("names"));
        }
    }

    @Test
    void eachFactorScalesOnlyItsOwnGroup() {
        final LimitFactors factors =
                LimitFactors.parse("services=2.0,machines=3.0,polling=0.5,retries=1.5");
        final Duration second = Duration.ofSeconds(1);

        assertThat(factors.services(second)).isEqualTo(Duration.ofSeconds(2));
        assertThat(factors.machines(second)).isEqualTo(Duration.ofSeconds(3));
        assertThat(factors.polling(second))
                .as("0.5 looks twice as often")
                .isEqualTo(Duration.ofMillis(500));
        assertThat(factors.retryPause(second)).isEqualTo(Duration.ofMillis(1500));
        assertThat(factors.tries(3)).as("4.5 tries round up to 5").isEqualTo(5);
    }

    @Test
    void aScaledLimitRoundsUpToAWholeMillisecondAndATryIsNeverLost() {
        final LimitFactors factors = LimitFactors.parse("services=0.1,retries=0.1");

        assertThat(factors.services(Duration.ofNanos(1)))
                .as("a limit is never scaled to nothing")
                .isEqualTo(Duration.ofMillis(1));
        assertThat(factors.services(Duration.ofMillis(15))).isEqualTo(Duration.ofMillis(2));
        assertThat(factors.tries(1)).as("a step is still tried once").isEqualTo(1);
        assertThat(factors.services(Duration.ofDays(365 * 1000L)))
                .as("a long limit is scaled without overflow")
                .isEqualTo(Duration.ofDays(36_500));
    }

    @Test
    void atOneNothingChanges() {
        final Duration limit = Duration.ofMillis(1234);

        assertThat(LimitFactors.DEFAULT.services(limit)).isEqualTo(limit);
        assertThat(LimitFactors.DEFAULT.machines(limit)).isEqualTo(limit);
        assertThat(LimitFactors.DEFAULT.polling(limit)).isEqualTo(limit);
        assertThat(LimitFactors.DEFAULT.retryPause(limit)).isEqualTo(limit);
        assertThat(LimitFactors.DEFAULT.tries(7)).isEqualTo(7);
        assertThat(LimitFactors.DEFAULT.line())
                .isEqualTo("services=1.0,machines=1.0,polling=1.0,retries=1.0");
    }

    @Test
    void aFactorBuiltInCodeIsHeldToTheSameRange() {
        assertThatThrownBy(
                        () ->
                                new LimitFactors(
                                        new BigDecimal("0.05"),
                                        BigDecimal.ONE,
                                        BigDecimal.ONE,
                                        BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("services 0.05 lies outside 0.1 to 100");
        assertThatThrownBy(
                        () ->
                                new LimitFactors(
                                        BigDecimal.ONE,
                                        new BigDecimal("1.005"),
                                        BigDecimal.ONE,
                                        BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than two digits");
        assertThatThrownBy(() -> LimitFactors.DEFAULT.tries(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
