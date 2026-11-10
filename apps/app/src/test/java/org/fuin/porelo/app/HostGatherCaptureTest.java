package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HostGatherCaptureTest {
    @TempDir Path dir;

    @ParameterizedTest
    @ValueSource(strings = {"raw", "basic"})
    void outputLimitsCannotTurnACredentialIntoAnUnredactableFragment(String form) {
        // Cutting a diagnostic through a credential must never reveal its surviving fragment.
        final var runner =
                new HostGatherRunner() {
                    @Override
                    protected Process start(ProcessBuilder builder) throws java.io.IOException {
                        builder.command(
                                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                HostGatherProcessChild.class.getName(),
                                "large-" + form,
                                dir.resolve("unused").toString());
                        return super.start(builder);
                    }
                };
        final String token = HostGatherProcessChild.boundaryToken();
        final String secret =
                form.equals("raw")
                        ? token
                        : java.util.Base64.getEncoder()
                                .encodeToString(
                                        ("x-access-token:" + token)
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        final String fragment = secret.substring(secret.length() - 1024, secret.length() - 512);
        final var config =
                new HostGatherConfig(
                        "http://127.0.0.1:3001",
                        "acme",
                        dir.resolve("ca.pem"),
                        List.of(
                                new HostGatherConfig.Repository(
                                        "first", "https://central.example/acme/first.git")));
        final StringWriter error = new StringWriter();
        final int code =
                new HostGatherFlow(runner, Path.of("/usr/bin/git"), dir)
                        .run(
                                false,
                                config,
                                List.of(),
                                "local-fixture-token".toCharArray(),
                                token.toCharArray(),
                                new PrintWriter(new StringWriter()),
                                new PrintWriter(error));
        assertThat(code).isEqualTo(1);
        assertThat(error.toString().contains(fragment))
                .as("a captured token fragment is withheld")
                .isFalse();
    }

    @org.junit.jupiter.api.Test
    void inheritedPipesAreBoundedAfterTheDirectChildExits() throws Exception {
        // An orphaned pipe must not hang a transfer while its credentials remain in memory.
        final Path marker = dir.resolve("children");
        final var runner =
                new HostGatherRunner() {
                    @Override
                    protected Process start(ProcessBuilder builder) throws java.io.IOException {
                        builder.command(
                                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                HostGatherProcessChild.class.getName(),
                                "orphan",
                                marker.toString());
                        return super.start(builder);
                    }
                };
        final var task =
                new java.util.concurrent.FutureTask<Throwable>(
                        () -> {
                            try {
                                runner.run(org.fuin.porelo.core.process.Command.of("git"));
                                return null;
                            } catch (RuntimeException ex) {
                                return ex;
                            }
                        });
        final Thread thread = Thread.ofVirtual().start(task);
        try {
            assertThat(task.get(4, java.util.concurrent.TimeUnit.SECONDS))
                    .isInstanceOf(HostGatherRefusal.class);
        } finally {
            runner.close();
            thread.interrupt();
            if (java.nio.file.Files.exists(marker))
                for (String pid : java.nio.file.Files.readString(marker).split(" "))
                    ProcessHandle.of(Long.parseLong(pid)).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    @org.junit.jupiter.api.Test
    void aNonDumpableProcessKeepsItsNumericIdentity() {
        // Process dumpability must not make an ordinary person look like uid zero.
        final var args =
                java.util.List.of(
                        Path.of(System.getProperty("java.home"), "bin/java").toString(),
                        "--enable-native-access=ALL-UNNAMED",
                        "-cp",
                        System.getProperty("java.class.path"),
                        HostGatherProcessChild.class.getName(),
                        "uid",
                        dir.resolve("unused").toString());
        final var result =
                new org.fuin.porelo.core.process.ProcessCommandRunner()
                        .run(org.fuin.porelo.core.process.Command.of(args));
        assertThat(result.successful()).as(result.standardError()).isTrue();
        final var values = result.standardOutput().split(":");
        assertThat(values).hasSize(2);
        assertThat(values[1]).isEqualTo(values[0]);
    }

    @org.junit.jupiter.api.Test
    void deliberateShutdownIsNotAGitFailure() throws Exception {
        // Teardown must not report an intentional stop as a failed remote transfer.
        final Path marker = dir.resolve("running");
        final var runner =
                new HostGatherRunner() {
                    @Override
                    protected Process start(ProcessBuilder builder) throws java.io.IOException {
                        builder.command(
                                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                HostGatherProcessChild.class.getName(),
                                "spawn",
                                marker.toString());
                        return super.start(builder);
                    }
                };
        final var task =
                new java.util.concurrent.FutureTask<Object>(
                        () -> {
                            try {
                                return runner.run(org.fuin.porelo.core.process.Command.of("git"));
                            } catch (RuntimeException ex) {
                                return ex;
                            }
                        });
        Thread.ofVirtual().start(task);
        try {
            final long deadline =
                    System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!java.nio.file.Files.exists(marker) && System.nanoTime() < deadline)
                Thread.sleep(10);
            assertThat(java.nio.file.Files.exists(marker)).isTrue();
            runner.close();
            assertThat(task.get(4, java.util.concurrent.TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(
                            HostGatherRefusal.class,
                            refusal -> {
                                assertThat(refusal.code()).isEqualTo(130);
                                assertThat(refusal.getMessage()).isEmpty();
                            });
        } finally {
            runner.close();
        }
    }
}
