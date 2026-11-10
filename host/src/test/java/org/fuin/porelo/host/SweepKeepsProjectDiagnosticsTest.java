package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.fuin.porelo.wire.SystemProgram;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SweepKeepsProjectDiagnosticsTest {
    @TempDir Path root;

    @Test
    void aMissingCheckedProgramKeepsTheProjectIOExceptionDiagnostic() throws Exception {
        try (var fixture = new ProjectFixture(root)) {
            final HostPrograms missing =
                    new HostPrograms(
                            Duration.ofSeconds(1),
                            1024,
                            name -> {
                                throw new java.io.UncheckedIOException(
                                        new SystemProgram.Missing(name, List.of()));
                            });
            final ProjectRuntime runtime =
                    runtime(
                            fixture,
                            (args, input, env, timeout) -> {
                                if (args.getFirst().equals("/usr/bin/id")) return missing.run(args);
                                return fixture.runtime.run(args, input, env, timeout);
                            });
            assertProjectDependencyRefusal(fixture, runtime);
        }
    }

    @Test
    void anUnreadableGetentKeepsTheProjectIOExceptionDiagnostic() throws Exception {
        try (var fixture = new ProjectFixture(root)) {
            final ProjectRuntime runtime =
                    runtime(
                            fixture,
                            (args, input, env, timeout) -> {
                                if (args.getFirst().equals("/usr/bin/getent"))
                                    throw new IOException("private failure");
                                return fixture.runtime.run(args, input, env, timeout);
                            });
            assertProjectDependencyRefusal(fixture, runtime);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void accountAndForgeLookupsPropagateReadFailures(boolean forge) throws Exception {
        try (var fixture = new ProjectFixture(root)) {
            final ProjectRuntime runtime =
                    runtime(
                            fixture,
                            (args, input, env, timeout) -> {
                                if (args.getFirst().equals("/usr/bin/getent"))
                                    throw new IOException("private failure");
                                return fixture.runtime.run(args, input, env, timeout);
                            });
            assertThatThrownBy(
                            () -> {
                                if (forge)
                                    runtime.forge(
                                            java.util.Map.of("account", "forge"),
                                            fixture.records.conf());
                                else runtime.exec("forge", List.of("true"));
                            })
                    .as("dependency read failures cannot be reported as an absent account")
                    .isExactlyInstanceOf(IOException.class);
        }
    }

    private ProjectRuntime runtime(ProjectFixture fixture, ProjectRuntime.Commands commands) {
        return new ProjectRuntime(
                fixture.files,
                commands,
                Path::of,
                path -> true,
                (path, token) -> {
                    throw new AssertionError("no forge call after a dependency failure");
                });
    }

    private void assertProjectDependencyRefusal(ProjectFixture fixture, ProjectRuntime runtime) {
        final StringWriter err = new StringWriter();
        final HostProject project =
                new HostProject(
                        fixture.files,
                        runtime,
                        (origin, token) -> {
                            throw new AssertionError("no central request");
                        },
                        fixture.locking);
        final int code =
                project.run(
                        "add",
                        List.of("alpha", "alice", "--signed-by", ProjectFixture.KEY),
                        new ByteArrayInputStream(new byte[0]),
                        new PrintWriter(new StringWriter()),
                        new PrintWriter(err));
        assertThat(code).as("a project verb keeps EX_UNAVAILABLE").isEqualTo(69);
        assertThat(err.toString())
                .as("the sweep's shared runner keeps the project verbs' dependency classification")
                .isEqualTo("porelo-host: dependency or file read failed: IOException\n");
    }
}
