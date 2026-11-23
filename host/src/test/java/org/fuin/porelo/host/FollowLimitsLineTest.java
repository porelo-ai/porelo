package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.LimitFactors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The porelo a follow starts alone runs under porelo-host's factors, not at 1.0. */
class FollowLimitsLineTest {
    @TempDir Path directory;
    FollowFixture f;

    @BeforeEach
    void host() throws Exception {
        f = new FollowFixture(directory);
        f.migrated();
    }

    private List<String> isolatedUnder(final LimitFactors factors) throws Exception {
        return isolatedUnder(factors, null);
    }

    private List<String> isolatedUnder(
            final LimitFactors factors, final @org.jspecify.annotations.Nullable String user)
            throws Exception {
        new FollowContext(
                        f.host,
                        f.project.files,
                        f.runtime.limited(factors),
                        f.clock,
                        new PrintWriter(new StringWriter(), true))
                .isolated(
                        "porelo-host-org-fetch",
                        Map.of(),
                        List.of("org", "fetch"),
                        "the organization fetch",
                        "the organization fetch was refused",
                        new byte[0],
                        true,
                        false,
                        user);
        return f.commands.getLast();
    }

    @Test
    void theIsolatedPoreloIsHandedTheHostsLineBeforeItsVerb() throws Exception {
        final List<String> command = isolatedUnder(LimitFactors.parse("services=2"));

        final int porelo = command.indexOf(f.runtime.checked("porelo").toString());
        assertThat(porelo).as("porelo is started at all").isPositive();
        assertThat(command.subList(porelo + 1, command.size()))
                .containsExactly(
                        "--limits",
                        "services=2.0,machines=1.0,polling=1.0,retries=1.0",
                        "org",
                        "fetch");
    }

    @Test
    void aPersonsPoreloFollowsThatPersonsOwnSettings() throws Exception {
        final List<String> command = isolatedUnder(LimitFactors.parse("services=2"), "alice");

        final int porelo = command.indexOf(f.runtime.checked("porelo").toString());
        assertThat(porelo).as("porelo is started at all").isPositive();
        assertThat(command).as("it runs as the person").contains("User=alice");
        assertThat(command.subList(porelo + 1, command.size()))
                .as("the person's config.yml names the factors, never the host's")
                .containsExactly("org", "fetch");
    }

    @Test
    void atTheDefaultsTheLineIsStillWrittenWhole() throws Exception {
        final List<String> command = isolatedUnder(LimitFactors.DEFAULT);

        assertThat(
                        Collections.indexOfSubList(
                                command,
                                List.of(
                                        "--limits",
                                        "services=1.0,machines=1.0,polling=1.0,retries=1.0")))
                .isPositive();
    }
}
