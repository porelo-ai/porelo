package org.fuin.porelo.organization.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Guards against a check that cannot fail: a store accepting what it must refuse would pass an
 * adapter as sound. Each stand-in breaks one requirement, and exactly its checks must go red.
 */
class BrokenStandInsTest {

    private static <F> Set<String> red(
            final List<Check<F>> checks, final java.util.function.Supplier<F> make)
            throws Exception {
        final Set<String> reds = new TreeSet<>();
        for (final Check<F> check : checks) {
            try {
                check.body().run(make.get());
            } catch (final AssertionError red) {
                reds.add(check.name());
            }
        }
        return reds;
    }

    @ParameterizedTest
    @EnumSource(Break.class)
    void aBrokenStoreFailsExactlyTheChecksOfWhatItBreaks(final Break what) throws Exception {
        final Set<String> organization =
                red(OrganizationContract.checks(), () -> new StandIn(what));
        final Set<String> offline = red(OfflineContract.checks(), () -> new StandIn(what));

        assertThat(organization)
                .as("the reading checks that %s must fail", what)
                .containsExactlyInAnyOrderElementsOf(what.organizationChecks);
        assertThat(offline)
                .as("the offline checks that %s must fail", what)
                .containsExactlyInAnyOrderElementsOf(what.offlineChecks);
    }

    @Test
    void everyCheckIsSeenToFailAgainstAStandIn() {
        final Set<String> reading =
                Arrays.stream(Break.values())
                        .flatMap(what -> what.organizationChecks.stream())
                        .collect(Collectors.toSet());
        final Set<String> offline =
                Arrays.stream(Break.values())
                        .flatMap(what -> what.offlineChecks.stream())
                        .collect(Collectors.toSet());

        assertThat(reading)
                .as("a reading check no stand-in fails proves nothing")
                .containsExactlyInAnyOrderElementsOf(names(OrganizationContract.checks()));
        assertThat(offline)
                .as("an offline check no stand-in fails proves nothing")
                .containsExactlyInAnyOrderElementsOf(names(OfflineContract.checks()));
    }

    private static <F> List<String> names(final List<Check<F>> checks) {
        return checks.stream().map(Check::name).toList();
    }
}
