package org.fuin.porelo.organization.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Guards against a write check that cannot fail: a store taking what it must refuse would pass an
 * adapter as sound. Each stand-in breaks one requirement, and exactly its checks must go red.
 */
class BrokenWritesStandInsTest {

    @ParameterizedTest
    @EnumSource(WriteBreak.class)
    void aBrokenStoreFailsExactlyTheChecksOfWhatItBreaks(final WriteBreak what) throws Exception {
        final Set<String> red = new TreeSet<>();
        for (final Check<WritesFixture> check : WritesContract.checks()) {
            try {
                check.body().run(new WritesStandIn(what));
            } catch (final AssertionError failed) {
                red.add(check.name());
            } catch (final Refused setupStopped) {
                // The break stopped what the check builds on, which says nothing of the check.
                continue;
            }
        }

        assertThat(red)
                .as("the write checks that %s must fail", what)
                .containsExactlyInAnyOrderElementsOf(what.checks);
    }

    @Test
    void everyCheckIsSeenToFailAgainstAStandIn() {
        final Set<String> seen =
                Arrays.stream(WriteBreak.values())
                        .flatMap(what -> what.checks.stream())
                        .collect(Collectors.toSet());

        assertThat(seen)
                .as("a check no stand-in makes fail is not known to be able to")
                .containsExactlyInAnyOrderElementsOf(
                        WritesContract.checks().stream().map(Check::name).toList());
    }
}
