package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HostProjectCommandTest {
    @Test
    void aFailedInheritedRemovalReplyKeepsTheWriteFailureCode(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        try (ProjectFixture host = new ProjectFixture(root)) {
            final var project =
                    new HostProject(
                            host.files,
                            host.runtime,
                            (origin, token) -> {
                                throw new AssertionError("no forge is needed");
                            },
                            ProjectLock::acquire);
            final java.io.Writer broken =
                    new java.io.Writer() {
                        @Override
                        public void write(char[] chars, int offset, int length)
                                throws java.io.IOException {
                            throw new java.io.IOException("broken output");
                        }

                        @Override
                        public void flush() {}

                        @Override
                        public void close() {}
                    };
            assertThat(
                            project.run(
                                    "remove",
                                    java.util.List.of("unknown", "--lock-fd", "0"),
                                    new java.io.ByteArrayInputStream(new byte[0]),
                                    new PrintWriter(broken),
                                    new PrintWriter(new StringWriter())))
                    .as("a failed machine-readable refusal is EX_CANTCREAT, as in Python")
                    .isEqualTo(73);
        }
    }

    @Test
    void theProjectGroupRegistersItsFiveVerbs() {
        final var project = PoreloHost.commandLine().getSubcommands().get("project");
        assertThat(project).as("the privileged project helper has a command group").isNotNull();
        assertThat(project.getSubcommands().keySet())
                .as(
                        "the project group exposes registration, removal, migration, taking off and central state")
                .containsExactlyInAnyOrder("add", "remove", "migrate", "take-off", "central-state");
    }

    @ParameterizedTest
    @ValueSource(strings = {"add", "remove", "migrate", "take-off", "central-state", "unknown"})
    void invalidCallsKeepTheUsageCodeThroughTheOuterParser(String verb) {
        final StringWriter err = new StringWriter();
        final int code =
                PoreloHost.run(
                        new String[] {"project", verb},
                        Map.of(),
                        () -> 0,
                        new PrintWriter(new StringWriter()),
                        new PrintWriter(err));
        assertThat(code).as("the outer parser keeps EX_USAGE for %s: %s", verb, err).isEqualTo(64);
    }

    @ParameterizedTest
    @ValueSource(strings = {"remove", "add", "migrate", "take-off", "central-state"})
    void anEndOfOptionsDelimiterIsRefusedByTheOuterParser(String verb) {
        final StringWriter err = new StringWriter();
        final int code =
                PoreloHost.run(
                        new String[] {"project", verb, "--", "alpha"},
                        Map.of(),
                        () -> 0,
                        new PrintWriter(new StringWriter()),
                        new PrintWriter(err));
        assertThat(code).as("Python refuses -- before consulting the host: %s", err).isEqualTo(64);
    }

    @Test
    void theSigningKeyOptionReachesTheProjectParserWithoutBeingExpanded() {
        final StringWriter err = new StringWriter();
        final int code =
                PoreloHost.run(
                        new String[] {
                            "project",
                            "add",
                            "../escape",
                            "alice",
                            "--signed-by",
                            ProjectFixture.KEY
                        },
                        Map.of(),
                        () -> 0,
                        new PrintWriter(new StringWriter()),
                        new PrintWriter(err));
        assertThat(code).as("invalid project is refused locally").isEqualTo(64);
        assertThat(err.toString())
                .as("the whole legacy argument list reaches its validator")
                .contains("project must be");
    }
}
