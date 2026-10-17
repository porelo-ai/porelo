package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tests for {@link PoreloCli}. */
class PoreloCliTest {

    @Test
    void recognisesAWorkingDirectoryThatHasBeenRemoved() {

        // Unhandled, this reaches the operator as a 30-frame stack trace through sun.nio.
        assertThat(
                        PoreloCli.missingWorkingDirectory(
                                new Error(
                                        "Properties init: Could not determine current working directory.")))
                .isTrue();
    }

    @Test
    void looksThroughTheCauseChain() {
        assertThat(
                        PoreloCli.missingWorkingDirectory(
                                new RuntimeException(
                                        "wrapped",
                                        new Error(
                                                "Could not determine current working directory."))))
                .isTrue();
    }

    @Test
    void leavesEveryOtherFailureAlone() {

        // Swallowing an unrelated Error would hide a real fault behind advice to cd elsewhere.
        assertThat(PoreloCli.missingWorkingDirectory(new OutOfMemoryError("Java heap space")))
                .isFalse();
        assertThat(PoreloCli.missingWorkingDirectory(new Error())).isFalse();
        assertThat(PoreloCli.missingWorkingDirectory(new Error("no such file"))).isFalse();
    }

    @Test
    void theLoggingFacadeALibraryBringsSaysNothingAboutAMissingProvider() {

        // Every clearance.log began with three SLF4J warnings from the watcher's D-Bus client
        // (found on 2026-10-13).
        final String before = System.getProperty("slf4j.internal.verbosity");
        final java.io.PrintStream err = System.err;
        final java.io.ByteArrayOutputStream said = new java.io.ByteArrayOutputStream();
        try {
            System.clearProperty("slf4j.internal.verbosity");
            PoreloCli.quietLogging();
            System.setErr(
                    new java.io.PrintStream(said, true, java.nio.charset.StandardCharsets.UTF_8));
            org.slf4j.LoggerFactory.getLogger(PoreloCliTest.class).info("nobody reads this");

            assertThat(System.getProperty("slf4j.internal.verbosity")).isEqualTo("ERROR");
            assertThat(said.toString(java.nio.charset.StandardCharsets.UTF_8))
                    .doesNotContain("SLF4J");
        } finally {
            System.setErr(err);
            if (before == null) {
                System.clearProperty("slf4j.internal.verbosity");
            } else {
                System.setProperty("slf4j.internal.verbosity", before);
            }
        }
    }
}
